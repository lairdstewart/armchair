package armchair.controller;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookType;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Controller
public class BookController {
    public record BookInfo(Long id, String googleBooksId, String title, String author) {}
    public record BookLists(List<BookInfo> liked, List<BookInfo> ok, List<BookInfo> disliked) {}
    public record ProfileDisplay(String username, String stats) {}

    private enum Mode {
        LIST,
        ADD,
        CATEGORIZE,
        RANK,
        RE_RANK,
        REMOVE;
    }

    private static final String SESSION_GUEST_USER_ID = "guestUserId";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private RankingStateRepository rankingStateRepository;

    @Autowired
    private GoogleBooksService googleBooksService;

    @PostConstruct
    public void init() {
        // Clean up ALL guest users on startup
        // Assumption: On Render, the VM goes to sleep after 15 minutes of inactivity.
        // When the app restarts, all guests are from a previous session and can be safely deleted.
        List<User> guests = userRepository.findByIsGuest(true);

        for (User guest : guests) {
            // Delete associated data
            for (BookType type : BookType.values()) {
                for (BookCategory category : BookCategory.values()) {
                    bookRepository.deleteAll(bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(guest.getId(), type, category));
                }
            }
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
    public String showPage(Model model, HttpSession session) {
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
        boolean hasAnyBooks = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty()
            || !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty();

        model.addAttribute("fictionBooks", fictionBooks);
        model.addAttribute("nonfictionBooks", nonfictionBooks);
        model.addAttribute("hasAnyBooks", hasAnyBooks);
        model.addAttribute("rankingState", rankingState);

        // For RE_RANK mode, show LIST view but with rerank type set
        if (mode == Mode.RE_RANK) {
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", rankingState.getType().name());
            model.addAttribute("removeType", null);
        } else if (mode == Mode.REMOVE) {
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", rankingState.getType().name());
        } else {
            model.addAttribute("mode", mode);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
        }

        // Add search results if in ADD mode
        if (mode == Mode.ADD && rankingState != null) {
            List<GoogleBooksService.BookResult> searchResults =
                (List<GoogleBooksService.BookResult>) session.getAttribute("bookSearchResults");
            model.addAttribute("searchResults", searchResults != null ? searchResults : List.of());
        }

        if (rankingState != null && mode == Mode.RANK && rankingState.getTitleBeingRanked() != null) {
            List<Book> currentList = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                userId, rankingState.getType(), rankingState.getCategory()
            );
            model.addAttribute("comparisonBook", currentList.get(rankingState.getCompareToIndex()).getTitle());
        }

        return "index";
    }

    private Mode determineMode(RankingState rankingState) {
        if (rankingState == null) {
            return Mode.LIST;
        }
        if (rankingState.getTitleBeingRanked() == null) {
            if (rankingState.isReRank()) {
                return Mode.RE_RANK;
            }
            if (rankingState.isRemove()) {
                return Mode.REMOVE;
            }
            return Mode.ADD;
        }
        if (rankingState.getCategory() == null) {
            return Mode.CATEGORIZE;
        }
        return Mode.RANK;
    }

    private BookLists getBookLists(BookType type, Long userId) {
        List<BookInfo> liked = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.LIKED)
            .stream().map(b -> new BookInfo(b.getId(), b.getGoogleBooksId(), b.getTitle(), b.getAuthor())).toList();
        List<BookInfo> ok = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.OK)
            .stream().map(b -> new BookInfo(b.getId(), b.getGoogleBooksId(), b.getTitle(), b.getAuthor())).toList();
        List<BookInfo> disliked = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, BookCategory.DISLIKED)
            .stream().map(b -> new BookInfo(b.getId(), b.getGoogleBooksId(), b.getTitle(), b.getAuthor())).toList();
        return new BookLists(liked, ok, disliked);
    }

    private String generateCsv(Long userId) {
        StringBuilder csv = new StringBuilder();
        csv.append("Rank,Title,Author,Category,Type\n");

        int rank = 1;

        // Iterate through all types and categories
        for (BookType type : BookType.values()) {
            for (BookCategory category : BookCategory.values()) {
                List<Book> books = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                    userId, type, category
                );
                for (Book book : books) {
                    csv.append(rank++).append(",");
                    csv.append("\"").append(escapeCsv(book.getTitle())).append("\",");
                    csv.append("\"").append(escapeCsv(book.getAuthor())).append("\",");
                    csv.append("\"").append(category.getValue()).append("\",");
                    csv.append("\"").append(type.getValue()).append("\"\n");
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

        List<Book> currentList = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
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
            insertBookAtPosition(rankingState.getGoogleBooksIdBeingRanked(), rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(),
                rankingState.getType(), rankingState.getCategory(), newLowIndex, userId);
            rankingStateRepository.deleteById(userId);
            // Clear search results
            session.removeAttribute("bookSearchResults");
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

    private void insertBookAtPosition(String googleBooksId, String title, String author, BookType type, BookCategory category, int position, Long userId) {
        // Shift all books at or after the insertion position
        List<Book> booksToShift = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, type, category
        );
        for (int i = booksToShift.size() - 1; i >= position; i--) {
            Book book = booksToShift.get(i);
            book.setPosition(book.getPosition() + 1);
            bookRepository.save(book);
        }

        // Insert the new book
        Book newBook = new Book(userId, googleBooksId, title, author, type, category, position);
        bookRepository.save(newBook);
    }

    @PostMapping("/start-add")
    public String startAdd(@RequestParam String type, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        BookType bookType = BookType.fromString(type);
        RankingState rankingState = new RankingState(userId, null, null, null, bookType, null, 0, 0, 0);
        rankingStateRepository.save(rankingState);
        // Clear any previous search results
        session.removeAttribute("bookSearchResults");
        return "redirect:/my-books";
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

        // Find the book and verify it belongs to this user
        Book book = bookRepository.findById(bookId).orElse(null);
        if (book == null || !book.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Store book info in ranking state
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isReRank()) {
            return "redirect:/my-books";
        }

        rankingState.setGoogleBooksIdBeingRanked(book.getGoogleBooksId());
        rankingState.setTitleBeingRanked(book.getTitle());
        rankingState.setAuthorBeingRanked(book.getAuthor());
        rankingStateRepository.save(rankingState);

        // Remove the book from its current position
        BookType type = book.getType();
        BookCategory category = book.getCategory();
        int removedPosition = book.getPosition();
        bookRepository.delete(book);

        // Shift remaining books in that category to fill the gap
        List<Book> booksToShift = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, type, category
        );
        for (Book b : booksToShift) {
            if (b.getPosition() > removedPosition) {
                b.setPosition(b.getPosition() - 1);
                bookRepository.save(b);
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

    @PostMapping("/select-remove-book")
    public String selectRemoveBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        // Find the book and verify it belongs to this user
        Book book = bookRepository.findById(bookId).orElse(null);
        if (book == null || !book.getUserId().equals(userId)) {
            return "redirect:/my-books";
        }

        // Verify we're in remove mode
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null || !rankingState.isRemove()) {
            return "redirect:/my-books";
        }

        // Remove the book
        BookType type = book.getType();
        BookCategory category = book.getCategory();
        int removedPosition = book.getPosition();
        bookRepository.delete(book);

        // Shift remaining books in that category to fill the gap
        List<Book> booksToShift = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, type, category
        );
        for (Book b : booksToShift) {
            if (b.getPosition() > removedPosition) {
                b.setPosition(b.getPosition() - 1);
                bookRepository.save(b);
            }
        }

        // Clear the ranking state
        rankingStateRepository.deleteById(userId);

        return "redirect:/my-books";
    }

    @PostMapping("/search-books")
    public String searchBooks(@RequestParam String query, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        List<GoogleBooksService.BookResult> results = googleBooksService.searchBooks(query);
        session.setAttribute("bookSearchResults", results);
        return "redirect:/my-books";
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
                             HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = rankingStateRepository.findById(userId).orElse(null);
        if (rankingState == null) {
            return "redirect:/my-books";
        }

        // Set the book info, leave category null to enter CATEGORIZE mode
        rankingState.setGoogleBooksIdBeingRanked(googleBooksId);
        rankingState.setTitleBeingRanked(bookName);
        rankingState.setAuthorBeingRanked(author);
        rankingStateRepository.save(rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/categorize")
    public String categorizeBook(@RequestParam String category,
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

        BookCategory bookCategory = BookCategory.fromString(category);
        List<Book> currentList = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
            userId, rankingState.getType(), bookCategory
        );

        if (currentList.isEmpty()) {
            // Category is empty, insert at the start
            Book newBook = new Book(userId, googleBooksId, bookName, author, rankingState.getType(), bookCategory, 0);
            bookRepository.save(newBook);
            rankingStateRepository.deleteById(userId);
            // Clear search results
            session.removeAttribute("bookSearchResults");
        } else {
            // Start binary search within this category
            int lowIndex = 0;
            int highIndex = currentList.size() - 1;
            int compareToIndex = (lowIndex + highIndex) / 2;
            rankingState.setGoogleBooksIdBeingRanked(googleBooksId);
            rankingState.setTitleBeingRanked(bookName);
            rankingState.setAuthorBeingRanked(author);
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

    @GetMapping("/explore-profiles")
    public String showExplore(@RequestParam(required = false) String query, Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "explore");
        model.addAttribute("query", query);

        if (query != null && !query.isBlank()) {
            List<User> results = userRepository.findByIsGuestFalseAndIsCuratedFalseAndUsernameContainingIgnoreCase(query.trim());
            List<ProfileDisplay> profileDisplays = results.stream()
                .map(this::createProfileDisplay)
                .toList();
            model.addAttribute("searchResults", profileDisplays);
        } else {
            // Show recent profiles when not searching (excluding curated lists)
            List<User> recentProfiles = userRepository.findTop10ByIsGuestFalseAndIsCuratedFalseOrderBySignupDateDesc();
            List<ProfileDisplay> profileDisplays = recentProfiles.stream()
                .map(this::createProfileDisplay)
                .toList();
            long totalProfiles = userRepository.countByIsGuestFalseAndIsCuratedFalse();
            long moreCount = Math.max(0, totalProfiles - recentProfiles.size());
            model.addAttribute("recentProfiles", profileDisplays);
            model.addAttribute("moreProfilesCount", moreCount);
        }

        return "explore";
    }

    private ProfileDisplay createProfileDisplay(User user) {
        long fictionCount = 0;
        long nonfictionCount = 0;
        for (BookCategory category : BookCategory.values()) {
            fictionCount += bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                user.getId(), BookType.FICTION, category).size();
            nonfictionCount += bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                user.getId(), BookType.NONFICTION, category).size();
        }

        String stats = String.format("%d fiction, %d non-fiction, user %d",
            fictionCount, nonfictionCount,
            user.getSignupNumber() != null ? user.getSignupNumber() : 0);

        return new ProfileDisplay(user.getUsername(), stats);
    }

    @GetMapping("/curated-lists")
    public String showCurated(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "curated");
        List<User> curatedUsers = userRepository.findByIsCurated(true);
        model.addAttribute("curatedUsers", curatedUsers);
        return "curated";
    }

    @GetMapping("/user/{username}")
    public String viewUser(@PathVariable String username, Model model, HttpSession session) {
        getCurrentUserId(session);

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return "redirect:/";
        }

        // Keep the appropriate nav button selected based on user type
        addNavigationAttributes(model, user.isCurated() ? "curated" : "explore");

        BookLists fictionBooks = getBookLists(BookType.FICTION, user.getId());
        BookLists nonfictionBooks = getBookLists(BookType.NONFICTION, user.getId());

        model.addAttribute("viewUsername", user.getUsername());
        model.addAttribute("fictionBooks", fictionBooks);
        model.addAttribute("nonfictionBooks", nonfictionBooks);
        model.addAttribute("isCurated", user.isCurated());

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
            fictionCount += bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.FICTION, category).size();
            nonfictionCount += bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, BookType.NONFICTION, category).size();
        }

        model.addAttribute("username", user.getUsername());
        model.addAttribute("signupDate", user.getSignupDate());
        model.addAttribute("signupNumber", user.getSignupNumber());
        model.addAttribute("fictionCount", fictionCount);
        model.addAttribute("nonfictionCount", nonfictionCount);
        model.addAttribute("hasAnyBooks", fictionCount + nonfictionCount > 0);

        return "profile";
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

        // Validate username
        if (username == null || username.isBlank()) {
            model.addAttribute("error", "Username cannot be empty");
            return "setup-username";
        }

        username = username.trim();

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

        // Delete all user's books
        for (BookType type : BookType.values()) {
            for (BookCategory category : BookCategory.values()) {
                bookRepository.deleteAll(bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(userId, type, category));
            }
        }

        // Delete ranking state if exists
        rankingStateRepository.deleteById(userId);

        // Delete the user record
        userRepository.delete(user);

        // Invalidate the session
        session.invalidate();

        // Redirect to logout to clear OAuth session
        return "redirect:/logout";
    }

    private void migrateGuestDataToUser(Long guestUserId, Long newUserId) {
        // Migrate all books from guest to new user
        for (BookType type : BookType.values()) {
            for (BookCategory category : BookCategory.values()) {
                List<Book> guestBooks = bookRepository.findByUserIdAndTypeAndCategoryOrderByPositionAsc(
                    guestUserId, type, category
                );
                for (Book book : guestBooks) {
                    book.setUserId(newUserId);
                    bookRepository.save(book);
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
            rankingStateRepository.save(newRankingState);
        }

        // Delete the guest user record
        User guestUser = userRepository.findById(guestUserId).orElse(null);
        if (guestUser != null && guestUser.isGuest()) {
            userRepository.delete(guestUser);
        }
    }
}
