package armchair.controller;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import armchair.repository.RankingStateRepository;
import armchair.repository.UserRepository;
import armchair.service.OpenLibraryService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class BookController {
    private static final Logger log = LoggerFactory.getLogger(BookController.class);

    public record BookInfo(Long id, String workOlid, String title, String author, String review) {
        public String bookUrl() {
            if (workOlid != null) return "https://openlibrary.org/works/" + workOlid;
            return "https://openlibrary.org/search?q=" + java.net.URLEncoder.encode(title + " " + author, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public record BookLists(List<BookInfo> liked, List<BookInfo> ok, List<BookInfo> disliked, List<BookInfo> unranked) {}
    public record ProfileDisplay(String username, String stats) {}
    public record ProfileDisplayWithFollow(String username, String stats, Long userId, boolean isFollowing) {}
    public record UserBookRank(Long rankingId, int rank, String category, String bookshelf) {}

    private enum Mode {
        LIST,
        CATEGORIZE,
        RANK,
        RE_RANK,
        REMOVE,
        REVIEW,
        RESOLVE,
        MANUAL_RESOLVE,
        DUPLICATE_RESOLVE;
    }

    private static final String SESSION_GUEST_USER_ID = "guestUserId";
    private static final int MAX_REVIEW_LENGTH = 5000;
    private static final int MAX_IMPORT_ROWS = 10000;
    private static final java.util.regex.Pattern USERNAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]+$");

    private static boolean isSafeRedirectUrl(String url) {
        return url != null && url.startsWith("/") && !url.contains("://") && !url.contains("//");
    }

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
    private OpenLibraryService openLibraryService;

    private List<OpenLibraryService.BookResult> searchLocalBooks(String query) {
        String[] words = query.trim().split("\\s+");
        List<Book> candidates = null;
        for (String word : words) {
            if (word.isEmpty()) continue;
            List<Book> matches = bookRepository.searchByTitleOrAuthor(word);
            if (candidates == null) {
                candidates = new ArrayList<>(matches);
            } else {
                Set<Long> matchIds = matches.stream().map(Book::getId).collect(Collectors.toSet());
                candidates.removeIf(b -> !matchIds.contains(b.getId()));
            }
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .map(b -> new OpenLibraryService.BookResult(b.getWorkOlid(), b.getCoverEditionOlid(), b.getTitle(), b.getAuthor(), b.getFirstPublishYear()))
            .toList();
    }

    private Book findOrCreateBook(String workOlid, String coverEditionOlid, String title, String author, Integer firstPublishYear) {
        // Look up by workOlid
        if (workOlid != null) {
            var existing = bookRepository.findByWorkOlid(workOlid);
            if (existing.isPresent()) {
                Book book = existing.get();
                // Enrich with coverEditionOlid if missing
                if (book.getCoverEditionOlid() == null && coverEditionOlid != null) {
                    book.setCoverEditionOlid(coverEditionOlid);
                    bookRepository.save(book);
                }
                return book;
            }
        }

        // For unverified books (null workOlid), check by title+author to avoid duplicates
        if (workOlid == null) {
            var matches = bookRepository.findByTitleAndAuthorIgnoreCase(title, author);
            if (!matches.isEmpty()) {
                // Prefer the verified book (has workOlid), fall back to first
                return matches.stream().filter(b -> b.getWorkOlid() != null).findFirst().orElse(matches.get(0));
            }
        }

        // No match — create new Book
        return bookRepository.save(new Book(workOlid, coverEditionOlid, title, author, firstPublishYear));
    }

    private void restoreAbandonedBook(Long userId) {
        RankingState state = rankingStateRepository.findById(userId).orElse(null);
        if (state == null || state.getTitleBeingRanked() == null) return;

        Book book = findOrCreateBook(state.getWorkOlidBeingRanked(),
            null, state.getTitleBeingRanked(), state.getAuthorBeingRanked(), null);

        if (!rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
            List<Ranking> unranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
            Ranking restored = new Ranking(userId, book, Bookshelf.UNRANKED, BookCategory.UNRANKED, unranked.size());
            restored.setReview(state.getReviewBeingRanked());
            rankingRepository.save(restored);
        }
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
            log.info("Cleaned up {} guest users on startup", guests.size());
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
                // Migrate guest data if returning user was browsing as guest
                Long guestUserId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
                if (guestUserId != null && !guestUserId.equals(user.getId())) {
                    migrateGuestDataToUser(guestUserId, user.getId());
                    session.removeAttribute(SESSION_GUEST_USER_ID);
                }
                return user.getId();
            }
            // User is authenticated but hasn't set up username yet — fall through to guest mode
            // They'll be prompted to set up username when they visit Profile
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
            restoreAbandonedBook(userId);
            rankingStateRepository.deleteById(userId);
            session.removeAttribute("bookSearchResults");
            session.removeAttribute("skipResolve");
            session.removeAttribute("duplicateResolveTitle");
            session.removeAttribute("duplicateResolveWorkOlid");
            session.removeAttribute("duplicateResolveBookId");
        }
        return "redirect:/my-books";
    }

    @GetMapping("/my-books")
    public String showPage(Model model, HttpSession session, @RequestParam(required = false) String selectedBookshelf, @RequestParam(required = false) String resolveQuery) {
        Long userId = getCurrentUserId(session);

        addNavigationAttributes(model, "list");

        // Show resolve warning if any (one-time, cleared after display)
        String resolveWarning = (String) session.getAttribute("resolveWarning");
        if (resolveWarning != null) {
            model.addAttribute("resolveWarning", resolveWarning);
            session.removeAttribute("resolveWarning");
        }

        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        Mode mode = determineMode(rankingState);

        // Intercept for duplicate resolve — user picked a book during RESOLVE that's already in their library
        String duplicateResolveTitle = (String) session.getAttribute("duplicateResolveTitle");
        if (duplicateResolveTitle != null) {
            mode = Mode.DUPLICATE_RESOLVE;
            model.addAttribute("duplicateResolveTitle", duplicateResolveTitle);
        }

        // Intercept CATEGORIZE for unverified books — show RESOLVE screen
        if (mode == Mode.CATEGORIZE && rankingState.getWorkOlidBeingRanked() == null) {
            Object skipResolve = session.getAttribute("skipResolve");
            if ("manual".equals(skipResolve)) {
                // Manual search mode — user searches Open Library themselves
                mode = Mode.MANUAL_RESOLVE;
                if (resolveQuery != null && !resolveQuery.isBlank()) {
                    List<OpenLibraryService.BookResult> resolveResults =
                        openLibraryService.searchBooks(resolveQuery, 10);
                    var seen = new java.util.LinkedHashSet<String>();
                    resolveResults = resolveResults.stream()
                        .filter(r -> seen.add((r.title() + "\0" + r.author()).toLowerCase()))
                        .toList();
                    model.addAttribute("resolveResults", resolveResults);
                    model.addAttribute("resolveQuery", resolveQuery);
                    if (resolveResults.isEmpty()) {
                        model.addAttribute("resolveNoResults", true);
                    }
                }
            } else if (skipResolve == null || "expanded".equals(skipResolve)) {
                int maxResults = "expanded".equals(skipResolve) ? 10 : 3;
                List<OpenLibraryService.BookResult> resolveResults =
                    openLibraryService.searchByTitleAndAuthor(
                        rankingState.getTitleBeingRanked(),
                        rankingState.getAuthorBeingRanked(),
                        maxResults);
                // Deduplicate results with the same title+author (different editions)
                var seen = new java.util.LinkedHashSet<String>();
                resolveResults = resolveResults.stream()
                    .filter(r -> seen.add((r.title() + "\0" + r.author()).toLowerCase()))
                    .toList();
                if (!resolveResults.isEmpty()) {
                    mode = Mode.RESOLVE;
                    model.addAttribute("resolveResults", resolveResults);
                    // If API returned fewer results than requested, expanding won't help —
                    // next "None of these" click should abandon immediately
                    if (skipResolve == null && resolveResults.size() < maxResults) {
                        session.setAttribute("skipResolve", "expanded");
                    }
                } else if (skipResolve == null) {
                    // First attempt returned nothing — try expanded (10 results) immediately
                    resolveResults = openLibraryService.searchByTitleAndAuthor(
                        rankingState.getTitleBeingRanked(),
                        rankingState.getAuthorBeingRanked(),
                        10);
                    seen.clear();
                    resolveResults = resolveResults.stream()
                        .filter(r -> seen.add((r.title() + "\0" + r.author()).toLowerCase()))
                        .toList();
                    if (!resolveResults.isEmpty()) {
                        mode = Mode.RESOLVE;
                        model.addAttribute("resolveResults", resolveResults);
                    } else {
                        // No results at all — redirect to manual search
                        log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked());
                        session.setAttribute("skipResolve", "manual");
                        return "redirect:/my-books";
                    }
                } else {
                    // Expanded attempt also returned nothing — redirect to manual search
                    log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked());
                    session.setAttribute("skipResolve", "manual");
                    return "redirect:/my-books";
                }
            }
        }

        // Add username for display if logged in
        String userName = null;
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && !user.isGuest()) {
            userName = user.getUsername();
        }
        model.addAttribute("userName", userName);

        BookLists fictionBooks = getBookLists(Bookshelf.FICTION, userId);
        BookLists nonfictionBooks = getBookLists(Bookshelf.NONFICTION, userId);
        List<BookInfo> wantToReadBooks = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> unrankedBooks = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        boolean hasFiction = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty();
        boolean hasNonfiction = !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty();
        boolean hasWantToRead = !wantToReadBooks.isEmpty();
        boolean hasUnranked = !unrankedBooks.isEmpty();
        boolean hasAnyBooks = hasFiction || hasNonfiction || hasWantToRead || hasUnranked;

        // Determine selected bookshelf (default to fiction if exists, then nonfiction, then want-to-read, then unranked)
        String effectiveSelectedBookshelf = selectedBookshelf;
        if (effectiveSelectedBookshelf == null || effectiveSelectedBookshelf.isEmpty()) {
            if (hasFiction) {
                effectiveSelectedBookshelf = "FICTION";
            } else if (hasNonfiction) {
                effectiveSelectedBookshelf = "NONFICTION";
            } else if (hasWantToRead) {
                effectiveSelectedBookshelf = "WANT_TO_READ";
            } else if (hasUnranked) {
                effectiveSelectedBookshelf = "UNRANKED";
            } else {
                effectiveSelectedBookshelf = "FICTION"; // Default for empty state
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
        model.addAttribute("selectedBookshelf", effectiveSelectedBookshelf);
        model.addAttribute("rankingState", rankingState);

        // For RE_RANK mode, show LIST view but with rerank bookshelf set
        if (mode == Mode.RE_RANK) {
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", rankingState.getBookshelf().name());
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", null);
        } else if (mode == Mode.REMOVE) {
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", rankingState.getBookshelf().name());
            model.addAttribute("reviewType", null);
        } else if (mode == Mode.REVIEW && rankingState.getBookIdBeingReviewed() == null) {
            // Review mode but no book selected yet - show LIST with review type
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", rankingState.getBookshelf().name());
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
            List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                userId, rankingState.getBookshelf(), rankingState.getCategory()
            );
            Ranking compRanking = currentList.get(rankingState.getCompareToIndex());
            model.addAttribute("comparisonBookTitle", compRanking.getBook().getTitle());
            model.addAttribute("comparisonBookAuthor", compRanking.getBook().getAuthor());
            model.addAttribute("comparisonBookWorkOlid", compRanking.getBook().getWorkOlid());
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

    private BookLists getBookLists(Bookshelf bookshelf, Long userId) {
        List<BookInfo> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, BookCategory.LIKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> ok = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, BookCategory.OK)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> disliked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, BookCategory.DISLIKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        List<BookInfo> unranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, BookCategory.UNRANKED)
            .stream().map(r -> new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview())).toList();
        return new BookLists(liked, ok, disliked, unranked);
    }

    private String generateCsv(Long userId) {
        StringBuilder csv = new StringBuilder();
        csv.append("Rank,Title,Author,Category,Bookshelf,Review\n");

        int rank = 1;

        // Iterate through all bookshelves and categories
        for (Bookshelf bookshelf : Bookshelf.values()) {
            for (BookCategory category : BookCategory.values()) {
                List<Ranking> rankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                    userId, bookshelf, category
                );
                for (Ranking ranking : rankings) {
                    csv.append(rank++).append(",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getTitle())).append("\",");
                    csv.append("\"").append(escapeCsv(ranking.getBook().getAuthor())).append("\",");
                    csv.append("\"").append(category.getValue()).append("\",");
                    csv.append("\"").append(bookshelf.getValue()).append("\",");
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

    @Transactional
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

        List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, rankingState.getBookshelf(), rankingState.getCategory()
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
            Bookshelf rankedBookshelf = rankingState.getBookshelf();
            boolean wasRankAll = rankingState.isRankAll();
            insertBookAtPosition(rankingState.getWorkOlidBeingRanked(), rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(),
                rankingState.getReviewBeingRanked(), rankedBookshelf, rankingState.getCategory(), newLowIndex, userId);
            rankingStateRepository.deleteById(userId);
            // Clear search results
            session.removeAttribute("bookSearchResults");
            session.removeAttribute("skipResolve");

            if (wasRankAll) {
                return startNextUnrankedBook(userId, rankedBookshelf);
            }
            return "redirect:/my-books?selectedBookshelf=" + rankedBookshelf.name();
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

    private void insertBookAtPosition(String workOlid, String title, String author, String review, Bookshelf bookshelf, BookCategory category, int position, Long userId) {
        // Shift all rankings at or after the insertion position
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, bookshelf, category
        );
        for (int i = rankingsToShift.size() - 1; i >= position; i--) {
            Ranking ranking = rankingsToShift.get(i);
            ranking.setPosition(ranking.getPosition() + 1);
            rankingRepository.save(ranking);
        }

        // Insert the new ranking
        Book book = findOrCreateBook(workOlid, null, title, author, null);
        Ranking newRanking = new Ranking(userId, book, bookshelf, category, position);
        newRanking.setReview(review);
        rankingRepository.save(newRanking);
    }

    @PostMapping("/start-rerank")
    public String startRerank(@RequestParam String bookshelf, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        Bookshelf bookshlf;
        try {
            bookshlf = Bookshelf.fromString(bookshelf);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        restoreAbandonedBook(userId);
        RankingState rankingState = new RankingState(userId, null, null, null, bookshlf, null, 0, 0, 0);
        rankingState.setReRank(true);
        rankingStateRepository.save(rankingState);
        return "redirect:/my-books";
    }

    @Transactional
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

        rankingState.setWorkOlidBeingRanked(ranking.getBook().getWorkOlid());
        rankingState.setTitleBeingRanked(ranking.getBook().getTitle());
        rankingState.setAuthorBeingRanked(ranking.getBook().getAuthor());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from its current position
        Bookshelf bookshelf = ranking.getBookshelf();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, bookshelf, category
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
    public String startRemove(@RequestParam String bookshelf, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        Bookshelf bookshlf;
        try {
            bookshlf = Bookshelf.fromString(bookshelf);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        restoreAbandonedBook(userId);
        RankingState rankingState = new RankingState(userId, null, null, null, bookshlf, null, 0, 0, 0);
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
        restoreAbandonedBook(userId);
        RankingState rankingState = new RankingState(userId, null, null, null, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0, 0, 0);
        rankingState.setRemove(true);
        rankingStateRepository.save(rankingState);
        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @Transactional
    @PostMapping("/select-remove-wtr-book")
    public String selectRemoveWantToReadBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        // Verify we're in remove mode for want-to-read
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isRemove() || rankingState.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        // Remove the ranking
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        // Clear the ranking state
        rankingStateRepository.deleteById(userId);
        session.removeAttribute("skipResolve");

        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
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
        restoreAbandonedBook(userId);
        RankingState rankingState = new RankingState(userId, null, null, null, ranking.getBookshelf(), null, 0, 0, 0);
        rankingState.setReview(true);
        rankingState.setBookIdBeingReviewed(bookId);
        rankingStateRepository.save(rankingState);

        return "redirect:/my-books";
    }

    @Transactional
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
        restoreAbandonedBook(userId);
        RankingState rankingState = new RankingState(userId, ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), ranking.getBookshelf(), null, 0, 0, 0);
        rankingState.setReRank(true);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from its current position
        Bookshelf bookshelf = ranking.getBookshelf();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, category);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @Transactional
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

        Bookshelf bookshelf = ranking.getBookshelf();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        String selectedBookshelf = bookshelf.name();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, category);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
    }

    @Transactional
    @PostMapping("/mark-as-read")
    public String markAsRead(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user and is in want-to-read
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        // Store book info in ranking state for categorization
        RankingState rankingState = new RankingState(userId, ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null, 0, 0, 0);
        rankingStateRepository.save(rankingState);

        // Remove the ranking from want-to-read list
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/remove-from-reading-list")
    public String removeFromReadingList(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the ranking and verify it belongs to this user and is in want-to-read
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        // Remove the ranking
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @Transactional
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
        Bookshelf bookshelf = ranking.getBookshelf();
        BookCategory category = ranking.getCategory();
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining rankings in that category to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, bookshelf, category
        );
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        // Clear the ranking state
        rankingStateRepository.deleteById(userId);
        session.removeAttribute("skipResolve");

        return "redirect:/my-books";
    }

    @PostMapping("/start-review")
    public String startReview(@RequestParam String bookshelf, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        Bookshelf bookshlf;
        try {
            bookshlf = Bookshelf.fromString(bookshelf);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        RankingState rankingState = new RankingState(userId, null, null, null, bookshlf, null, 0, 0, 0);
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
        session.removeAttribute("skipResolve");

        return "redirect:/my-books";
    }

    @GetMapping("/search-books")
    public String showSearchBooks(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "search-books");

        List<OpenLibraryService.BookResult> searchResults =
            (List<OpenLibraryService.BookResult>) session.getAttribute("bookSearchResults");
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

        List<OpenLibraryService.BookResult> results = searchLocalBooks(query);
        if (results.isEmpty()) {
            results = openLibraryService.searchBooks(query);
        }
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
        restoreAbandonedBook(userId);
        rankingStateRepository.deleteById(userId);
        // Clear search results
        session.removeAttribute("bookSearchResults");
        session.removeAttribute("skipResolve");
        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/resolve-book")
    public String resolveBook(@RequestParam String workOlid,
                              @RequestParam String title,
                              @RequestParam String author,
                              @RequestParam(required = false) String coverEditionOlid,
                              HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || rankingState.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }

        // Check if user already has a ranking for a book with this workOlid
        if (rankingRepository.existsByUserIdAndBookWorkOlid(userId, workOlid)) {
            // Find the unverified book being ranked
            Book unverifiedBook = findOrCreateBook(rankingState.getWorkOlidBeingRanked(),
                null, rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(), null);
            session.setAttribute("duplicateResolveTitle", title);
            session.setAttribute("duplicateResolveWorkOlid", workOlid);
            session.setAttribute("duplicateResolveBookId", unverifiedBook.getId());
            session.removeAttribute("skipResolve");
            return "redirect:/my-books";
        }

        // Find the existing book and update it with the selected result's metadata
        Book existingBook = findOrCreateBook(rankingState.getWorkOlidBeingRanked(),
            null, rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(), null);
        existingBook.setWorkOlid(workOlid);
        existingBook.setCoverEditionOlid(coverEditionOlid);
        existingBook.setTitle(title);
        existingBook.setAuthor(author);
        bookRepository.save(existingBook);

        // Update RankingState to match
        rankingState.setWorkOlidBeingRanked(workOlid);
        rankingState.setTitleBeingRanked(title);
        rankingState.setAuthorBeingRanked(author);
        rankingStateRepository.save(rankingState);

        session.removeAttribute("skipResolve");
        return "redirect:/my-books";
    }

    @PostMapping("/skip-resolve")
    public String skipResolve(HttpSession session) {
        Object current = session.getAttribute("skipResolve");
        if ("expanded".equals(current)) {
            // User rejected all results — redirect to manual search
            session.setAttribute("skipResolve", "manual");
        } else {
            session.setAttribute("skipResolve", "expanded");
        }
        return "redirect:/my-books";
    }

    @PostMapping("/abandon-resolve")
    public String abandonResolve(HttpSession session) {
        Long userId = getCurrentUserId(session);
        RankingState rs = rankingStateRepository.findById(userId).orElse(null);
        String title = "unknown book";
        if (rs != null) {
            title = rs.getTitleBeingRanked();
            log.warn("RESOLVE abandoned: user skipped manual search for \"{}\" by {}", title, rs.getAuthorBeingRanked());
            rankingStateRepository.delete(rs);
        }
        session.removeAttribute("skipResolve");
        session.setAttribute("resolveWarning", title);
        return "redirect:/my-books?selectedBookshelf=UNRANKED";
    }

    @Transactional
    @PostMapping("/skip-duplicate-resolve")
    public String skipDuplicateResolve(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Long unverifiedBookId = (Long) session.getAttribute("duplicateResolveBookId");
        session.removeAttribute("duplicateResolveTitle");
        session.removeAttribute("duplicateResolveWorkOlid");
        session.removeAttribute("duplicateResolveBookId");

        // Delete ranking state
        rankingStateRepository.deleteById(userId);
        session.removeAttribute("skipResolve");

        // Delete the unverified book's ranking if the user has one, and the book if orphaned
        if (unverifiedBookId != null) {
            cleanupUnverifiedBook(userId, unverifiedBookId);
        }

        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/rerank-duplicate-resolve")
    public String rerankDuplicateResolve(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Long unverifiedBookId = (Long) session.getAttribute("duplicateResolveBookId");
        String duplicateWorkOlid = (String) session.getAttribute("duplicateResolveWorkOlid");
        session.removeAttribute("duplicateResolveTitle");
        session.removeAttribute("duplicateResolveWorkOlid");
        session.removeAttribute("duplicateResolveBookId");
        session.removeAttribute("skipResolve");

        // Clean up the unverified book
        if (unverifiedBookId != null) {
            cleanupUnverifiedBook(userId, unverifiedBookId);
        }

        // Find the existing ranking by workOlid
        Ranking existingRanking = duplicateWorkOlid != null
            ? rankingRepository.findByUserIdAndBookWorkOlid(userId, duplicateWorkOlid)
            : null;

        if (existingRanking == null) {
            rankingStateRepository.deleteById(userId);
            return "redirect:/my-books";
        }

        // Set up RankingState for re-ranking the existing book through CATEGORIZE
        Book existingBook = existingRanking.getBook();
        RankingState newState = new RankingState(userId, existingBook.getWorkOlid(), existingBook.getTitle(), existingBook.getAuthor(), null, null, 0, 0, 0);
        newState.setReRank(true);
        newState.setReviewBeingRanked(existingRanking.getReview());
        rankingStateRepository.save(newState);

        // Remove the existing ranking from its current position
        Bookshelf bookshelf = existingRanking.getBookshelf();
        BookCategory category = existingRanking.getCategory();
        int removedPosition = existingRanking.getPosition();
        rankingRepository.delete(existingRanking);

        // Shift remaining rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, category);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    private void cleanupUnverifiedBook(Long userId, Long bookId) {
        // Delete user's ranking for this book if they have one
        List<Ranking> userRankings = rankingRepository.findByUserId(userId).stream()
            .filter(r -> r.getBook().getId().equals(bookId))
            .toList();
        for (Ranking r : userRankings) {
            Bookshelf bookshelf = r.getBookshelf();
            BookCategory category = r.getCategory();
            int removedPosition = r.getPosition();
            rankingRepository.delete(r);

            // Shift remaining rankings to fill the gap
            List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, category);
            for (Ranking shift : rankingsToShift) {
                if (shift.getPosition() > removedPosition) {
                    shift.setPosition(shift.getPosition() - 1);
                    rankingRepository.save(shift);
                }
            }
        }

        // Delete the book if no other rankings reference it
        if (!rankingRepository.existsByBookId(bookId)) {
            bookRepository.deleteById(bookId);
        }
    }

    @PostMapping("/select-book")
    public String selectBook(@RequestParam String workOlid,
                             @RequestParam String bookName,
                             @RequestParam String author,
                             @RequestParam(required = false) String coverEditionOlid,
                             HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        restoreAbandonedBook(userId);
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null) {
            // Create new RankingState for user coming from Search Books tab
            rankingState = new RankingState(userId, null, null, null, null, null, 0, 0, 0);
        }

        // Set the book info, leave category null to enter CATEGORIZE mode
        rankingState.setWorkOlidBeingRanked(workOlid);
        rankingState.setTitleBeingRanked(bookName);
        rankingState.setAuthorBeingRanked(author);
        rankingStateRepository.save(rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/add-to-reading-list")
    public String addToReadingList(@RequestParam String workOlid,
                                   @RequestParam String bookName,
                                   @RequestParam String author,
                                   @RequestParam(required = false) String coverEditionOlid,
                                   @RequestParam(required = false) String returnUrl,
                                   HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        String redirectTo = isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=books";

        // Resolve the book
        Book book = findOrCreateBook(workOlid, coverEditionOlid, bookName, author, null);

        // Check if book is already in user's library (any category)
        if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
            return redirectTo;
        }

        // Add ranking directly to want-to-read list at the end
        List<Ranking> wantToReadRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        int position = wantToReadRankings.size();
        Ranking newRanking = new Ranking(userId, book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, position);
        rankingRepository.save(newRanking);

        return redirectTo;
    }

    @Transactional
    @PostMapping("/categorize")
    public String categorizeBook(@RequestParam String bookshelf,
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

        String workOlid = rankingState.getWorkOlidBeingRanked();
        String bookName = rankingState.getTitleBeingRanked();
        String author = rankingState.getAuthorBeingRanked();
        // Trim review, treat empty as null, and limit length
        String trimmedReview = (review != null && !review.isBlank()) ? review.trim() : null;
        if (trimmedReview != null && trimmedReview.length() > MAX_REVIEW_LENGTH) {
            trimmedReview = trimmedReview.substring(0, MAX_REVIEW_LENGTH);
        }

        Bookshelf bookshelfEnum;
        BookCategory bookCategory;
        try {
            bookshelfEnum = Bookshelf.fromString(bookshelf);
            bookCategory = BookCategory.fromString(category);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, bookshelfEnum, bookCategory
        );

        if (currentList.isEmpty()) {
            // Category is empty, insert at the start
            boolean wasRankAll = rankingState.isRankAll();
            Book book = findOrCreateBook(workOlid, null, bookName, author, null);
            Ranking newRanking = new Ranking(userId, book, bookshelfEnum, bookCategory, 0);
            newRanking.setReview(trimmedReview);
            rankingRepository.save(newRanking);
            rankingStateRepository.deleteById(userId);
            // Clear search results
            session.removeAttribute("bookSearchResults");
            session.removeAttribute("skipResolve");

            if (wasRankAll) {
                return startNextUnrankedBook(userId, bookshelfEnum);
            }
            return "redirect:/my-books?selectedBookshelf=" + bookshelfEnum.name();
        } else {
            // Start binary search within this category
            int lowIndex = 0;
            int highIndex = currentList.size() - 1;
            int compareToIndex = (lowIndex + highIndex) / 2;
            rankingState.setWorkOlidBeingRanked(workOlid);
            rankingState.setTitleBeingRanked(bookName);
            rankingState.setAuthorBeingRanked(author);
            rankingState.setReviewBeingRanked(trimmedReview);
            rankingState.setBookshelf(bookshelfEnum);
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

    private Long getExistingUserId(HttpSession session) {
        String oauthSubject = getOauthSubject();
        if (oauthSubject != null) {
            User user = userRepository.findByOauthSubject(oauthSubject).orElse(null);
            if (user != null) return user.getId();
            return null;
        }
        Long guestId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
        if (guestId != null) {
            User guest = userRepository.findById(guestId).orElse(null);
            if (guest != null) return guestId;
        }
        return null;
    }

    @GetMapping("/search")
    public String showUnifiedSearch(@RequestParam(required = false, defaultValue = "books") String type,
                                     @RequestParam(required = false) String query,
                                     @RequestParam(required = false) Boolean more,
                                     Model model, HttpSession session) {
        addNavigationAttributes(model, "search");
        model.addAttribute("searchType", type);
        model.addAttribute("query", query);

        Long currentUserId = getExistingUserId(session);
        boolean isLoggedIn = getOauthSubject() != null && currentUserId != null;
        User currentUser = isLoggedIn ? userRepository.findById(currentUserId).orElse(null) : null;
        boolean isRealUser = currentUser != null && !currentUser.isGuest();

        // --- Books tab ---
        Map<String, UserBookRank> userBooks = currentUserId != null ? buildUserBooksMap(currentUserId) : Map.of();
        List<OpenLibraryService.BookResult> bookResults;
        boolean localResults = false;
        boolean moreResults = false;
        if ("books".equals(type) && query != null && !query.isBlank()) {
            bookResults = searchLocalBooks(query);
            if (bookResults.isEmpty()) {
                bookResults = openLibraryService.searchBooks(query);
                // Deduplicate by title+author (API can return multiple editions)
                Set<String> seen = new java.util.LinkedHashSet<>();
                bookResults = bookResults.stream()
                    .filter(b -> seen.add(b.title().toLowerCase().trim() + "\t" + b.author().toLowerCase().trim()))
                    .toList();
            } else {
                localResults = true;
                if (Boolean.TRUE.equals(more)) {
                    moreResults = true;
                    // Build set of local result keys for deduplication
                    Set<String> localKeys = bookResults.stream()
                        .map(b -> b.title().toLowerCase().trim() + "\t" + b.author().toLowerCase().trim())
                        .collect(Collectors.toSet());
                    List<OpenLibraryService.BookResult> apiResults = openLibraryService.searchBooks(query);
                    // Deduplicate API results against local results and against themselves
                    Set<String> seen = new java.util.LinkedHashSet<>(localKeys);
                    List<OpenLibraryService.BookResult> extraResults = apiResults.stream()
                        .filter(b -> seen.add(b.title().toLowerCase().trim() + "\t" + b.author().toLowerCase().trim()))
                        .limit(3)
                        .toList();
                    bookResults = new ArrayList<>(bookResults);
                    bookResults.addAll(extraResults);
                }
            }
        } else {
            Map<String, UserBookRank> finalUserBooks = userBooks;
            bookResults = bookRepository.findRandom10Books().stream()
                .filter(b -> !finalUserBooks.containsKey(b.getWorkOlid()))
                .map(b -> new OpenLibraryService.BookResult(b.getWorkOlid(), b.getCoverEditionOlid(), b.getTitle(), b.getAuthor(), b.getFirstPublishYear()))
                .toList();
        }
        model.addAttribute("bookResults", bookResults);
        model.addAttribute("localResults", localResults);
        model.addAttribute("moreResults", moreResults);
        if (currentUserId != null) {
            model.addAttribute("userBooks", userBooks);
        }

        // --- Profiles tab ---
        if ("profiles".equals(type) && query != null && !query.isBlank()) {
            List<User> results;
            if (isRealUser) {
                results = userRepository.findByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndUsernameContainingIgnoreCaseAndIdNot(query.trim(), currentUserId);
            } else {
                results = userRepository.findByIsGuestFalseAndIsCuratedFalseAndPublishListsTrueAndUsernameContainingIgnoreCase(query.trim());
            }
            model.addAttribute("profileSearchResults", results.stream()
                .map(u -> createProfileDisplayWithFollow(u, isRealUser ? currentUserId : null)).toList());
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
            model.addAttribute("profileSearchResults", recentProfiles.stream()
                .map(u -> createProfileDisplayWithFollow(u, isRealUser ? currentUserId : null)).toList());
            model.addAttribute("moreProfilesCount", Math.max(0, totalProfiles - recentProfiles.size()));
        }

        // --- Following tab ---
        if (isRealUser) {
            List<Follow> follows = followRepository.findByFollowerId(currentUserId);
            model.addAttribute("followingResults", follows.stream()
                .map(f -> userRepository.findById(f.getFollowedId()).orElse(null))
                .filter(u -> u != null)
                .map(u -> createProfileDisplayWithFollow(u, currentUserId))
                .toList());
        } else {
            model.addAttribute("followingResults", List.of());
        }

        // --- Followers tab ---
        if (isRealUser) {
            List<Follow> followers = followRepository.findByFollowedId(currentUserId);
            model.addAttribute("followerResults", followers.stream()
                .map(f -> userRepository.findById(f.getFollowerId()).orElse(null))
                .filter(u -> u != null)
                .map(u -> createProfileDisplayWithFollow(u, currentUserId))
                .toList());
        } else {
            model.addAttribute("followerResults", List.of());
        }

        // --- Curated tab ---
        if ("curated".equals(type) && query != null && !query.isBlank()) {
            model.addAttribute("curatedResults", userRepository.findByIsCuratedTrueAndUsernameContainingIgnoreCase(query.trim()));
        } else {
            model.addAttribute("curatedResults", userRepository.findByIsCurated(true));
        }

        // Shared attributes
        model.addAttribute("canFollow", isRealUser);
        model.addAttribute("userHasPublished", currentUser != null && currentUser.isPublishLists());

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

    private void putBookKeys(Map<String, UserBookRank> userBooks, Book book, UserBookRank ubr) {
        if (book.getWorkOlid() != null) {
            userBooks.put(book.getWorkOlid(), ubr);
        }
    }

    private Map<String, UserBookRank> buildUserBooksMap(Long userId) {
        Map<String, UserBookRank> userBooks = new HashMap<>();

        for (Bookshelf bookshelf : List.of(Bookshelf.FICTION, Bookshelf.NONFICTION)) {
            int rank = 1;
            for (BookCategory category : List.of(BookCategory.LIKED, BookCategory.OK, BookCategory.DISLIKED)) {
                List<Ranking> rankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, category);
                for (Ranking ranking : rankings) {
                    UserBookRank ubr = new UserBookRank(ranking.getId(), rank, category.name().toLowerCase(), bookshelf.name().toLowerCase());
                    putBookKeys(userBooks, ranking.getBook(), ubr);
                    rank++;
                }
            }
        }

        // Add want-to-read books
        List<Ranking> wantToReadRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        for (Ranking ranking : wantToReadRankings) {
            UserBookRank ubr = new UserBookRank(ranking.getId(), 0, "want_to_read", "want_to_read");
            putBookKeys(userBooks, ranking.getBook(), ubr);
        }

        // Add unranked books
        List<Ranking> unrankedRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
        for (Ranking ranking : unrankedRankings) {
            UserBookRank ubr = new UserBookRank(ranking.getId(), 0, "unranked", "unranked");
            putBookKeys(userBooks, ranking.getBook(), ubr);
        }

        return userBooks;
    }

    private ProfileDisplay createProfileDisplay(User user) {
        long fictionCount = 0;
        long nonfictionCount = 0;
        for (BookCategory category : BookCategory.values()) {
            fictionCount += rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, category).size();
            nonfictionCount += rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, category).size();
        }

        String stats = String.format(" | %d fiction | %d non-fiction",
            fictionCount, nonfictionCount);

        return new ProfileDisplay(user.getUsername(), stats);
    }

    private ProfileDisplayWithFollow createProfileDisplayWithFollow(User user, Long currentUserId) {
        long fictionCount = 0;
        long nonfictionCount = 0;
        for (BookCategory category : BookCategory.values()) {
            fictionCount += rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, category).size();
            nonfictionCount += rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, category).size();
        }

        String stats = String.format(" | %d fiction | %d non-fiction",
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

        BookLists fictionBooks = getBookLists(Bookshelf.FICTION, user.getId());
        BookLists nonfictionBooks = getBookLists(Bookshelf.NONFICTION, user.getId());
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
            fictionCount += rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.FICTION, category).size();
            nonfictionCount += rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.NONFICTION, category).size();
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

        return isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=profiles";
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

        return isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=profiles";
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

        // Check characters
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            model.addAttribute("error", "Username can only contain letters, numbers, hyphens, and underscores");
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

        try {
            userRepository.save(newUser);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            model.addAttribute("error", "Username already taken");
            model.addAttribute("username", username);
            return "setup-username";
        }

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

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            model.addAttribute("error", "Username can only contain letters, numbers, hyphens, and underscores");
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
        try {
            userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            model.addAttribute("error", "Username already taken");
            model.addAttribute("username", username);
            return "change-username";
        }

        return "redirect:/my-profile";
    }

    @GetMapping("/import-goodreads")
    public String showImportGoodreads(Model model, HttpSession session,
                                       @RequestParam(required = false) Integer imported,
                                       @RequestParam(required = false) Integer skipped,
                                       @RequestParam(required = false) Integer failed) {
        Long userId = getCurrentUserId(session);
        addNavigationAttributes(model, "profile");
        if (imported != null) {
            String message = "Successfully imported " + imported + " books";
            if (skipped != null && skipped > 0) {
                message += ", " + skipped + " were already in library";
            }
            if (failed != null && failed > 0) {
                message += ", failed to import " + failed;
            }
            model.addAttribute("resultMessage", message);
        }
        return "import-goodreads";
    }

    @Transactional
    @PostMapping("/import-goodreads")
    public String importGoodreads(@RequestParam("file") MultipartFile file, HttpSession session) {
        Long userId = getCurrentUserId(session);

        int imported = 0;
        int skipped = 0;
        int failed = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            // Read header row and find column indices
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return "redirect:/import-goodreads?imported=0&skipped=0&failed=0";
            }
            List<String> headers = parseCsvLine(headerLine);
            int titleIndex = -1;
            int authorIndex = -1;
            int reviewIndex = -1;
            int exclusiveShelfIndex = -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).trim();
                if ("Title".equalsIgnoreCase(h)) titleIndex = i;
                else if ("Author".equalsIgnoreCase(h)) authorIndex = i;
                else if ("My Review".equalsIgnoreCase(h)) reviewIndex = i;
                else if ("Exclusive Shelf".equalsIgnoreCase(h)) exclusiveShelfIndex = i;
            }
            if (titleIndex == -1 || authorIndex == -1) {
                return "redirect:/import-goodreads?imported=0&skipped=0&failed=0";
            }

            // Build lookup set of user's existing books by title+author for dedup
            Set<String> existingBookKeys = rankingRepository.findByUserId(userId).stream()
                .map(r -> r.getBook().getTitle().toLowerCase().trim() + "\0" + r.getBook().getAuthor().toLowerCase().trim())
                .collect(Collectors.toSet());

            // Get current max positions for unranked and want-to-read rankings
            List<Ranking> existingUnranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
            int nextUnrankedPosition = existingUnranked.size();
            List<Ranking> existingWantToRead = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
            int nextWantToReadPosition = existingWantToRead.size();

            String line;
            int rowCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (++rowCount > MAX_IMPORT_ROWS) break;
                List<String> fields = parseCsvLine(line);
                if (fields.size() <= Math.max(titleIndex, Math.max(authorIndex, reviewIndex >= 0 ? reviewIndex : 0))) {
                    continue;
                }

                try {
                    String title = fields.get(titleIndex).trim();
                    int colonIndex = title.indexOf(':');
                    if (colonIndex >= 0) {
                        title = title.substring(0, colonIndex).trim();
                    }
                    // Strip Goodreads series info like "(Dune #2)" or "(Harry Potter, #3)"
                    title = title.replaceAll("\\s*\\([^)]*#\\d+\\)\\s*$", "").trim();
                    String author = fields.get(authorIndex).trim();
                    String review = reviewIndex >= 0 && reviewIndex < fields.size() ? fields.get(reviewIndex).trim() : "";
                    String exclusiveShelf = exclusiveShelfIndex >= 0 && exclusiveShelfIndex < fields.size() ? fields.get(exclusiveShelfIndex).trim() : "";
                    boolean isToRead = "to-read".equals(exclusiveShelf) || "currently-reading".equals(exclusiveShelf);
                    if (title.isEmpty() || author.isEmpty()) continue;

                    // Skip if user already has this book (by title+author, catches resolved books with changed titles)
                    String importKey = title.toLowerCase().trim() + "\0" + author.toLowerCase().trim();
                    if (existingBookKeys.contains(importKey)) {
                        skipped++;
                        continue;
                    }

                    // Create unverified book (null workOlid)
                    Book book = findOrCreateBook(null, null, title, author, null);

                    // Update title to stripped version if book was found with a subtitle
                    if (!title.equals(book.getTitle())) {
                        book.setTitle(title);
                        bookRepository.save(book);
                    }

                    // Check for duplicate by book ID
                    if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
                        skipped++;
                    } else {
                        // Trim review
                        String trimmedReview = (review != null && !review.isBlank()) ? review.trim() : null;
                        if (trimmedReview != null && trimmedReview.length() > MAX_REVIEW_LENGTH) {
                            trimmedReview = trimmedReview.substring(0, MAX_REVIEW_LENGTH);
                        }

                        Ranking newRanking;
                        if (isToRead) {
                            newRanking = new Ranking(userId, book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, nextWantToReadPosition);
                            nextWantToReadPosition++;
                        } else {
                            newRanking = new Ranking(userId, book, Bookshelf.UNRANKED, BookCategory.UNRANKED, nextUnrankedPosition);
                            nextUnrankedPosition++;
                        }
                        newRanking.setReview(trimmedReview);
                        rankingRepository.save(newRanking);
                        imported++;
                    }
                } catch (Exception e) {
                    log.error("Error importing Goodreads row {}: {}", rowCount, e.getMessage());
                    failed++;
                }
            }
        } catch (Exception e) {
            log.error("Error reading Goodreads CSV: {}", e.getMessage());
        }

        return "redirect:/my-books?selectedBookshelf=UNRANKED";
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

    @Transactional
    @PostMapping("/rank-unranked-book")
    public String rankUnrankedBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUserId().equals(userId) || ranking.getBookshelf() != Bookshelf.UNRANKED) {
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }

        // Create RankingState for categorization (no bookshelf/category set — enters CATEGORIZE mode)
        RankingState rankingState = new RankingState(userId, ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null, 0, 0, 0);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingStateRepository.save(rankingState);

        // Remove the ranking from unranked list
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);

        // Shift remaining unranked rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/rank-all")
    public String rankAll(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        return startNextUnrankedBook(userId, null);
    }

    private String startNextUnrankedBook(Long userId, Bookshelf lastRankedBookshelf) {
        List<Ranking> unrankedBooks = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
        if (unrankedBooks.isEmpty()) {
            // All done — redirect to the bookshelf of the last ranked book
            String selectedBookshelf = lastRankedBookshelf != null ? lastRankedBookshelf.name() : "FICTION";
            return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
        }

        Ranking nextBook = unrankedBooks.get(0);

        // Create RankingState for categorization
        RankingState rankingState = new RankingState(userId, nextBook.getBook().getWorkOlid(), nextBook.getBook().getTitle(), nextBook.getBook().getAuthor(), null, null, 0, 0, 0);
        rankingState.setReviewBeingRanked(nextBook.getReview());
        rankingState.setRankAll(true);
        rankingStateRepository.save(rankingState);

        // Remove from unranked list
        int removedPosition = nextBook.getPosition();
        rankingRepository.delete(nextBook);

        // Shift remaining unranked rankings to fill the gap
        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
        for (Ranking r : rankingsToShift) {
            if (r.getPosition() > removedPosition) {
                r.setPosition(r.getPosition() - 1);
                rankingRepository.save(r);
            }
        }

        return "redirect:/my-books";
    }

    @Transactional
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
        for (Bookshelf bookshelf : Bookshelf.values()) {
            for (BookCategory category : BookCategory.values()) {
                List<Ranking> guestRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                    guestUserId, bookshelf, category
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
                guestRankingState.getWorkOlidBeingRanked(),
                guestRankingState.getTitleBeingRanked(),
                guestRankingState.getAuthorBeingRanked(),
                guestRankingState.getBookshelf(),
                guestRankingState.getCategory(),
                guestRankingState.getCompareToIndex(),
                guestRankingState.getLowIndex(),
                guestRankingState.getHighIndex()
            );
            newRankingState.setReviewBeingRanked(guestRankingState.getReviewBeingRanked());
            newRankingState.setReRank(guestRankingState.isReRank());
            newRankingState.setRemove(guestRankingState.isRemove());
            newRankingState.setReview(guestRankingState.isReview());
            newRankingState.setRankAll(guestRankingState.isRankAll());
            newRankingState.setBookIdBeingReviewed(guestRankingState.getBookIdBeingReviewed());
            rankingStateRepository.save(newRankingState);
        }

        // Delete the guest user record
        User guestUser = userRepository.findById(guestUserId).orElse(null);
        if (guestUser != null && guestUser.isGuest()) {
            userRepository.delete(guestUser);
        }
    }
}
