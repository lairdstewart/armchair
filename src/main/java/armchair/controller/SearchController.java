package armchair.controller;

import armchair.controller.ControllerUtils.PaginationResult;
import armchair.dto.BookInfo;
import armchair.dto.ProfileDisplay;
import armchair.dto.ProfileDisplayWithFollow;
import armchair.dto.UserBookRank;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.CuratedList;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.recommendation.RecommendationAlgorithm;
import armchair.repository.BookRepository;
import armchair.repository.CuratedListRepository;
import armchair.repository.CuratedRankingRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import armchair.service.BookService;
import armchair.service.OpenLibraryService;
import armchair.service.RankingService;
import armchair.service.SearchService;
import armchair.service.SessionStateManager;
import armchair.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

import static armchair.controller.ControllerUtils.*;
import static armchair.service.SessionStateManager.*;

@Controller
public class SearchController extends BaseController {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private CuratedListRepository curatedListRepository;

    @Autowired
    private OpenLibraryService openLibraryService;

    @Autowired
    private BookService bookService;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private UserService userService;

    @Autowired
    private RecommendationAlgorithm recommendationAlgorithm;

    @Autowired
    private SessionStateManager sessionState;

    @SuppressWarnings("unchecked")
    @GetMapping("/search-books")
    public String showSearchBooks(@RequestParam(defaultValue = "0") int page,
                                  Model model, HttpSession session) {
        addNavigationAttributes(model, "search-books");

        List<OpenLibraryService.BookResult> searchResults =
            (List<OpenLibraryService.BookResult>) session.getAttribute(SESSION_BOOK_SEARCH_RESULTS);
        if (searchResults != null && !searchResults.isEmpty()) {
            PaginationResult<OpenLibraryService.BookResult> pagination =
                PaginationResult.of(searchResults, page, BOOK_SEARCH_PAGE_SIZE);
            model.addAttribute("searchResults", pagination.pageItems());
            model.addAttribute("booksPage", pagination.page());
            model.addAttribute("booksTotalPages", pagination.totalPages());
            model.addAttribute("booksTotalCount", pagination.totalCount());
            model.addAttribute("bookPageSize", BOOK_SEARCH_PAGE_SIZE);
        } else {
            model.addAttribute("searchResults", List.of());
        }
        model.addAttribute("query", session.getAttribute(SESSION_BOOK_SEARCH_QUERY));

        return "search-books";
    }

    @PostMapping("/search-books")
    public String searchBooks(@RequestParam String query, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        List<OpenLibraryService.BookResult> results =
            openLibraryService.searchBooks(query, BOOK_SEARCH_FETCH_LIMIT);
        session.setAttribute(SESSION_BOOK_SEARCH_RESULTS, results);
        session.setAttribute(SESSION_BOOK_SEARCH_QUERY, query);

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState != null && rankingState.getBookIdentity().getTitle() == null) {
            return "redirect:/my-books";
        }

        return "redirect:/search-books";
    }

    @GetMapping("/search")
    public String showUnifiedSearch(@RequestParam(required = false, defaultValue = "books") String type,
                                     @RequestParam(required = false) String query,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     Model model, HttpSession session) {
        addNavigationAttributes(model, "search");
        model.addAttribute("searchType", type);
        model.addAttribute("query", query);

        Long currentUserId = getCurrentUserId();
        boolean isLoggedIn = getOAuthIdentity() != null && currentUserId != null;
        User currentUser = isLoggedIn ? userRepository.findById(currentUserId).orElse(null) : null;
        boolean isRealUser = currentUser != null;

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> allRankings = currentUserId != null ? rankingService.fetchAllRankingsGrouped(currentUserId) : Map.of();
        Map<String, UserBookRank> userBooks = currentUserId != null ? rankingService.buildUserBooksMap(allRankings) : Map.of();
        List<OpenLibraryService.BookResult> bookResults;
        if ("books".equals(type) && query != null && !query.isBlank()) {
            String cachedQuery = (String) session.getAttribute(SESSION_UNIFIED_BOOK_SEARCH_QUERY);
            @SuppressWarnings("unchecked")
            List<OpenLibraryService.BookResult> cachedResults =
                (List<OpenLibraryService.BookResult>) session.getAttribute(SESSION_UNIFIED_BOOK_SEARCH_RESULTS);
            if (cachedResults != null && query.equals(cachedQuery)) {
                bookResults = cachedResults;
            } else {
                bookResults = SearchService.deduplicateResults(
                    openLibraryService.searchBooks(query, BOOK_SEARCH_FETCH_LIMIT));
                session.setAttribute(SESSION_UNIFIED_BOOK_SEARCH_RESULTS, bookResults);
                session.setAttribute(SESSION_UNIFIED_BOOK_SEARCH_QUERY, query);
            }
            PaginationResult<OpenLibraryService.BookResult> bookPagination =
                PaginationResult.of(bookResults, page, BOOK_SEARCH_PAGE_SIZE);
            model.addAttribute("bookResults", bookPagination.pageItems());
            model.addAttribute("booksPage", bookPagination.page());
            model.addAttribute("booksTotalPages", bookPagination.totalPages());
            model.addAttribute("booksTotalCount", bookPagination.totalCount());
            model.addAttribute("bookPageSize", BOOK_SEARCH_PAGE_SIZE);
        } else {
            bookResults = searchService.getRandomBooksExcluding(userBooks);
            model.addAttribute("bookResults", bookResults);
        }

        int pageSize = BOOKSHELF_PAGE_SIZE;
        if ("profiles".equals(type)) {
            List<User> allProfiles;
            if (query != null && !query.isBlank()) {
                if (isRealUser) {
                    allProfiles = userRepository.searchPublicProfilesExcluding(query.trim(), currentUserId);
                } else {
                    allProfiles = userRepository.searchPublicProfiles(query.trim());
                }
            } else {
                if (isRealUser) {
                    allProfiles = userRepository.findAllPublicProfilesExcluding(currentUserId);
                } else {
                    allProfiles = userRepository.findAllPublicProfiles();
                }
            }
            PaginationResult<User> profilePagination = PaginationResult.of(allProfiles, page, pageSize);
            model.addAttribute("profileSearchResults", profilePagination.pageItems().stream()
                .map(u -> userService.createProfileDisplayWithFollow(u, isRealUser ? currentUserId : null)).toList());
            model.addAttribute("profilesPage", profilePagination.page());
            model.addAttribute("profilesTotalPages", profilePagination.totalPages());
            model.addAttribute("profilesTotalCount", profilePagination.totalCount());
        } else {
            model.addAttribute("profileSearchResults", List.of());
        }

        if ("following".equals(type) && isRealUser) {
            List<Follow> follows = followRepository.findByFollowerIdWithFollowed(currentUserId);
            List<ProfileDisplayWithFollow> allFollowing = follows.stream()
                .map(Follow::getFollowed)
                .map(u -> userService.createProfileDisplayWithFollow(u, currentUserId))
                .toList();
            PaginationResult<ProfileDisplayWithFollow> followingPagination = PaginationResult.of(allFollowing, page, pageSize);
            model.addAttribute("followingResults", followingPagination.pageItems());
            model.addAttribute("followingPage", followingPagination.page());
            model.addAttribute("followingTotalPages", followingPagination.totalPages());
            model.addAttribute("followingTotalCount", followingPagination.totalCount());
        } else {
            model.addAttribute("followingResults", List.of());
        }

        if ("followers".equals(type) && isRealUser) {
            List<Follow> followers = followRepository.findByFollowedIdWithFollower(currentUserId);
            List<ProfileDisplayWithFollow> allFollowers = followers.stream()
                .map(Follow::getFollower)
                .map(u -> userService.createProfileDisplayWithFollow(u, currentUserId))
                .toList();
            PaginationResult<ProfileDisplayWithFollow> followersPagination = PaginationResult.of(allFollowers, page, pageSize);
            model.addAttribute("followerResults", followersPagination.pageItems());
            model.addAttribute("followersPage", followersPagination.page());
            model.addAttribute("followersTotalPages", followersPagination.totalPages());
            model.addAttribute("followersTotalCount", followersPagination.totalCount());
        } else {
            model.addAttribute("followerResults", List.of());
        }

        if ("curated".equals(type)) {
            List<CuratedList> allCurated;
            if (query != null && !query.isBlank()) {
                allCurated = curatedListRepository.searchByUsername(query.trim());
            } else {
                allCurated = curatedListRepository.findAll();
            }
            PaginationResult<CuratedList> curatedPagination = PaginationResult.of(allCurated, page, pageSize);
            model.addAttribute("curatedResults", curatedPagination.pageItems());
            model.addAttribute("curatedPage", curatedPagination.page());
            model.addAttribute("curatedTotalPages", curatedPagination.totalPages());
            model.addAttribute("curatedTotalCount", curatedPagination.totalCount());
        } else {
            model.addAttribute("curatedResults", List.of());
        }

        model.addAttribute("canFollow", isRealUser);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("userBooks", userBooks);

        return "search";
    }

    @GetMapping("/search-profiles")
    public String showExplore(@RequestParam(required = false) String query, Model model, HttpSession session) {
        addNavigationAttributes(model, "search");
        model.addAttribute("query", query);

        if (query != null && !query.isBlank()) {
            List<User> results = userRepository.searchPublicProfiles(query.trim());
            List<ProfileDisplay> profileDisplays = results.stream()
                .map(userService::createProfileDisplay)
                .toList();
            model.addAttribute("searchResults", profileDisplays);
        } else {
            List<User> recentProfiles = userRepository.findRecentPublicProfiles();
            List<ProfileDisplay> profileDisplays = recentProfiles.stream()
                .map(userService::createProfileDisplay)
                .toList();
            long totalProfiles = userRepository.count();
            long moreCount = Math.max(0, totalProfiles - recentProfiles.size());
            model.addAttribute("recentProfiles", profileDisplays);
            model.addAttribute("moreProfilesCount", moreCount);
        }

        return "explore";
    }

    private static final List<BookCategory> RANKED_CATEGORIES = List.of(BookCategory.LIKED, BookCategory.OK, BookCategory.DISLIKED);

    @GetMapping("/recs")
    public String showRecs(Model model, HttpSession session) {
        if (getOAuthIdentity() == null) {
            return "redirect:/login";
        }
        Long userId = getCurrentUserId();
        addNavigationAttributes(model, "recs");

        long fictionRankedCount = rankingRepository.countByUserIdAndBookshelfAndCategoryIn(userId, Bookshelf.FICTION, RANKED_CATEGORIES);
        long nonfictionRankedCount = rankingRepository.countByUserIdAndBookshelfAndCategoryIn(userId, Bookshelf.NONFICTION, RANKED_CATEGORIES);

        List<BookInfo> fictionRecs = List.of();
        if (fictionRankedCount >= MIN_RANKED_BOOKS_FOR_RECS) {
            fictionRecs = recommendationAlgorithm.getFictionRecommendations(userId, RECOMMENDATIONS_LIMIT).stream()
                    .map(b -> new BookInfo(b.getId(), b.getWorkOlid(), b.getEditionOlid(),
                            b.getTitle(), b.getAuthor(), null, b.getFirstPublishYear(), b.getCoverId()))
                    .toList();
        }
        List<BookInfo> nonfictionRecs = List.of();
        if (nonfictionRankedCount >= MIN_RANKED_BOOKS_FOR_RECS) {
            nonfictionRecs = recommendationAlgorithm.getNonfictionRecommendations(userId, RECOMMENDATIONS_LIMIT).stream()
                    .map(b -> new BookInfo(b.getId(), b.getWorkOlid(), b.getEditionOlid(),
                            b.getTitle(), b.getAuthor(), null, b.getFirstPublishYear(), b.getCoverId()))
                    .toList();
        }
        model.addAttribute("fictionRecs", fictionRecs);
        model.addAttribute("nonfictionRecs", nonfictionRecs);
        model.addAttribute("fictionRankedCount", fictionRankedCount);
        model.addAttribute("nonfictionRankedCount", nonfictionRankedCount);
        model.addAttribute("minRankedBooks", MIN_RANKED_BOOKS_FOR_RECS);

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> allRankings = rankingService.fetchAllRankingsGrouped(userId);
        Map<String, UserBookRank> userBooks = rankingService.buildUserBooksMap(allRankings);
        model.addAttribute("userBooks", userBooks);

        return "recs";
    }

    @GetMapping("/editions/{workOlid}")
    public String showEditions(@PathVariable String workOlid,
                               @RequestParam(required = false) String title,
                               @RequestParam(required = false) String author,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false) Integer coverId,
                               @RequestParam(required = false) String query,
                               Model model, HttpSession session) {
        addNavigationAttributes(model, "search");

        @SuppressWarnings("unchecked")
        List<OpenLibraryService.EditionResult> allEditions =
            (List<OpenLibraryService.EditionResult>) session.getAttribute(SESSION_BROWSE_EDITIONS_PREFIX + workOlid);

        if (allEditions == null) {
            allEditions = openLibraryService.getEditionsForWork(workOlid, OpenLibraryService.DEFAULT_EDITION_FETCH_LIMIT, 0);
            if (coverId != null) {
                allEditions = ControllerUtils.moveMatchingToFront(allEditions, e -> coverId.equals(e.coverId()));
            }
            session.setAttribute(SESSION_BROWSE_EDITIONS_PREFIX + workOlid, allEditions);
        }

        PaginationResult<OpenLibraryService.EditionResult> editionPagination = PaginationResult.of(allEditions, page, EDITION_PAGE_SIZE);

        model.addAttribute("workOlid", workOlid);
        model.addAttribute("workTitle", title);
        model.addAttribute("workAuthor", author);
        model.addAttribute("editionResults", editionPagination.pageItems());
        model.addAttribute(SESSION_EDITION_PAGE, editionPagination.page());
        model.addAttribute("editionTotalPages", editionPagination.totalPages());
        model.addAttribute("editionTotalCount", editionPagination.totalCount());
        model.addAttribute("editionPageSize", EDITION_PAGE_SIZE);
        model.addAttribute("searchQuery", query);

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            var allRankings = rankingService.fetchAllRankingsGrouped(currentUserId);
            var userBooks = rankingService.buildUserBooksMap(allRankings);
            model.addAttribute("userBooks", userBooks);
            UserBookRank existingBook = userBooks.get(workOlid);
            if (existingBook != null) {
                model.addAttribute("existingBookshelf", existingBook.bookshelf().toUpperCase());
            }
        }

        return "editions";
    }

    @PostMapping("/select-book")
    public String selectBook(@RequestParam String workOlid,
                             @RequestParam String bookName,
                             @RequestParam String author,
                             @RequestParam(required = false) String editionOlid,
                             @RequestParam(required = false) Integer firstPublishYear,
                             @RequestParam(required = false) Integer coverId,
                             HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        if (rankingRepository.existsByUserIdAndBookWorkOlid(userId, workOlid)) {
            return "redirect:/my-books";
        }
        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null) {
            rankingState = new RankingState(null, null, null, null, null);
        }

        bookService.findOrCreateBook(workOlid, editionOlid, bookName, author, firstPublishYear, coverId);

        rankingState.getBookIdentity().setBookInfo(workOlid, bookName, author);
        rankingState.setMode(RankingMode.SELECT_EDITION);
        sessionState.saveRankingState(session, rankingState);

        sessionState.clearEditionCache(session);

        session.setAttribute(SESSION_EDITION_SELECTION_SOURCE, EDITION_SOURCE_SEARCH);
        return "redirect:/rank/edition";
    }

    @PostMapping("/select-book-edition")
    public String selectBookEdition(@RequestParam String workOlid,
                                    @RequestParam String bookName,
                                    @RequestParam String author,
                                    @RequestParam(required = false) String editionOlid,
                                    @RequestParam(required = false) String isbn13,
                                    @RequestParam(required = false) Integer coverId,
                                    @RequestParam(required = false) String editionTitle,
                                    HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        if (rankingRepository.existsByUserIdAndBookWorkOlid(userId, workOlid)) {
            return "redirect:/my-books";
        }
        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));

        String titleToUse = (editionTitle != null && !editionTitle.isBlank()) ? editionTitle : bookName;

        Book book = bookService.findOrCreateBook(workOlid, editionOlid, titleToUse, author, null, coverId);
        if (editionOlid != null) {
            book.setEditionOlid(editionOlid);
        }
        if (isbn13 != null) {
            book.setIsbn13(isbn13);
        }
        if (coverId != null) {
            book.setCoverId(coverId);
        }
        if (editionTitle != null && !editionTitle.isBlank()) {
            book.setTitle(editionTitle);
        }
        bookRepository.save(book);

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null) {
            rankingState = new RankingState(null, null, null, null, null);
        }
        rankingState.getBookIdentity().setBookInfo(workOlid, titleToUse, author);
        rankingState.getEditionSelection().setEditionOlid(editionOlid);
        rankingState.getEditionSelection().setIsbn13(isbn13);
        rankingState.getEditionSelection().setEditionSelected(true);
        rankingState.setMode(RankingMode.CATEGORIZE);
        sessionState.saveRankingState(session, rankingState);

        return "redirect:/rank/categorize";
    }

    @PostMapping("/add-to-reading-list")
    public String addToReadingList(@RequestParam String workOlid,
                                   @RequestParam String bookName,
                                   @RequestParam String author,
                                   @RequestParam(required = false) String editionOlid,
                                   @RequestParam(required = false) Integer firstPublishYear,
                                   @RequestParam(required = false) Integer coverId,
                                   @RequestParam(required = false) String returnUrl,
                                   HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            String redirect = isSafeRedirectUrl(returnUrl) ? returnUrl : "/";
            session.setAttribute("POST_LOGIN_REDIRECT", redirect);
            return "redirect:/login";
        }

        String redirectTo = isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=books";

        Book book = bookService.findOrCreateBook(workOlid, editionOlid, bookName, author, firstPublishYear, coverId);

        if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
            return redirectTo;
        }

        rankingService.createWantToReadRanking(userId, book);

        return redirectTo;
    }
}
