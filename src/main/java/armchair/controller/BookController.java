package armchair.controller;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookType;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import armchair.repository.RankingStateRepository;
import armchair.repository.UserRepository;
import armchair.service.GoogleBooksService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class BookController {
    public record BookInfo(Long id, String googleBooksId, String isbn13, String title, String author, String review) {
        public String bookUrl() {
            if (googleBooksId != null) return "https://books.google.com/books?id=" + googleBooksId;
            if (isbn13 != null) return "https://www.google.com/search?tbo=p&tbm=bks&q=isbn:" + isbn13;
            return null;
        }
    }
    public record BookLists(List<BookInfo> liked, List<BookInfo> ok, List<BookInfo> disliked, List<BookInfo> unranked) {}
    public record ProfileDisplay(String username, String stats) {}
    public record ProfileDisplayWithFollow(String username, String stats, Long userId, boolean isFollowing) {}
    public record UserBookRank(int rank, String category, String type) {}

    private enum Mode {
        LIST,
        CATEGORIZE,
        RANK,
        RE_RANK,
        REMOVE,
        REVIEW;
    }

    private static final String SESSION_GUEST_USER_ID = "guestUserId";
    private static final int MAX_REVIEW_LENGTH = 5000;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private RankingStateRepository rankingStateRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private GoogleBooksService googleBooksService;

    private void cacheBookResults(List<GoogleBooksService.BookResult> results) {
        for (GoogleBooksService.BookResult result : results) {
            if (result.isbn13() == null) continue;
            if (bookRepository.findByIsbn13(result.isbn13()).isEmpty()) {
                bookRepository.save(new Book(result.googleBooksId(), result.title(), result.author(), result.isbn13()));
            }
        }
    }

    private Book findOrCreateBook(String googleBooksId, String title, String author, String isbn13) {
        if (googleBooksId != null) {
            return bookRepository.findByGoogleBooksId(googleBooksId)
                .orElseGet(() -> bookRepository.save(new Book(googleBooksId, title, author, isbn13)));
        }
        return bookRepository.save(new Book(null, title, author, isbn13));
    }

    @PostConstruct
    public void init() {
        // Clean up ALL guest users on startup
        // Assumption: On Render, the VM goes to sleep after 15 minutes of inactivity.
        // When the app restarts, all guests are from a previous session and can be safely deleted.
        List<User> guests = userRepository.findByIsGuest(true);

        for (User guest : guests) {
            // Delete associated data
            rankingRepository.deleteByUserId(guest.getId());
            rankingStateRepository.deleteById(guest.getId());
            userRepository.delete(guest);
        }

        if (!guests.isEmpty()) {
            System.out.println("Cleaned up " + guests.size() + " guest users on startup");
        }
    }

    private String getOauthSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OAuth2User) {
            OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
            return oauth2User.getAttribute("sub"); // OpenID "sub" claim is the unique user ID
        }
        return null;
    }

    private void addNavigationAttributes(Model model, String currentPage) {
        boolean isLoggedIn = getOauthSubject() != null;
        model.addAttribute("isLoggedIn", isLoggedIn);
        model.addAttribute("currentPage", currentPage);
    }

    private Long getCurrentUserId(HttpSession session) {
        String oauthSubject = getOauthSubject();

        if (oauthSubject != null) {
            // Find user by their OAuth subject
            User user = userRepository.findByOauthSubject(oauthSubject).orElse(null);
            if (user != null) {
                return user.getId();
            }
            // User is authenticated but hasn't set up username yet - return null to signal setup needed
            return null;
        }

        // Guest user - get or create from session
        Long guestUserId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
        if (guestUserId != null) {
            User guest = userRepository.findById(guestUserId).orElse(null);
            if (guest != null) {
                return guestUserId;
            }
        }

        // Create new guest user
        User newGuest = new User("guest");
        newGuest.setGuest(true);
        userRepository.save(newGuest);
        session.setAttribute(SESSION_GUEST_USER_ID, newGuest.getId());
        return newGuest.getId();
    }

    @GetMapping("/")
    public String showWelcome(Model model, HttpSession session) {
        getCurrentUserId(session); // Ensure guest user exists and update activity
        addNavigationAttributes(model, "about");
        return "welcome";
    }

    @PostMapping("/my-books")
    public String goToMyBooks(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId != null) {
            rankingStateRepository.deleteById(userId);
            session.removeAttribute("bookSearchResults");
        }
        return "redirect:/my-books";
    }

    @GetMapping("/my-books")
    public String showPage(Model model, HttpSession session, @RequestParam(required = false) String selectedType) {
        Long userId = getCurrentUserId(session);

        // If user is authenticated via OAuth but hasn't set up username, use guest mode for now
        // They'll be prompted to set up username when they click Profile
        if (userId == null && getOauthSubject() != null) {
            // Check if they already have a guest session (from before OAuth login)
            Long existingGuestId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
            if (existingGuestId != null) {
                User existingGuest = userRepository.findById(existingGuestId).orElse(null);
                if (existingGuest != null && existingGuest.isGuest()) {
                    // Reuse existing guest session
                    userId = existingGuestId;
                }
            }

            // If no existing guest session, create a new one
            if (userId == null) {
                User tempGuest = new User("guest");
                tempGuest.setGuest(true);
                userRepository.save(tempGuest);
                session.setAttribute(SESSION_GUEST_USER_ID, tempGuest.getId());
                userId = tempGuest.getId();
            }
        }

        addNavigationAttributes(model, "list");

        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        Mode mode = determineMode(rankingState);

        // Add username for display if logged in
        String userName = null;
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && !user.isGuest()) {
            userName = user.getUsername();
        }
        model.addAttribute("userName", userName);

        BookLists fictionBooks = getBookLists(BookType.FICTION, userId);
        BookLists nonfictionBooks = getBookLists(BookType.NONFICTION, userId);
        List<BookInfo> wantToReadBooks = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getGoogleBooksId(), r.getBook().getIsbn13(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> unrankedBooks = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.UNRANKED, BookCategory.UNRANKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getGoogleBooksId(), r.getBook().getIsbn13(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        boolean hasFiction = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty();
        boolean hasNonfiction = !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty();
        boolean hasWantToRead = !wantToReadBooks.isEmpty();
        boolean hasUnranked = !unrankedBooks.isEmpty();
        boolean hasAnyBooks = hasFiction || hasNonfiction || hasWantToRead || hasUnranked;

        // Determine selected type (default to fiction if exists, then nonfiction, then want-to-read, then unranked)
        String effectiveSelectedType = selectedType;
        if (effectiveSelectedType == null || effectiveSelectedType.isEmpty()) {
            if (hasFiction) {
                effectiveSelectedType = "FICTION";
            } else if (hasNonfiction) {
                effectiveSelectedType = "NONFICTION";
            } else if (hasWantToRead) {
                effectiveSelectedType = "WANT_TO_READ";
            } else if (hasUnranked) {
                effectiveSelectedType = "UNRANKED";
            } else {
                effectiveSelectedType = "FICTION"; // Default for empty state
            }
        }

        model.addAttribute("fictionBooks", fictionBooks);
        model.addAttribute("nonfictionBooks", nonfictionBooks);
        model.addAttribute("wantToReadBooks", wantToReadBooks);
        model.addAttribute("unrankedBooks", unrankedBooks);
        model.addAttribute("hasFiction", hasFiction);
        model.addAttribute("hasNonfiction", hasNonfiction);
        model.addAttribute("hasWantToRead", hasWantToRead);
        model.addAttribute("hasUnranked", hasUnranked);
        model.addAttribute("hasAnyBooks", hasAnyBooks);
        model.addAttribute("selectedType", effectiveSelectedType);
        model.addAttribute("rankingState", rankingState);

        // For RE_RANK mode, show LIST view but with rerank type set
        if (mode == Mode.RE_RANK) {
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", rankingState.getType().name());
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", null);
        } else if (mode == Mode.REMOVE) {
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            // For want-to-read, type is null but category is WANT_TO_READ
            if (rankingState.getType() != null) {
                model.addAttribute("removeType", rankingState.getType().name());
            } else if (rankingState.getCategory() == BookCategory.WANT_TO_READ) {
                model.addAttribute("removeType", "WANT_TO_READ");
            } else {
                model.addAttribute("removeType", null);
            }
            model.addAttribute("reviewType", null);
        } else if (mode == Mode.REVIEW && rankingState.getBookIdBeingReviewed() == null) {
            // Review mode but no book selected yet - show LIST with review type
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", rankingState.getType().name());
        } else if (mode == Mode.REVIEW) {
            // Review mode with book selected - show REVIEW screen
            model.addAttribute("mode", mode);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", null);
            // Load the ranking being reviewed
            Ranking rankingBeingReviewed = rankingRepository.findById(rankingState.getBookIdBeingReviewed()).orElse(null);
            if (rankingBeingReviewed != null) {
                model.addAttribute("reviewBook", rankingBeingReviewed);
            }
        } else {
            model.addAttribute("mode", mode);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", null);
        }

        boolean isRankAll = rankingState != null && rankingState.isRankAll();
        model.addAttribute("isRankAll", isRankAll);
        if (isRankAll) {
            int remainingUnranked = unrankedBooks.size();
            model.addAttribute("rankAllRemaining", remainingUnranked);
        }

        if (rankingState != null && mode == Mode.RANK && rankingState.getTitleBeingRanked() != null) {
            List<Ranking> currentList = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                userId, rankingState.getType(), rankingState.getCategory()
            );
            Ranking compRanking = currentList.get(rankingState.getCompareToIndex());
            model.addAttribute("comparisonBookTitle", compRanking.getBook().getTitle());
            model.addAttribute("comparisonBookAuthor", compRanking.getBook().getAuthor());
            model.addAttribute("comparisonBookGoogleId", compRanking.getBook().getGoogleBooksId());
            model.addAttribute("comparisonBookIsbn13", compRanking.getBook().getIsbn13());
        }

        return "index";
    }

    private Mode determineMode(RankingState rankingState) {
        if (rankingState == null) {
            return Mode.LIST;
        }
        if (rankingState.isReview() && rankingState.getBookIdBeingReviewed() != null) {
            return Mode.REVIEW;
        }
        if (rankingState.getTitleBeingRanked() == null) {
            if (rankingState.isReRank()) {
                return Mode.RE_RANK;
            }
            if (rankingState.isRemove()) {
                return Mode.REMOVE;
            }
            if (rankingState.isReview()) {
                return Mode.REVIEW;
            }
            // No longer using ADD mode - orphaned RankingStates show LIST
            return Mode.LIST;
        }
        if (rankingState.getCategory() == null) {
            return Mode.CATEGORIZE;
        }
        return Mode.RANK;
    }

    private BookLists getBookLists(BookType type, Long userId) {
        List<BookInfo> liked = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.LIKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getGoogleBooksId(), r.getBook().getIsbn13(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> ok = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.OK)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getGoogleBooksId(), r.getBook().getIsbn13(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> disliked = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.DISLIKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getGoogleBooksId(), r.getBook().getIsbn13(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> unranked = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.UNRANKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getGoogleBooksId(), r.getBook().getIsbn13(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        return new BookLists(liked, ok, disliked, unranked);
    }

    private String generateCsv(Long userId) {
        StringBuilder csv = new StringBuilder();
        csv.append("Rank,Title,Author,ISBN13,Category,Type,Review\n");

        int rank = 1;

        // Iterate through all types and categories
        for (BookType type : BookType.values()) {
            for (BookCategory category : BookCategory.values()) {
                List<Ranking> rankings = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                    userId, type, category
                );
                for (Ranking ranking : rankings) {
                    csv.append(rank++).append(",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getTitle())).append("\",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getAuthor())).append("\",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getIsbn13())).append("\",");
                    csv.append("\"").append(category.getValue()).append("\",");
                    csv.append("\"").append(type.getValue()).append("\",");
                    csv.append("\"").append(escapeCsv(ranking.getReview())).append("\"\n");
                }
            }
        }

        return csv.toString();
    }

    private String escapeCsv(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\"", "\"\"");
    }

    @PostMapping("/choose")
    public String chooseBook(@RequestParam String choice, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null) {
            return "redirect:/my-books";
        }

        List<Ranking> currentList = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, rankingState.getType(), rankingState.getCategory()
        );

        int newLowIndex = rankingState.getLowIndex();
        int newHighIndex = rankingState.getHighIndex();

        if ("new".equals(choice)) {
            // New book is better, search lower indices (towards index 0)
            newHighIndex = rankingState.getCompareToIndex() - 1;
        } else {
            // Existing book is better, search higher indices
            newLowIndex = rankingState.getCompareToIndex() + 1;
        }

        if (newLowIndex > newHighIndex) {
            // Found the insertion position - insert the book
            BookType rankedType = rankingState.getType();
            boolean wasRankAll = rankingState.isRankAll();
            insertBookAtPosition(rankingState.getGoogleBooksIdBeingRanked(), rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(),
                rankingState.getIsbn13BeingRanked(), rankingState.getReviewBeingRanked(), rankedType, rankingState.getCategory(), newLowIndex, userId);
            rankingStateRepository.deleteById(userId);
            // Clear search results
            session.removeAttribute("bookSearchResults");

            if (wasRankAll) {
                return startNextUnrankedBook(userId, rankedType);
            }
            return "redirect:/my-books?selectedType=" + rankedType.name();
        } else {
            // Continue binary search
            int newCompareToIndex = (newLowIndex + newHighIndex) / 2;
            rankingState.setCompareToIndex(newCompareToIndex);
            rankingState.setLowIndex(newLowIndex);
            rankingState.setHighIndex(newHighIndex);
            rankingStateRepository.save(rankingState);
        }

        return "redirect:/my-books";
    }

    private void insertBookAtPosition(String googleBooksId, String title, String author, String isbn13, String review, BookType type, BookCategory category, int position, Long userId) {
        // Shift all rankings at or after the insertion position
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, type, category
        );
        for (int i = rankingsToShift.size() - 1; i >= position; i--) {
            Ranking ranking = rankingsToShift.get(i);
            ranking.setPosition(ranking.getPosition() + 1);
            rankingRepository.save(ranking);
        }

        // Insert the new ranking
        Book book = findOrCreateBook(googleBooksId, title, author, isbn13);
        Ranking newRanking = new Ranking(userId, book, type, category, position);
        newRanking.setReview(review);
        rankingRepository.save(newRanking);
    }

    @PostMapping("/start-rerank")
    public String startRerank(@RequestParam String type, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        BookType bookType = BookType.fromString(type);
        RankingState rankingState = new RankingState(userId, null, null, null, bookType, null, 0, 0, 0);
        rankingState.setReRank(true);
        rankingStateRepository.save(rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/select-rerank-book")
    public String selectRerankBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Store book info in ranking state
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isReRank()) {
            return "redirect:/my-books";
        }

        rankingState.setGoogleBooksIdBeingRanked(ranking.getBook().getGoogleBooksId());
        rankingState.setTitleBeingRanked(ranking.getBook().getTitle());
        rankingState.setAuthorBeingRanked(ranking.getBook().getAuthor());
        rankingState.setIsbn13BeingRanked(ranking.getBook().getIsbn13());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from its current position
        BookType type = ranking.getType();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, type, category
        );
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @PostMapping("/start-remove")
    public String startRemove(@RequestParam String type, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        BookType bookType = BookType.fromString(type);
        RankingState rankingState = new RankingState(userId, null, null, null, bookType, null, 0, 0, 0);
        rankingState.setRemove(true);
        rankingStateRepository.save(rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/start-remove-wtr")
    public String startRemoveWantToRead(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = new RankingState(userId, null, null, null, null, BookCategory.WANT_TO_READ, 0, 0, 0);
        rankingState.setRemove(true);
        rankingStateRepository.save(rankingState);
        return "redirect:/my-books?selectedType=WANT_TO_READ";
    }

    @PostMapping("/select-remove-wtr-book")
    public String selectRemoveWantToReadBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getCategory() != BookCategory.WANT_TO_READ) {
            return "redirect:/my-books?selectedType=WANT_TO_READ";
        }

        // Verify we're in remove mode for want-to-read
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isRemove() || rankingState.getCategory() != BookCategory.WANT_TO_READ) {
            return "redirect:/my-books?selectedType=WANT_TO_READ";
        }

        // Remove the ranking
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        // Clear the ranking state
        rankingStateRepository.deleteById(userId);

        return "redirect:/my-books?selectedType=WANT_TO_READ";
    }

    @PostMapping("/direct-review")
    public String directReview(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Set up review mode directly
        RankingState rankingState = new RankingState(userId, null, null, null, ranking.getType(), null, 0, 0, 0);
        rankingState.setReview(true);
        rankingState.setBookIdBeingReviewed(bookId);
        rankingStateRepository.save(rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/direct-rerank")
    public String directRerank(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Store book info in ranking state (including existing review)
        RankingState rankingState = new RankingState(userId, ranking.getBook().getGoogleBooksId(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), ranking.getType(), null, 0, 0, 0);
        rankingState.setReRank(true);
        rankingState.setIsbn13BeingRanked(ranking.getBook().getIsbn13());
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from its current position
        BookType type = ranking.getType();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, category);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @PostMapping("/direct-remove")
    public String directRemove(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        BookType type = ranking.getType();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        String selectedType = type != null ? type.name() : "FICTION";
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, category);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books?selectedType=" + selectedType;
    }

    @PostMapping("/mark-as-read")
    public String markAsRead(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user and is in want-to-read
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getCategory() != BookCategory.WANT_TO_READ) {
            return "redirect:/my-books?selectedType=WANT_TO_READ";
        }

        // Store book info in ranking state for categorization
        RankingState rankingState = new RankingState(userId, ranking.getBook().getGoogleBooksId(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null, 0, 0, 0);
        rankingState.setIsbn13BeingRanked(ranking.getBook().getIsbn13());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from want-to-read list
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @PostMapping("/remove-from-reading-list")
    public String removeFromReadingList(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user and is in want-to-read
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getCategory() != BookCategory.WANT_TO_READ) {
            return "redirect:/my-books?selectedType=WANT_TO_READ";
        }

        // Remove the ranking
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books?selectedType=WANT_TO_READ";
    }

    @PostMapping("/select-remove-book")
    public String selectRemoveBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Verify we're in remove mode
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isRemove()) {
            return "redirect:/my-books";
        }

        // Remove the ranking
        BookType type = ranking.getType();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, type, category
        );
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        // Clear the ranking state
        rankingStateRepository.deleteById(userId);

        return "redirect:/my-books";
    }

    @PostMapping("/start-review")
    public String startReview(@RequestParam String type, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        BookType bookType = BookType.fromString(type);
        RankingState rankingState = new RankingState(userId, null, null, null, bookType, null, 0, 0, 0);
        rankingState.setReview(true);
        rankingStateRepository.save(rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/select-review-book")
    public String selectReviewBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Verify we're in review mode
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isReview()) {
            return "redirect:/my-books";
        }

        // Set the ranking being reviewed
        rankingState.setBookIdBeingReviewed(bookId);
        rankingStateRepository.save(rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/save-review")
    public String saveReview(@RequestParam(required = false) String review, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isReview() || rankingState.getBookIdBeingReviewed() == null) {
            return "redirect:/my-books";
        }

        // Find the ranking and update its review
        Ranking ranking = rankingRepository.findById(rankingState.getBookIdBeingReviewed()).orElse(null);
        if (ranking != null && ranking.getUserId().equals(userId)) {
            // Trim review, treat empty as null, and limit length
            String trimmedReview = (review != null && !review.isBlank()) ? review.trim() : null;
            if (trimmedReview != null && trimmedReview.length() > MAX_REVIEW_LENGTH) {
                trimmedReview = trimmedReview.substring(0, MAX_REVIEW_LENGTH);
            }
            ranking.setReview(trimmedReview);
            rankingRepository.save(ranking);
        }

        // Clear the ranking state
        rankingStateRepository.deleteById(userId);

        return "redirect:/my-books";
    }

    @GetMapping("/search-books")
    public String showSearchBooks(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "search-books");

        List<GoogleBooksService.BookResult> searchResults =
            (List<GoogleBooksService.BookResult>) session.getAttribute("bookSearchResults");
        model.addAttribute("searchResults", searchResults != null ? searchResults : List.of());
        model.addAttribute("query", session.getAttribute("bookSearchQuery"));

        return "search-books";
    }

    @PostMapping("/search-books")
    public String searchBooks(@RequestParam String query, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        List<GoogleBooksService.BookResult> results = googleBooksService.searchBooks(query);
        cacheBookResults(results);
        session.setAttribute("bookSearchResults", results);
        session.setAttribute("bookSearchQuery", query);

        // Check if user is in ADD mode (came from My Books page)
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState != null && rankingState.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }

        return "redirect:/search-books";
    }

    @PostMapping("/cancel-add")
    public String cancelAdd(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        rankingStateRepository.deleteById(userId);
        // Clear search results
        session.removeAttribute("bookSearchResults");
        return "redirect:/my-books";
    }

    @PostMapping("/select-book")
    public String selectBook(@RequestParam String googleBooksId,
                             @RequestParam String bookName,
                             @RequestParam String author,
                             @RequestParam(required = false) String isbn13,
                             HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null) {
            // Create new RankingState for user coming from Search Books tab
            rankingState = new RankingState(userId, null, null, null, null, null, 0, 0, 0);
        }

        // Set the book info, leave category null to enter CATEGORIZE mode
        rankingState.setGoogleBooksIdBeingRanked(googleBooksId);
        rankingState.setTitleBeingRanked(bookName);
        rankingState.setAuthorBeingRanked(author);
        rankingState.setIsbn13BeingRanked(isbn13);
        rankingStateRepository.save(rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/add-to-reading-list")
    public String addToReadingList(@RequestParam String googleBooksId,
                                   @RequestParam String bookName,
                                   @RequestParam String author,
                                   @RequestParam(required = false) String isbn13,
                                   @RequestParam(required = false) String returnUrl,
                                   HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        String redirectTo = returnUrl != null && !returnUrl.isBlank() ? "redirect:" + returnUrl : "redirect:/search?type=books";

        // Check if book is already in user's library (any category)
        if (rankingRepository.existsByUserIdAndBookGoogleBooksId(userId, googleBooksId)) {
            return redirectTo;
        }

        // Add ranking directly to want-to-read list at the end
        List<Ranking> wantToReadRankings = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ);
        int position = wantToReadRankings.size();
        Book book = findOrCreateBook(googleBooksId, bookName, author, isbn13);
        Ranking newRanking = new Ranking(userId, book, null, BookCategory.WANT_TO_READ, position);
        rankingRepository.save(newRanking);

        return redirectTo;
    }

    @PostMapping("/categorize")
    public String categorizeBook(@RequestParam String type,
                                  @RequestParam String category,
                                  @RequestParam(required = false) String review,
                                  HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || rankingState.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }

        String googleBooksId = rankingState.getGoogleBooksIdBeingRanked();
        String bookName = rankingState.getTitleBeingRanked();
        String author = rankingState.getAuthorBeingRanked();
        // Trim review, treat empty as null, and limit length
        String trimmedReview = (review != null && !review.isBlank()) ? review.trim() : null;
        if (trimmedReview != null && trimmedReview.length() > MAX_REVIEW_LENGTH) {
            trimmedReview = trimmedReview.substring(0, MAX_REVIEW_LENGTH);
        }

        BookType bookType = BookType.fromString(type);
        BookCategory bookCategory = BookCategory.fromString(category);
        List<Ranking> currentList = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, bookType, bookCategory
        );

        String isbn13 = rankingState.getIsbn13BeingRanked();

        if (currentList.isEmpty()) {
            // Category is empty, insert at the start
            boolean wasRankAll = rankingState.isRankAll();
            Book book = findOrCreateBook(googleBooksId, bookName, author, isbn13);
            Ranking newRanking = new Ranking(userId, book, bookType, bookCategory, 0);
            newRanking.setReview(trimmedReview);
            rankingRepository.save(newRanking);
            rankingStateRepository.deleteById(userId);
            // Clear search results
            session.removeAttribute("bookSearchResults");

            if (wasRankAll) {
                return startNextUnrankedBook(userId, bookType);
            }
            return "redirect:/my-books?selectedType=" + bookType.name();
        } else {
            // Start binary search within this category
            int lowIndex = 0;
            int highIndex = currentList.size() - 1;
            int compareToIndex = (lowIndex + highIndex) / 2;
            rankingState.setGoogleBooksIdBeingRanked(googleBooksId);
            rankingState.setTitleBeingRanked(bookName);
            rankingState.setAuthorBeingRanked(author);
            rankingState.setReviewBeingRanked(trimmedReview);
            rankingState.setType(bookType);
            rankingState.setCategory(bookCategory);
            rankingState.setCompareToIndex(compareToIndex);
            rankingState.setLowIndex(lowIndex);
            rankingState.setHighIndex(highIndex);
            rankingStateRepository.save(rankingState);
        }

        return "redirect:/my-books";
    }

    @GetMapping("/export-csv")
    public ResponseEntity<String> exportCsv(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        String csv = generateCsv(userId);
        String filename = "books.csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", filename);

        return ResponseEntity.ok()
            .headers(headers)
            .body(csv);
    }

    @GetMapping("/search")
    public String showUnifiedSearch(@RequestParam(required = false, defaultValue = "books") String type,
                                     @RequestParam(required = false) String query,
                                     Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "search");
        model.addAttribute("searchType", type);
        model.addAttribute("query", query);

        if ("books".equals(type)) {
            Long userId = getCurrentUserId(session);
            Map<String, UserBookRank> userBooks = userId != null ? buildUserBooksMap(userId) : Map.of();

            List<GoogleBooksService.BookResult> results;
            if (query != null && !query.isBlank()) {
                results = googleBooksService.searchBooks(query);
                cacheBookResults(results);
            } else {
                // Show 10 random books from database when search is empty, excluding user's books
                results = bookRepository.findRandom10Books().stream()
                    .filter(b -> !userBooks.containsKey(b.getGoogleBooksId()))
                    .map(b -> new GoogleBooksService.BookResult(b.getGoogleBooksId(), b.getTitle(), b.getAuthor(), b.getIsbn13()))
                    .toList();
            }
            model.addAttribute("bookResults", results);

            if (userId != null) {
                model.addAttribute("userBooks", userBooks);
            }
        } else if ("profiles".equals(type)) {
            Long currentUserId = getCurrentUserId(session);
            boolean isLoggedIn = getOauthSubject() != null && currentUserId != null;
            User currentUser = isLoggedIn ? userRepository.findById(currentUserId).orElse(null) : null;
            boolean isRealUser = currentUser != null && !currentUser.isGuest();

            if (query != null && !query.isBlank()) {
                List<User> results;
                if (isRealUser) {
                    results = userRepository.findByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndUsernameContainingIgnoreCaseAndIdNot(query.trim(), currentUserId);
                } else {
                    results = userRepository.findByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndUsernameContainingIgnoreCase(query.trim());
                }
                List<ProfileDisplayWithFollow> profileDisplays = results.stream()
                    .map(u -> createProfileDisplayWithFollow(u, isRealUser ? currentUserId : null))
                    .toList();
                model.addAttribute("profileResults", profileDisplays);
            } else {
                List<User> recentProfiles;
                long totalProfiles;
                if (isRealUser) {
                    recentProfiles = userRepository.findTop10ByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndIdNotOrderBySignupDateDesc(currentUserId);
                    totalProfiles = userRepository.countByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndIdNot(currentUserId);
                } else {
                    recentProfiles = userRepository.findTop10ByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueOrderBySignupDateDesc();
                    totalProfiles = userRepository.countByIsGuestFalseAndIsCuratedFalseAndPublishListsTrue();
                }
                List<ProfileDisplayWithFollow> profileDisplays = recentProfiles.stream()
                    .map(u -> createProfileDisplayWithFollow(u, isRealUser ? currentUserId : null))
                    .toList();
                long moreCount = Math.max(0, totalProfiles - recentProfiles.size());
                model.addAttribute("profileResults", profileDisplays);
                model.addAttribute("moreProfilesCount", moreCount);
            }
            model.addAttribute("canFollow", isRealUser);
            model.addAttribute("userHasPublished", currentUser != null && currentUser.isPublishLists());
        } else if ("following".equals(type)) {
            Long currentUserId = getCurrentUserId(session);
            boolean isLoggedIn = getOauthSubject() != null && currentUserId != null;
            User currentUser = isLoggedIn ? userRepository.findById(currentUserId).orElse(null) : null;
            boolean isRealUser = currentUser != null && !currentUser.isGuest();

            if (isRealUser) {
                List<Follow> follows = followRepository.findByFollowerId(currentUserId);
                List<ProfileDisplayWithFollow> profileDisplays = follows.stream()
                    .map(f -> userRepository.findById(f.getFollowedId()).orElse(null))
                    .filter(u -> u != null)
                    .map(u -> createProfileDisplayWithFollow(u, currentUserId))
                    .toList();
                model.addAttribute("profileResults", profileDisplays);
            } else {
                model.addAttribute("profileResults", List.of());
            }
            model.addAttribute("canFollow", isRealUser);
            model.addAttribute("userHasPublished", currentUser != null && currentUser.isPublishLists());
        } else if ("followers".equals(type)) {
            Long currentUserId = getCurrentUserId(session);
            boolean isLoggedIn = getOauthSubject() != null && currentUserId != null;
            User currentUser = isLoggedIn ? userRepository.findById(currentUserId).orElse(null) : null;
            boolean isRealUser = currentUser != null && !currentUser.isGuest();

            if (isRealUser) {
                List<Follow> followers = followRepository.findByFollowedId(currentUserId);
                List<ProfileDisplayWithFollow> profileDisplays = followers.stream()
                    .map(f -> userRepository.findById(f.getFollowerId()).orElse(null))
                    .filter(u -> u != null)
                    .map(u -> createProfileDisplayWithFollow(u, currentUserId))
                    .toList();
                model.addAttribute("profileResults", profileDisplays);
            } else {
                model.addAttribute("profileResults", List.of());
            }
            model.addAttribute("canFollow", isRealUser);
            model.addAttribute("userHasPublished", currentUser != null && currentUser.isPublishLists());
        } else if ("curated".equals(type)) {
            if (query != null && !query.isBlank()) {
                List<User> results = userRepository.findByIsCuratedTrueAndUsernameContainingIgnoreCase(query.trim());
                model.addAttribute("curatedResults", results);
            } else {
                List<User> curatedUsers = userRepository.findByIsCurated(true);
                model.addAttribute("curatedResults", curatedUsers);
            }
        }

        return "search";
    }

    @GetMapping("/search-profiles")
    public String showExplore(@RequestParam(required = false) String query, Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "search");
        model.addAttribute("query", query);

        if (query != null && !query.isBlank()) {
            List<User> results = userRepository.findByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndUsernameContainingIgnoreCase(query.trim());
            List<ProfileDisplay> profileDisplays = results.stream()
                .map(this::createProfileDisplay)
                .toList();
            model.addAttribute("searchResults", profileDisplays);
        } else {
            // Show recent profiles when not searching (excluding curated lists, only those who opted in)
            List<User> recentProfiles = userRepository.findTop10ByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueOrderBySignupDateDesc();
            List<ProfileDisplay> profileDisplays = recentProfiles.stream()
                .map(this::createProfileDisplay)
                .toList();
            long totalProfiles = userRepository.countByIsGuestFalseAndIsCuratedFalseAndPublishListsTrue();
            long moreCount = Math.max(0, totalProfiles - recentProfiles.size());
            model.addAttribute("recentProfiles", profileDisplays);
            model.addAttribute("moreProfilesCount", moreCount);
        }

        return "explore";
    }

    private Map<String, UserBookRank> buildUserBooksMap(Long userId) {
        Map<String, UserBookRank> userBooks = new HashMap<>();

        for (BookType bookType : BookType.values()) {
            int rank = 1;
            for (BookCategory category : List.of(BookCategory.LIKED, BookCategory.OK, BookCategory.DISLIKED)) {
                List<Ranking> rankings = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, bookType, category);
                for (Ranking ranking : rankings) {
                    if (ranking.getBook().getGoogleBooksId() != null) {
                        userBooks.put(ranking.getBook().getGoogleBooksId(), new UserBookRank(rank, category.name().toLowerCase(), bookType.name().toLowerCase()));
                    }
                    rank++;
                }
            }
        }

        // Add want-to-read books
        List<Ranking> wantToReadRankings = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ);
        for (Ranking ranking : wantToReadRankings) {
            if (ranking.getBook().getGoogleBooksId() != null) {
                userBooks.put(ranking.getBook().getGoogleBooksId(), new UserBookRank(0, "want_to_read", ""));
            }
        }

        // Add unranked books
        List<Ranking> unrankedRankings = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.UNRANKED, BookCategory.UNRANKED);
        for (Ranking ranking : unrankedRankings) {
            if (ranking.getBook().getGoogleBooksId() != null) {
                userBooks.put(ranking.getBook().getGoogleBooksId(), new UserBookRank(0, "unranked", "unranked"));
            }
        }

        return userBooks;
    }

    private ProfileDisplay createProfileDisplay(User user) {
        long fictionCount = 0;
        long nonfictionCount = 0;
        for (BookCategory category : BookCategory.values()) {
            fictionCount += rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                user.getId(), BookType.FICTION, category).size();
            nonfictionCount += rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                user.getId(), BookType.NONFICTION, category).size();
        }

        String stats = String.format(" | %d fiction | %d non-fiction",
            fictionCount, nonfictionCount);

        return new ProfileDisplay(user.getUsername(), stats);
    }

    private ProfileDisplayWithFollow createProfileDisplayWithFollow(User user, Long currentUserId) {
        long fictionCount = 0;
        long nonfictionCount = 0;
        for (BookCategory category : BookCategory.values()) {
            fictionCount += rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                user.getId(), BookType.FICTION, category).size();
            nonfictionCount += rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                user.getId(), BookType.NONFICTION, category).size();
        }

        String stats = String.format("| %d fiction | %d non-fiction",
            fictionCount, nonfictionCount);

        boolean isFollowing = currentUserId != null && followRepository.existsByFollowerIdAndFollowedId(currentUserId, user.getId());

        return new ProfileDisplayWithFollow(user.getUsername(), stats, user.getId(), isFollowing);
    }

    @GetMapping("/curated-lists")
    public String showCurated(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "curated");
        List<User> curatedUsers = userRepository.findByIsCurated(true);
        model.addAttribute("curatedUsers", curatedUsers);
        return "curated";
    }

    @GetMapping("/recs")
    public String showRecs(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "recs");
        return "recs";
    }

    @GetMapping("/user/{username}")
    public String viewUser(@PathVariable String username, Model model, HttpSession session) {
        getCurrentUserId(session);

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return "redirect:/";
        }

        // Block access if user hasn't opted in to publish their lists (curated lists are always visible)
        if (!user.isCurated() && !user.isPublishLists()) {
            return "redirect:/";
        }

        // Keep the appropriate nav button selected based on user type
        addNavigationAttributes(model, user.isCurated() ? "curated" : "explore");

        BookLists fictionBooks = getBookLists(BookType.FICTION, user.getId());
        BookLists nonfictionBooks = getBookLists(BookType.NONFICTION, user.getId());
        boolean hasFiction = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty() || !fictionBooks.unranked().isEmpty();
        boolean hasNonfiction = !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty() || !nonfictionBooks.unranked().isEmpty();

        Long currentUserId = getCurrentUserId(session);
        Map<String, UserBookRank> userBooks = currentUserId != null ? buildUserBooksMap(currentUserId) : Map.of();

        model.addAttribute("viewUsername", user.getUsername());
        model.addAttribute("fictionBooks", fictionBooks);
        model.addAttribute("nonfictionBooks", nonfictionBooks);
        model.addAttribute("hasFiction", hasFiction);
        model.addAttribute("hasNonfiction", hasNonfiction);
        model.addAttribute("isCurated", user.isCurated());
        model.addAttribute("userBooks", userBooks);

        return "view-user";
    }

    @GetMapping("/my-profile")
    public String showProfile(Model model, HttpSession session) {
        // If user is authenticated via OAuth but hasn't set up username, send them to setup
        if (getOauthSubject() != null) {
            User existingUser = userRepository.findByOauthSubject(getOauthSubject()).orElse(null);
            if (existingUser == null) {
                // OAuth user without username - redirect to setup
                return "redirect:/setup-username";
            }
        }

        Long userId = getCurrentUserId(session);
        if (userId == null) {
            // Not logged in - redirect to home
            return "redirect:/";
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isGuest()) {
            // Guest user - redirect to home
            return "redirect:/";
        }

        addNavigationAttributes(model, "profile");

        // Count fiction and non-fiction books
        long fictionCount = 0;
        long nonfictionCount = 0;
        for (BookCategory category : BookCategory.values()) {
            fictionCount += rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.FICTION, category).size();
            nonfictionCount += rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.NONFICTION, category).size();
        }

        model.addAttribute("username", user.getUsername());
        model.addAttribute("signupDate", user.getSignupDate());
        model.addAttribute("signupNumber", user.getSignupNumber());
        model.addAttribute("fictionCount", fictionCount);
        model.addAttribute("nonfictionCount", nonfictionCount);
        model.addAttribute("hasAnyBooks", fictionCount + nonfictionCount > 0);
        model.addAttribute("publishLists", user.isPublishLists());

        return "profile";
    }

    @PostMapping("/follow")
    public String followUser(@RequestParam Long userId, @RequestParam(required = false) String returnUrl, HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/search?type=profiles";
        }

        Long currentUserId = getCurrentUserId(session);
        User currentUser = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
        if (currentUser == null || currentUser.isGuest()) {
            return "redirect:/search?type=profiles";
        }

        // Can't follow yourself
        if (currentUserId.equals(userId)) {
            return "redirect:/search?type=profiles";
        }

        // Check if target user exists
        User targetUser = userRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            return "redirect:/search?type=profiles";
        }

        // Check if already following
        if (!followRepository.existsByFollowerIdAndFollowedId(currentUserId, userId)) {
            Follow follow = new Follow(currentUserId, userId);
            followRepository.save(follow);
        }

        return "redirect:" + (returnUrl != null ? returnUrl : "/search?type=profiles");
    }

    @PostMapping("/unfollow")
    public String unfollowUser(@RequestParam Long userId, @RequestParam(required = false) String returnUrl, HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/search?type=profiles";
        }

        Long currentUserId = getCurrentUserId(session);
        User currentUser = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
        if (currentUser == null || currentUser.isGuest()) {
            return "redirect:/search?type=profiles";
        }

        // Delete the follow relationship
        followRepository.findByFollowerIdAndFollowedId(currentUserId, userId)
            .ifPresent(followRepository::delete);

        return "redirect:" + (returnUrl != null ? returnUrl : "/search?type=profiles");
    }

    @PostMapping("/toggle-publish-lists")
    public String togglePublishLists(HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/";
        }

        User user = userRepository.findByOauthSubject(oauthSubject).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        user.setPublishLists(!user.isPublishLists());
        userRepository.save(user);

        return "redirect:/my-profile";
    }

    @GetMapping("/setup-username")
    public String showUsernameSetup(Model model) {
        // Only allow access if user is authenticated but doesn't have a username yet
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/";
        }

        User existingUser = userRepository.findByOauthSubject(oauthSubject).orElse(null);
        if (existingUser != null) {
            // User already has username, redirect to list
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "setup");
        return "setup-username";
    }

    @PostMapping("/setup-username")
    public String submitUsername(@RequestParam String username, Model model, HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "setup");

        // Validate username
        if (username == null || username.isBlank()) {
            model.addAttribute("error", "Username cannot be empty");
            return "setup-username";
        }

        username = username.trim();

        // Check length
        if (username.length() > 50) {
            model.addAttribute("error", "Username must be fewer than 50 characters");
            model.addAttribute("username", username);
            return "setup-username";
        }

        // Check if username already exists
        if (userRepository.existsByUsername(username)) {
            model.addAttribute("error", "Username already taken");
            model.addAttribute("username", username);
            return "setup-username";
        }

        // Create user with chosen username and OAuth subject
        User newUser = new User(username, oauthSubject);
        newUser.setGuest(false);

        // Set signup tracking - count real users (exclude guests and curated lists)
        long realUserCount = userRepository.countByIsGuestAndIsCurated(false, false);
        newUser.setSignupNumber(realUserCount + 1);
        newUser.setSignupDate(LocalDateTime.now());

        userRepository.save(newUser);

        // Migrate guest user data if they were using the app as a guest
        Long guestUserId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
        if (guestUserId != null) {
            migrateGuestDataToUser(guestUserId, newUser.getId());
            // Clear the guest user session
            session.removeAttribute(SESSION_GUEST_USER_ID);
        }

        return "redirect:/my-profile";
    }

    @GetMapping("/change-username")
    public String showChangeUsername(Model model, HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/";
        }
        User user = userRepository.findByOauthSubject(oauthSubject).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }
        addNavigationAttributes(model, "profile");
        model.addAttribute("username", user.getUsername());
        return "change-username";
    }

    @PostMapping("/change-username")
    public String changeUsername(@RequestParam String username, Model model, HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/";
        }
        User user = userRepository.findByOauthSubject(oauthSubject).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "profile");

        if (username == null || username.isBlank()) {
            model.addAttribute("error", "Username cannot be empty");
            model.addAttribute("username", user.getUsername());
            return "change-username";
        }

        username = username.trim();

        if (username.length() > 50) {
            model.addAttribute("error", "Username must be fewer than 50 characters");
            model.addAttribute("username", username);
            return "change-username";
        }

        // If same as current, just redirect back
        if (username.equals(user.getUsername())) {
            return "redirect:/my-profile";
        }

        if (userRepository.existsByUsername(username)) {
            model.addAttribute("error", "Username already taken");
            model.addAttribute("username", username);
            return "change-username";
        }

        user.setUsername(username);
        userRepository.save(user);

        return "redirect:/my-profile";
    }

    @GetMapping("/import-goodreads")
    public String showImportGoodreads(Model model, HttpSession session,
                                       @RequestParam(required = false) Integer imported,
                                       @RequestParam(required = false) Integer skipped) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }
        addNavigationAttributes(model, "profile");
        if (imported != null) {
            String message = "Successfully imported " + imported + " books";
            if (skipped != null && skipped > 0) {
                message += ", " + skipped + " were already in library";
            }
            model.addAttribute("resultMessage", message);
        }
        return "import-goodreads";
    }

    @PostMapping("/import-goodreads")
    public String importGoodreads(@RequestParam("file") MultipartFile file, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        int imported = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            // Read header row and find column indices
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return "redirect:/import-goodreads?imported=0&skipped=0";
            }
            List<String> headers = parseCsvLine(headerLine);
            int titleIndex = -1;
            int authorIndex = -1;
            int isbnIndex = -1;
            int isbn13Index = -1;
            int reviewIndex = -1;
            int exclusiveShelfIndex = -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).trim();
                if ("Title".equalsIgnoreCase(h)) titleIndex = i;
                else if ("Author".equalsIgnoreCase(h)) authorIndex = i;
                else if ("ISBN".equalsIgnoreCase(h)) isbnIndex = i;
                else if ("ISBN13".equalsIgnoreCase(h)) isbn13Index = i;
                else if ("My Review".equalsIgnoreCase(h)) reviewIndex = i;
                else if ("Exclusive Shelf".equalsIgnoreCase(h)) exclusiveShelfIndex = i;
            }
            if (titleIndex == -1 || authorIndex == -1) {
                return "redirect:/import-goodreads?imported=0&skipped=0";
            }

            // Get current max positions for unranked and want-to-read rankings
            List<Ranking> existingUnranked = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.UNRANKED, BookCategory.UNRANKED);
            int nextUnrankedPosition = existingUnranked.size();
            List<Ranking> existingWantToRead = rankingRepository.findByUserIdAndCategoryOrderByPositionAsc(userId, BookCategory.WANT_TO_READ);
            int nextWantToReadPosition = existingWantToRead.size();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = parseCsvLine(line);
                if (fields.size() <= Math.max(titleIndex, Math.max(authorIndex, reviewIndex >= 0 ? reviewIndex : 0))) {
                    continue;
                }

                String title = fields.get(titleIndex).trim();
                int colonIndex = title.indexOf(':');
                if (colonIndex >= 0) {
                    title = title.substring(0, colonIndex).trim();
                }
                String author = fields.get(authorIndex).trim();
                String rawIsbn = isbnIndex >= 0 && isbnIndex < fields.size() ? fields.get(isbnIndex).trim() : "";
                String rawIsbn13 = isbn13Index >= 0 && isbn13Index < fields.size() ? fields.get(isbn13Index).trim() : "";
                String review = reviewIndex >= 0 && reviewIndex < fields.size() ? fields.get(reviewIndex).trim() : "";
                String exclusiveShelf = exclusiveShelfIndex >= 0 && exclusiveShelfIndex < fields.size() ? fields.get(exclusiveShelfIndex).trim() : "";
                boolean isToRead = "to-read".equals(exclusiveShelf) || "currently-reading".equals(exclusiveShelf);
                if (title.isEmpty() || author.isEmpty()) continue;

                // Parse ISBN13 from Goodreads format (e.g. ="9781324074335"), fall back to ISBN-10 conversion
                String isbn13 = rawIsbn13.replaceAll("[=\"]", "");
                if (isbn13.isEmpty()) {
                    String isbn10 = rawIsbn.replaceAll("[=\"]", "");
                    isbn13 = isbn10.isEmpty() ? null : GoogleBooksService.convertIsbn10To13(isbn10);
                }

                // Check for duplicate by ISBN13
                if (isbn13 != null && rankingRepository.existsByUserIdAndBookIsbn13(userId, isbn13)) {
                    skipped++;
                } else {
                    // Trim review
                    String trimmedReview = (review != null && !review.isBlank()) ? review.trim() : null;
                    if (trimmedReview != null && trimmedReview.length() > MAX_REVIEW_LENGTH) {
                        trimmedReview = trimmedReview.substring(0, MAX_REVIEW_LENGTH);
                    }

                    // Reuse existing Book if one exists with this ISBN13
                    Book book;
                    if (isbn13 != null) {
                        String finalIsbn1 = isbn13;
                        book = bookRepository.findByIsbn13(isbn13)
                            .orElseGet(() -> bookRepository.save(new Book(null, title, author, finalIsbn1)));
                    } else {
                        book = bookRepository.save(new Book(null, title, author, null));
                    }

                    Ranking newRanking;
                    if (isToRead) {
                        newRanking = new Ranking(userId, book, null, BookCategory.WANT_TO_READ, nextWantToReadPosition);
                        nextWantToReadPosition++;
                    } else {
                        newRanking = new Ranking(userId, book, BookType.UNRANKED, BookCategory.UNRANKED, nextUnrankedPosition);
                        nextUnrankedPosition++;
                    }
                    newRanking.setReview(trimmedReview);
                    rankingRepository.save(newRanking);
                    imported++;
                }
            }
        } catch (Exception e) {
            System.err.println("Error importing Goodreads CSV: " + e.getMessage());
        }

        return "redirect:/import-goodreads?imported=" + imported + "&skipped=" + skipped;
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }

    @PostMapping("/rank-unranked-book")
    public String rankUnrankedBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getType() != BookType.UNRANKED) {
            return "redirect:/my-books?selectedType=UNRANKED";
        }

        // Create RankingState for categorization (no type/category set — enters CATEGORIZE mode)
        RankingState rankingState = new RankingState(userId, ranking.getBook().getGoogleBooksId(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null, 0, 0, 0);
        rankingState.setIsbn13BeingRanked(ranking.getBook().getIsbn13());
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from unranked list
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining unranked rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.UNRANKED, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @PostMapping("/rank-all")
    public String rankAll(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        return startNextUnrankedBook(userId, null);
    }

    private String startNextUnrankedBook(Long userId, BookType lastRankedType) {
        List<Ranking> unrankedBooks = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.UNRANKED, BookCategory.UNRANKED);
        if (unrankedBooks.isEmpty()) {
            // All done — redirect to the type of the last ranked book
            String selectedType = lastRankedType != null ? lastRankedType.name() : "FICTION";
            return "redirect:/my-books?selectedType=" + selectedType;
        }

        Ranking nextBook = unrankedBooks.get(0);

        // Create RankingState for categorization
        RankingState rankingState = new RankingState(userId, nextBook.getBook().getGoogleBooksId(), nextBook.getBook().getTitle(), nextBook.getBook().getAuthor(), null, null, 0, 0, 0);
        rankingState.setIsbn13BeingRanked(nextBook.getBook().getIsbn13());
        rankingState.setReviewBeingRanked(nextBook.getReview());
        rankingState.setRankAll(true);
        rankingStateRepository.save(rankingState);

        // Remove from unranked list
        int removedPosition = nextBook.getPosition();
        rankingRepository.delete(nextBook);

        // Shift remaining unranked rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.UNRANKED, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @PostMapping("/delete-profile")
    public String deleteProfile(HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject == null) {
            return "redirect:/";
        }

        User user = userRepository.findByOauthSubject(oauthSubject).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        Long userId = user.getId();

        // Delete all user's rankings
        rankingRepository.deleteByUserId(userId);

        // Delete ranking state if exists
        rankingStateRepository.deleteById(userId);

        // Delete all follow relationships (both where user is follower and followed)
        followRepository.findByFollowerId(userId).forEach(followRepository::delete);
        followRepository.findByFollowedId(userId).forEach(followRepository::delete);

        // Delete the user record
        userRepository.delete(user);

        // Invalidate the session
        session.invalidate();

        // Redirect to logout to clear OAuth session
        return "redirect:/logout";
    }

    private void migrateGuestDataToUser(Long guestUserId, Long newUserId) {
        // Migrate all rankings from guest to new user
        for (BookType type : BookType.values()) {
            for (BookCategory category : BookCategory.values()) {
                List<Ranking> guestRankings = rankingRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                    guestUserId, type, category
                );
                for (Ranking ranking : guestRankings) {
                    ranking.setUserId(newUserId);
                    rankingRepository.save(ranking);
                }
            }
        }

        // Migrate ranking state if exists
        RankingState guestRankingState = rankingStateRepository.findById(guestUserId).orElse(null);
        if (guestRankingState != null) {
            // Delete old ranking state and create new one for new user
            rankingStateRepository.deleteById(guestUserId);
            RankingState newRankingState = new RankingState(
                newUserId,
                guestRankingState.getGoogleBooksIdBeingRanked(),
                guestRankingState.getTitleBeingRanked(),
                guestRankingState.getAuthorBeingRanked(),
                guestRankingState.getType(),
                guestRankingState.getCategory(),
                guestRankingState.getCompareToIndex(),
                guestRankingState.getLowIndex(),
                guestRankingState.getHighIndex()
            );
            newRankingState.setIsbn13BeingRanked(guestRankingState.getIsbn13BeingRanked());
            rankingStateRepository.save(newRankingState);
        }

        // Delete the guest user record
        User guestUser = userRepository.findById(guestUserId).orElse(null);
        if (guestUser != null && guestUser.isGuest()) {
            userRepository.delete(guestUser);
        }
    }
}
