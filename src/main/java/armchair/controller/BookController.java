package armchair.controller;

import armchair.dto.BookInfo;
import armchair.dto.BookLists;
import armchair.dto.ProfileDisplay;
import armchair.dto.ProfileDisplayWithFollow;
import armchair.dto.UserBookRank;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import armchair.recommendation.RecommendationAlgorithm;
import armchair.service.BookService;
import armchair.service.ImportExportService;
import armchair.service.OpenLibraryService;
import armchair.service.RankingService;
import armchair.service.SearchService;
import armchair.service.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
public class BookController {
    private static final Logger log = LoggerFactory.getLogger(BookController.class);

    private enum Mode {
        LIST,
        SELECT_EDITION,
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
    private static final String SESSION_BOOK_SEARCH_RESULTS = "bookSearchResults";
    private static final String SESSION_BOOK_SEARCH_QUERY = "bookSearchQuery";
    private static final String SESSION_SKIP_RESOLVE = "skipResolve";
    private static final String SESSION_CACHED_EDITIONS = "cachedEditions";
    private static final String SESSION_EDITION_PAGE = "editionPage";
    private static final String SESSION_RESOLVE_WARNING = "resolveWarning";
    private static final String SESSION_DUPLICATE_RESOLVE_TITLE = "duplicateResolveTitle";
    private static final String SESSION_DUPLICATE_RESOLVE_WORK_OLID = "duplicateResolveWorkOlid";
    private static final String SESSION_DUPLICATE_RESOLVE_BOOK_ID = "duplicateResolveBookId";
    private static final String SESSION_EDITION_SELECTION_SOURCE = "editionSelectionSource";
    private static final String SESSION_BROWSE_EDITIONS_PREFIX = "browseEditions_";
    private static final String SESSION_RANKING_STATE = "rankingState";

    private static final String SKIP_RESOLVE_EXPANDED = "expanded";
    private static final String SKIP_RESOLVE_MANUAL = "manual";
    private static final String EDITION_SOURCE_RESOLVE = "RESOLVE";
    private static final String EDITION_SOURCE_SEARCH = "SEARCH";

    private static final int EDITION_PAGE_SIZE = 5;
    private static final int SEARCH_RESULTS_DEFAULT = 3;
    private static final int SEARCH_RESULTS_EXPANDED = 10;
    private static final int MANUAL_SEARCH_RESULTS = 10;
    private static final int BOOKSHELF_PAGE_SIZE = 10;
    private static final int RECOMMENDATIONS_LIMIT = 10;

    private record PaginationResult<T>(List<T> pageItems, int page, int totalPages, int totalCount) {
        static <T> PaginationResult<T> of(List<T> allItems, int page, int pageSize) {
            int total = allItems.size();
            int pages = Math.max(1, (total + pageSize - 1) / pageSize);
            int safePage = Math.max(0, Math.min(page, pages - 1));
            int start = safePage * pageSize;
            int end = Math.min(start + pageSize, total);
            List<T> items = start < total ? allItems.subList(start, end) : List.of();
            return new PaginationResult<>(items, safePage, pages, total);
        }
    }

    private static <T> List<T> moveMatchingToFront(List<T> items, java.util.function.Predicate<T> matcher) {
        for (int i = 1; i < items.size(); i++) {
            if (matcher.test(items.get(i))) {
                List<T> reordered = new ArrayList<>(items);
                T match = reordered.remove(i);
                reordered.add(0, match);
                return reordered;
            }
        }
        return items;
    }

    private static String trimReview(String review) {
        if (review == null || review.isBlank()) return null;
        String trimmed = review.trim();
        return trimmed.length() > ImportExportService.MAX_REVIEW_LENGTH
            ? trimmed.substring(0, ImportExportService.MAX_REVIEW_LENGTH) : trimmed;
    }

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
    private FollowRepository followRepository;

    @Autowired
    private OpenLibraryService openLibraryService;

    @Autowired
    private BookService bookService;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private UserService userService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ImportExportService importExportService;

    @Autowired
    private RecommendationAlgorithm recommendationAlgorithm;

    private static void clearDuplicateResolveSession(HttpSession session) {
        session.removeAttribute(SESSION_DUPLICATE_RESOLVE_TITLE);
        session.removeAttribute(SESSION_DUPLICATE_RESOLVE_WORK_OLID);
        session.removeAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID);
    }

    private static RankingState getRankingState(HttpSession session) {
        return (RankingState) session.getAttribute(SESSION_RANKING_STATE);
    }

    private static void saveRankingState(HttpSession session, RankingState state) {
        session.setAttribute(SESSION_RANKING_STATE, state);
    }

    private static void clearRankingState(HttpSession session) {
        session.removeAttribute(SESSION_RANKING_STATE);
    }

    @PostConstruct
    public void init() {
        userService.cleanupGuests();
    }

    record OAuthIdentity(String subject, String provider) {}

    private OAuthIdentity getOAuthIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            OAuth2User oauth2User = oauthToken.getPrincipal();
            String provider = oauthToken.getAuthorizedClientRegistrationId();
            String subject;
            if ("github".equals(provider)) {
                Object id = oauth2User.getAttribute("id");
                subject = id != null ? id.toString() : null;
            } else {
                subject = oauth2User.getAttribute("sub");
            }
            if (subject != null) {
                return new OAuthIdentity(subject, provider);
            }
        }
        return null;
    }

    private void addNavigationAttributes(Model model, String currentPage) {
        boolean isLoggedIn = getOAuthIdentity() != null;
        model.addAttribute("isLoggedIn", isLoggedIn);
        model.addAttribute("currentPage", currentPage);
    }

    private Long getCurrentUserId(HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();

        if (identity != null) {
            User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
            if (user != null) {
                Long guestUserId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
                if (guestUserId != null && !guestUserId.equals(user.getId())) {
                    userService.migrateGuestDataToUser(guestUserId, user.getId());
                    session.removeAttribute(SESSION_GUEST_USER_ID);
                }
                return user.getId();
            }
        }

        Long guestUserId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
        if (guestUserId != null) {
            User guest = userRepository.findById(guestUserId).orElse(null);
            if (guest != null) {
                return guestUserId;
            }
        }

        User newGuest = new User("guest-" + java.util.UUID.randomUUID());
        newGuest.setGuest(true);
        userRepository.save(newGuest);
        session.setAttribute(SESSION_GUEST_USER_ID, newGuest.getId());
        return newGuest.getId();
    }

    @GetMapping("/")
    public String showWelcome(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "about");
        return "welcome";
    }

    @GetMapping("/login")
    public String showLogin(Model model) {
        addNavigationAttributes(model, "login");
        return "login";
    }

    @PostMapping("/my-books")
    public String goToMyBooks(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId != null) {
            rankingService.restoreAbandonedBook(userId, getRankingState(session));
            clearRankingState(session);
            session.removeAttribute(SESSION_BOOK_SEARCH_RESULTS);
            session.removeAttribute(SESSION_SKIP_RESOLVE);
            clearDuplicateResolveSession(session);
        }
        return "redirect:/my-books";
    }

    @GetMapping("/my-books")
    public String showPage(Model model, HttpSession session, @RequestParam(required = false) String selectedBookshelf, @RequestParam(required = false) String resolveQuery) {
        Long userId = getCurrentUserId(session);

        addNavigationAttributes(model, "list");

        String resolveWarning = (String) session.getAttribute(SESSION_RESOLVE_WARNING);
        if (resolveWarning != null) {
            model.addAttribute(SESSION_RESOLVE_WARNING, resolveWarning);
            session.removeAttribute(SESSION_RESOLVE_WARNING);
        }

        RankingState rankingState = getRankingState(session);

        Mode mode = determineMode(rankingState);

        String duplicateResolveTitle = (String) session.getAttribute(SESSION_DUPLICATE_RESOLVE_TITLE);
        if (duplicateResolveTitle != null) {
            mode = Mode.DUPLICATE_RESOLVE;
            model.addAttribute(SESSION_DUPLICATE_RESOLVE_TITLE, duplicateResolveTitle);
        }

        boolean needsResolve = mode == Mode.RESOLVE ||
            (mode == Mode.CATEGORIZE && rankingState != null && rankingState.getWorkOlidBeingRanked() == null);
        if (needsResolve && rankingState != null) {
            Object skipResolve = session.getAttribute(SESSION_SKIP_RESOLVE);
            if (SKIP_RESOLVE_MANUAL.equals(skipResolve)) {
                mode = Mode.MANUAL_RESOLVE;
                if (resolveQuery != null && !resolveQuery.isBlank()) {
                    List<OpenLibraryService.BookResult> resolveResults =
                        SearchService.deduplicateResults(openLibraryService.searchBooks(resolveQuery, MANUAL_SEARCH_RESULTS));
                    model.addAttribute("resolveResults", resolveResults);
                    model.addAttribute("resolveQuery", resolveQuery);
                    if (resolveResults.isEmpty()) {
                        model.addAttribute("resolveNoResults", true);
                    }
                }
            } else if (skipResolve == null || SKIP_RESOLVE_EXPANDED.equals(skipResolve)) {
                int maxResults = SKIP_RESOLVE_EXPANDED.equals(skipResolve) ? SEARCH_RESULTS_EXPANDED : SEARCH_RESULTS_DEFAULT;
                List<OpenLibraryService.BookResult> resolveResults = searchService.combinedSearch(
                        rankingState.getTitleBeingRanked(),
                        rankingState.getAuthorBeingRanked(),
                        maxResults);
                if (!resolveResults.isEmpty()) {
                    mode = Mode.RESOLVE;
                    model.addAttribute("resolveResults", resolveResults);
                    if (skipResolve == null && resolveResults.size() < maxResults) {
                        session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_EXPANDED);
                    }
                } else if (skipResolve == null) {
                    resolveResults = searchService.combinedSearch(
                        rankingState.getTitleBeingRanked(),
                        rankingState.getAuthorBeingRanked(),
                        SEARCH_RESULTS_EXPANDED);
                    if (!resolveResults.isEmpty()) {
                        mode = Mode.RESOLVE;
                        model.addAttribute("resolveResults", resolveResults);
                    } else {
                        log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked());
                        session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_MANUAL);
                        return "redirect:/my-books";
                    }
                } else {
                    log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked());
                    session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_MANUAL);
                    return "redirect:/my-books";
                }
            }
        }

        if (mode == Mode.SELECT_EDITION && rankingState != null && rankingState.getWorkOlidBeingRanked() != null) {
            @SuppressWarnings("unchecked")
            List<OpenLibraryService.EditionResult> allEditions =
                (List<OpenLibraryService.EditionResult>) session.getAttribute(SESSION_CACHED_EDITIONS);

            if (allEditions == null) {
                Book editionBook = bookRepository.findByWorkOlid(rankingState.getWorkOlidBeingRanked()).orElse(null);
                String preferredEditionOlid = editionBook != null ? editionBook.getEditionOlid() : null;

                allEditions = openLibraryService.getEditionsForWork(
                    rankingState.getWorkOlidBeingRanked(), OpenLibraryService.DEFAULT_EDITION_FETCH_LIMIT, 0, preferredEditionOlid);
                Integer editionCoverId = editionBook != null ? editionBook.getCoverId() : null;
                if (editionCoverId != null && !allEditions.isEmpty() && !editionCoverId.equals(allEditions.get(0).coverId())) {
                    allEditions = moveMatchingToFront(allEditions, e -> editionCoverId.equals(e.coverId()));
                }
                session.setAttribute(SESSION_CACHED_EDITIONS, allEditions);
            }

            if (allEditions.size() == 1) {
                OpenLibraryService.EditionResult soleEdition = allEditions.get(0);
                rankingState.setEditionOlidBeingRanked(soleEdition.editionOlid());
                rankingState.setIsbn13BeingRanked(soleEdition.isbn13());
                rankingState.setEditionSelected(true);
                rankingState.setMode(RankingMode.CATEGORIZE);
                saveRankingState(session, rankingState);

                Book book = bookService.findOrCreateBook(rankingState.getWorkOlidBeingRanked(),
                    soleEdition.editionOlid(), rankingState.getTitleBeingRanked(),
                    rankingState.getAuthorBeingRanked(), null, null);
                book.setIsbn13(soleEdition.isbn13());
                bookRepository.save(book);

                session.removeAttribute(SESSION_CACHED_EDITIONS);
                return "redirect:/my-books";
            }

            if (allEditions.isEmpty()) {
                rankingState.setEditionSelected(true);
                rankingState.setMode(RankingMode.CATEGORIZE);
                saveRankingState(session, rankingState);
                session.removeAttribute(SESSION_CACHED_EDITIONS);
                return "redirect:/my-books";
            }

            Integer editionPage = (Integer) session.getAttribute(SESSION_EDITION_PAGE);
            if (editionPage == null) editionPage = 0;

            PaginationResult<OpenLibraryService.EditionResult> editionPagination = PaginationResult.of(allEditions, editionPage, EDITION_PAGE_SIZE);

            model.addAttribute("editionResults", editionPagination.pageItems());
            model.addAttribute(SESSION_EDITION_PAGE, editionPagination.page());
            model.addAttribute("editionTotalPages", editionPagination.totalPages());
            model.addAttribute("editionTotalCount", editionPagination.totalCount());
            model.addAttribute("editionPageSize", EDITION_PAGE_SIZE);
        }

        String userName = null;
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && !user.isGuest()) {
            userName = user.getUsername();
        }
        model.addAttribute("userName", userName);

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> allRankings = rankingService.fetchAllRankingsGrouped(userId);
        BookLists fictionBooks = rankingService.getBookLists(Bookshelf.FICTION, allRankings);
        BookLists nonfictionBooks = rankingService.getBookLists(Bookshelf.NONFICTION, allRankings);
        List<BookInfo> wantToReadBooks = rankingService.getRankings(allRankings, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED)
            .stream().map(RankingService::toBookInfo).toList();
        List<BookInfo> unrankedBooks = rankingService.getRankings(allRankings, Bookshelf.UNRANKED, BookCategory.UNRANKED)
            .stream().map(RankingService::toBookInfo).toList();
        boolean hasFiction = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty();
        boolean hasNonfiction = !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty();
        boolean hasWantToRead = !wantToReadBooks.isEmpty();
        boolean hasUnranked = !unrankedBooks.isEmpty();
        boolean hasAnyBooks = hasFiction || hasNonfiction || hasWantToRead || hasUnranked;

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
                effectiveSelectedBookshelf = "FICTION";
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
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", rankingState.getBookshelf().name());
        } else if (mode == Mode.REVIEW) {
            model.addAttribute("mode", mode);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", null);
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
            model.addAttribute("comparisonBookCoverId", compRanking.getBook().getCoverId());
            model.addAttribute("comparisonBookEditionOlid", compRanking.getBook().getEditionOlid());
            if (rankingState.getWorkOlidBeingRanked() != null) {
                bookRepository.findByWorkOlid(rankingState.getWorkOlidBeingRanked())
                    .ifPresent(b -> {
                        model.addAttribute("rankingBookCoverId", b.getCoverId());
                        model.addAttribute("rankingBookEditionOlid", b.getEditionOlid());
                    });
            }
        }

        return "index";
    }

    // ========== URL-Based Mode Routes ==========

    @GetMapping("/rank/categorize")
    public String showCategorize(Model model, HttpSession session) {
        Long userId = getCurrentUserId(session);
        RankingState rs = getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.CATEGORIZE) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);
        model.addAttribute("isRerank", rs.getOriginalPosition() != null);
        model.addAttribute("editionSelected", rs.isEditionSelected());
        model.addAttribute("isRankAll", rs.isRankAll());
        if (rs.isRankAll()) {
            List<Ranking> unrankedBooks = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
            model.addAttribute("rankAllRemaining", unrankedBooks.size());
        }
        model.addAttribute("mode", Mode.CATEGORIZE);

        return "index";
    }

    @GetMapping("/rank/compare")
    public String showRankCompare(Model model, HttpSession session) {
        Long userId = getCurrentUserId(session);
        RankingState rs = getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.RANK) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);

        List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, rs.getBookshelf(), rs.getCategory()
        );
        if (rs.getCompareToIndex() >= currentList.size()) {
            return "redirect:/my-books";
        }
        Ranking compRanking = currentList.get(rs.getCompareToIndex());
        model.addAttribute("comparisonBookTitle", compRanking.getBook().getTitle());
        model.addAttribute("comparisonBookAuthor", compRanking.getBook().getAuthor());
        model.addAttribute("comparisonBookWorkOlid", compRanking.getBook().getWorkOlid());
        model.addAttribute("comparisonBookCoverId", compRanking.getBook().getCoverId());
        model.addAttribute("comparisonBookEditionOlid", compRanking.getBook().getEditionOlid());

        if (rs.getWorkOlidBeingRanked() != null) {
            bookRepository.findByWorkOlid(rs.getWorkOlidBeingRanked())
                .ifPresent(b -> {
                    model.addAttribute("rankingBookCoverId", b.getCoverId());
                    model.addAttribute("rankingBookEditionOlid", b.getEditionOlid());
                });
        }

        model.addAttribute("mode", Mode.RANK);
        return "index";
    }

    @Transactional
    @GetMapping("/rank/edition")
    public String showEditionSelection(@RequestParam(required = false, defaultValue = "0") int page,
                                       Model model, HttpSession session) {
        Long userId = getCurrentUserId(session);
        RankingState rs = getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.SELECT_EDITION) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);
        model.addAttribute("cameFromResolve",
            EDITION_SOURCE_RESOLVE.equals(session.getAttribute(SESSION_EDITION_SELECTION_SOURCE)));

        @SuppressWarnings("unchecked")
        List<OpenLibraryService.EditionResult> allEditions =
            (List<OpenLibraryService.EditionResult>) session.getAttribute(SESSION_CACHED_EDITIONS);

        if (allEditions == null) {
            Book book = bookRepository.findByWorkOlid(rs.getWorkOlidBeingRanked()).orElse(null);
            String preferredEditionOlid = book != null ? book.getEditionOlid() : null;
            allEditions = openLibraryService.getEditionsForWork(
                rs.getWorkOlidBeingRanked(), OpenLibraryService.DEFAULT_EDITION_FETCH_LIMIT, 0, preferredEditionOlid);
            Integer coverId = book != null ? book.getCoverId() : null;
            if (coverId != null && !allEditions.isEmpty() && !coverId.equals(allEditions.get(0).coverId())) {
                allEditions = moveMatchingToFront(allEditions, e -> coverId.equals(e.coverId()));
            }
            session.setAttribute(SESSION_CACHED_EDITIONS, allEditions);
        }

        if (allEditions.size() == 1 && !rs.isRankAll()) {
            OpenLibraryService.EditionResult soleEdition = allEditions.get(0);

            Book book = bookService.findOrCreateBook(rs.getWorkOlidBeingRanked(),
                soleEdition.editionOlid(), rs.getTitleBeingRanked(),
                rs.getAuthorBeingRanked(), null, soleEdition.coverId());
            book.setIsbn13(soleEdition.isbn13());
            if (soleEdition.coverId() != null) {
                book.setCoverId(soleEdition.coverId());
            }
            bookRepository.save(book);

            if (rs.isWantToRead()) {
                session.removeAttribute(SESSION_CACHED_EDITIONS);
                return addToWantToReadAndContinue(userId, rs, book, session);
            }

            rs.setEditionOlidBeingRanked(soleEdition.editionOlid());
            rs.setIsbn13BeingRanked(soleEdition.isbn13());
            rs.setEditionSelected(true);
            rs.setMode(RankingMode.CATEGORIZE);
            saveRankingState(session, rs);

            session.removeAttribute(SESSION_CACHED_EDITIONS);
            return "redirect:/rank/categorize";
        }

        if (allEditions.isEmpty()) {
            if (rs.isWantToRead()) {
                Book book = bookService.findOrCreateBook(rs.getWorkOlidBeingRanked(),
                    null, rs.getTitleBeingRanked(), rs.getAuthorBeingRanked(), null, null);
                session.removeAttribute(SESSION_CACHED_EDITIONS);
                return addToWantToReadAndContinue(userId, rs, book, session);
            }
            rs.setEditionSelected(true);
            rs.setMode(RankingMode.CATEGORIZE);
            saveRankingState(session, rs);
            session.removeAttribute(SESSION_CACHED_EDITIONS);
            return "redirect:/rank/categorize";
        }

        PaginationResult<OpenLibraryService.EditionResult> editionPagination = PaginationResult.of(allEditions, page, EDITION_PAGE_SIZE);

        model.addAttribute("editionResults", editionPagination.pageItems());
        model.addAttribute(SESSION_EDITION_PAGE, editionPagination.page());
        model.addAttribute("editionTotalPages", editionPagination.totalPages());
        model.addAttribute("editionTotalCount", editionPagination.totalCount());
        model.addAttribute("editionPageSize", EDITION_PAGE_SIZE);
        model.addAttribute("isRankAll", rs.isRankAll());
        if (rs.isRankAll()) {
            List<Ranking> remainingUnranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
            model.addAttribute("rankAllRemaining", remainingUnranked.size());
        }
        model.addAttribute("mode", Mode.SELECT_EDITION);

        return "index";
    }

    @GetMapping("/resolve")
    public String showResolve(Model model, HttpSession session,
                              @RequestParam(required = false) String resolveQuery) {
        Long userId = getCurrentUserId(session);
        RankingState rs = getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.RESOLVE) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);

        Object skipResolve = session.getAttribute(SESSION_SKIP_RESOLVE);

        if (SKIP_RESOLVE_MANUAL.equals(skipResolve)) {
            if (resolveQuery != null && !resolveQuery.isBlank()) {
                List<OpenLibraryService.BookResult> resolveResults =
                    SearchService.deduplicateResults(openLibraryService.searchBooks(resolveQuery, MANUAL_SEARCH_RESULTS));
                model.addAttribute("resolveResults", resolveResults);
                model.addAttribute("resolveQuery", resolveQuery);
                if (resolveResults.isEmpty()) {
                    model.addAttribute("resolveNoResults", true);
                }
            }
            model.addAttribute("mode", Mode.MANUAL_RESOLVE);
            return "index";
        }

        int maxResults = SKIP_RESOLVE_EXPANDED.equals(skipResolve) ? SEARCH_RESULTS_EXPANDED : SEARCH_RESULTS_DEFAULT;
        List<OpenLibraryService.BookResult> resolveResults = searchService.combinedSearch(
                rs.getTitleBeingRanked(),
                rs.getAuthorBeingRanked(),
                maxResults);

        if (!resolveResults.isEmpty()) {
            model.addAttribute("resolveResults", resolveResults);
            if (skipResolve == null && resolveResults.size() < maxResults) {
                session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_EXPANDED);
            }
            model.addAttribute("mode", Mode.RESOLVE);
            return "index";
        }

        if (skipResolve == null) {
            resolveResults = searchService.combinedSearch(
                rs.getTitleBeingRanked(), rs.getAuthorBeingRanked(), SEARCH_RESULTS_EXPANDED);
            if (!resolveResults.isEmpty()) {
                model.addAttribute("resolveResults", resolveResults);
                session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_EXPANDED);
                model.addAttribute("mode", Mode.RESOLVE);
                return "index";
            }
        }

        log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rs.getTitleBeingRanked(), rs.getAuthorBeingRanked());
        session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_MANUAL);
        return "redirect:/resolve";
    }

    @GetMapping("/review/{rankingId}")
    public String showReview(@PathVariable Long rankingId, Model model, HttpSession session) {
        Long userId = getCurrentUserId(session);
        RankingState rs = getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.REVIEW ||
            !rankingId.equals(rs.getBookIdBeingReviewed())) {
            return "redirect:/my-books";
        }

        Ranking ranking = rankingRepository.findById(rankingId).orElse(null);
        if (ranking == null || !ranking.getUser().getId().equals(userId)) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("reviewBook", ranking);
        model.addAttribute("mode", Mode.REVIEW);

        return "index";
    }

    private Mode determineMode(RankingState rankingState) {
        if (rankingState == null) {
            return Mode.LIST;
        }
        if (rankingState.getMode() != null) {
            return convertRankingMode(rankingState.getMode());
        }
        return Mode.LIST;
    }

    private Mode convertRankingMode(RankingMode rankingMode) {
        return switch (rankingMode) {
            case RESOLVE -> Mode.RESOLVE;
            case SELECT_EDITION -> Mode.SELECT_EDITION;
            case CATEGORIZE -> Mode.CATEGORIZE;
            case RANK -> Mode.RANK;
            case REVIEW -> Mode.REVIEW;
            case RE_RANK -> Mode.RE_RANK;
            case REMOVE -> Mode.REMOVE;
        };
    }

    @Transactional
    @PostMapping("/choose")
    public String chooseBook(@RequestParam String choice, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = getRankingState(session);
        if (rankingState == null) {
            return "redirect:/my-books";
        }

        List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, rankingState.getBookshelf(), rankingState.getCategory()
        );

        int newLowIndex = rankingState.getLowIndex();
        int newHighIndex = rankingState.getHighIndex();

        if ("new".equals(choice)) {
            newHighIndex = rankingState.getCompareToIndex() - 1;
        } else {
            newLowIndex = rankingState.getCompareToIndex() + 1;
        }

        if (newLowIndex > newHighIndex) {
            Bookshelf rankedBookshelf = rankingState.getBookshelf();
            boolean wasRankAll = rankingState.isRankAll();
            rankingService.insertBookAtPosition(rankingState.getWorkOlidBeingRanked(), rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(),
                rankingState.getReviewBeingRanked(), rankedBookshelf, rankingState.getCategory(), newLowIndex, userId);
            clearRankingState(session);
            session.removeAttribute(SESSION_BOOK_SEARCH_RESULTS);
            session.removeAttribute(SESSION_SKIP_RESOLVE);

            if (wasRankAll) {
                return startNextUnrankedBook(userId, rankedBookshelf, session);
            }
            return "redirect:/my-books?selectedBookshelf=" + rankedBookshelf.name();
        } else {
            int newCompareToIndex = (newLowIndex + newHighIndex) / 2;
            rankingState.setCompareToIndex(newCompareToIndex);
            rankingState.setLowIndex(newLowIndex);
            rankingState.setHighIndex(newHighIndex);
            saveRankingState(session, rankingState);
            return "redirect:/rank/compare";
        }
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
        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, bookshlf, null);
        rankingState.setMode(RankingMode.RE_RANK);
        saveRankingState(session, rankingState);
        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/select-rerank-book")
    public String selectRerankBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.RE_RANK) {
            return "redirect:/my-books";
        }

        rankingState.setBookInfo(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor());
        saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

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
        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, bookshlf, null);
        rankingState.setMode(RankingMode.REMOVE);
        saveRankingState(session, rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/start-remove-wtr")
    public String startRemoveWantToRead(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        rankingState.setMode(RankingMode.REMOVE);
        saveRankingState(session, rankingState);
        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @Transactional
    @PostMapping("/select-remove-wtr-book")
    public String selectRemoveWantToReadBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REMOVE || rankingState.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        clearRankingState(session);
        session.removeAttribute(SESSION_SKIP_RESOLVE);

        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @PostMapping("/direct-review")
    public String directReview(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, ranking.getBookshelf(), null);
        rankingState.setBookIdBeingReviewed(bookId);
        rankingState.setMode(RankingMode.REVIEW);
        saveRankingState(session, rankingState);

        return "redirect:/review/" + bookId;
    }

    @Transactional
    @PostMapping("/direct-rerank")
    public String directRerank(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), ranking.getBookshelf(), null);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingState.setOriginalCategory(ranking.getCategory());
        rankingState.setOriginalPosition(ranking.getPosition());
        rankingState.setMode(RankingMode.CATEGORIZE);
        saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return "redirect:/rank/categorize";
    }

    @Transactional
    @PostMapping("/direct-remove")
    public String directRemove(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        String selectedBookshelf = ranking.getBookshelf().name();
        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
    }

    @Transactional
    @PostMapping("/mark-as-read")
    public String markAsRead(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null);
        boolean needsResolve = ranking.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.SELECT_EDITION);
        }
        saveRankingState(session, rankingState);

        session.removeAttribute(SESSION_CACHED_EDITIONS);
        session.removeAttribute(SESSION_EDITION_PAGE);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/edition";
    }

    @Transactional
    @PostMapping("/remove-from-reading-list")
    public String removeFromReadingList(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @Transactional
    @PostMapping("/select-remove-book")
    public String selectRemoveBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REMOVE) {
            return "redirect:/my-books";
        }

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        clearRankingState(session);
        session.removeAttribute(SESSION_SKIP_RESOLVE);

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
        RankingState rankingState = new RankingState(null, null, null, bookshlf, null);
        rankingState.setMode(RankingMode.REVIEW);
        saveRankingState(session, rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/select-review-book")
    public String selectReviewBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REVIEW) {
            return "redirect:/my-books";
        }

        rankingState.setBookIdBeingReviewed(bookId);
        saveRankingState(session, rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/save-review")
    public String saveReview(@RequestParam(required = false) String review, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REVIEW || rankingState.getBookIdBeingReviewed() == null) {
            return "redirect:/my-books";
        }

        Ranking ranking = rankingRepository.findById(rankingState.getBookIdBeingReviewed()).orElse(null);
        if (ranking != null && ranking.getUser().getId().equals(userId)) {
            String trimmedReview = trimReview(review);
            ranking.setReview(trimmedReview);
            rankingRepository.save(ranking);
        }

        clearRankingState(session);
        session.removeAttribute(SESSION_SKIP_RESOLVE);

        return "redirect:/my-books";
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/search-books")
    public String showSearchBooks(Model model, HttpSession session) {
        getCurrentUserId(session);
        addNavigationAttributes(model, "search-books");

        List<OpenLibraryService.BookResult> searchResults =
            (List<OpenLibraryService.BookResult>) session.getAttribute(SESSION_BOOK_SEARCH_RESULTS);
        model.addAttribute("searchResults", searchResults != null ? searchResults : List.of());
        model.addAttribute("query", session.getAttribute(SESSION_BOOK_SEARCH_QUERY));

        return "search-books";
    }

    @PostMapping("/search-books")
    public String searchBooks(@RequestParam String query, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        List<OpenLibraryService.BookResult> results = openLibraryService.searchBooks(query);
        session.setAttribute(SESSION_BOOK_SEARCH_RESULTS, results);
        session.setAttribute(SESSION_BOOK_SEARCH_QUERY, query);

        RankingState rankingState = getRankingState(session);
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
        RankingState rs = getRankingState(session);
        String selectedBookshelf = rs != null && rs.getBookshelf() != null ? rs.getBookshelf().name() : null;
        rankingService.restoreAbandonedBook(userId, rs);
        clearRankingState(session);
        session.removeAttribute(SESSION_BOOK_SEARCH_RESULTS);
        session.removeAttribute(SESSION_SKIP_RESOLVE);
        session.removeAttribute(SESSION_EDITION_SELECTION_SOURCE);
        if (selectedBookshelf != null) {
            return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
        }
        return "redirect:/my-books";
    }

    @PostMapping("/back-to-edition")
    public String backToEdition(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rs = getRankingState(session);
        if (rs == null || rs.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }
        rs.setMode(RankingMode.SELECT_EDITION);
        rs.setEditionSelected(false);
        saveRankingState(session, rs);
        return "redirect:/rank/edition";
    }

    @PostMapping("/back-to-editions")
    public String backToEditions(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rs = getRankingState(session);
        if (rs == null || rs.getWorkOlidBeingRanked() == null) {
            return "redirect:/my-books";
        }
        String workOlid = rs.getWorkOlidBeingRanked();
        String title = rs.getTitleBeingRanked();
        String author = rs.getAuthorBeingRanked();
        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        clearRankingState(session);
        session.removeAttribute(SESSION_CACHED_EDITIONS);
        String url = UriComponentsBuilder.fromPath("/editions/{workOlid}")
            .queryParam("title", title)
            .queryParam("author", author)
            .buildAndExpand(workOlid)
            .toUriString();
        return "redirect:" + url;
    }

    @PostMapping("/back-to-resolve")
    public String backToResolve(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rs = getRankingState(session);
        if (rs == null || rs.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }
        rs.setMode(RankingMode.RESOLVE);
        saveRankingState(session, rs);
        session.removeAttribute(SESSION_SKIP_RESOLVE);
        session.removeAttribute(SESSION_CACHED_EDITIONS);
        session.removeAttribute(SESSION_EDITION_SELECTION_SOURCE);
        return "redirect:/resolve";
    }

    @Transactional
    @PostMapping("/resolve-book")
    public String resolveBook(@RequestParam String workOlid,
                              @RequestParam String title,
                              @RequestParam String author,
                              @RequestParam(required = false) String editionOlid,
                              @RequestParam(required = false) Integer firstPublishYear,
                              @RequestParam(required = false) Integer coverId,
                              HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }

        if (rankingRepository.existsByUserIdAndBookWorkOlid(userId, workOlid)) {
            Book unverifiedBook = bookService.findOrCreateBook(rankingState.getWorkOlidBeingRanked(),
                null, rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(), null, null);
            session.setAttribute(SESSION_DUPLICATE_RESOLVE_TITLE, title);
            session.setAttribute(SESSION_DUPLICATE_RESOLVE_WORK_OLID, workOlid);
            session.setAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID, unverifiedBook.getId());
            session.removeAttribute(SESSION_SKIP_RESOLVE);
            return "redirect:/my-books";
        }

        Book existingBook = bookService.findOrCreateBook(rankingState.getWorkOlidBeingRanked(),
            null, rankingState.getTitleBeingRanked(), rankingState.getAuthorBeingRanked(), null, null);
        existingBook.setWorkOlid(workOlid);
        existingBook.setEditionOlid(editionOlid);
        existingBook.setTitle(title);
        existingBook.setAuthor(author);
        existingBook.setFirstPublishYear(firstPublishYear);
        existingBook.setCoverId(coverId);
        bookRepository.save(existingBook);

        rankingState.setBookInfo(workOlid, title, author);
        rankingState.setMode(RankingMode.SELECT_EDITION);
        saveRankingState(session, rankingState);

        session.removeAttribute(SESSION_SKIP_RESOLVE);
        session.setAttribute(SESSION_EDITION_SELECTION_SOURCE, EDITION_SOURCE_RESOLVE);
        return "redirect:/rank/edition";
    }

    @PostMapping("/skip-resolve")
    public String skipResolve(HttpSession session) {
        Object current = session.getAttribute(SESSION_SKIP_RESOLVE);
        if (SKIP_RESOLVE_EXPANDED.equals(current)) {
            session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_MANUAL);
        } else {
            session.setAttribute(SESSION_SKIP_RESOLVE, SKIP_RESOLVE_EXPANDED);
        }
        return "redirect:/resolve";
    }

    @PostMapping("/abandon-resolve")
    public String abandonResolve(HttpSession session) {
        Long userId = getCurrentUserId(session);
        RankingState rs = getRankingState(session);
        String title = "unknown book";
        if (rs != null) {
            title = rs.getTitleBeingRanked();
            log.warn("RESOLVE abandoned: user skipped manual search for \"{}\" by {}", title, rs.getAuthorBeingRanked());
            clearRankingState(session);
        }
        session.removeAttribute(SESSION_SKIP_RESOLVE);
        session.setAttribute(SESSION_RESOLVE_WARNING, title);
        return "redirect:/my-books?selectedBookshelf=UNRANKED";
    }

    @Transactional
    @PostMapping("/skip-duplicate-resolve")
    public String skipDuplicateResolve(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Long unverifiedBookId = (Long) session.getAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID);
        clearDuplicateResolveSession(session);

        clearRankingState(session);
        session.removeAttribute(SESSION_SKIP_RESOLVE);

        if (unverifiedBookId != null) {
            rankingService.cleanupUnverifiedBook(userId, unverifiedBookId);
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

        Long unverifiedBookId = (Long) session.getAttribute(SESSION_DUPLICATE_RESOLVE_BOOK_ID);
        String duplicateWorkOlid = (String) session.getAttribute(SESSION_DUPLICATE_RESOLVE_WORK_OLID);
        clearDuplicateResolveSession(session);
        session.removeAttribute(SESSION_SKIP_RESOLVE);

        if (unverifiedBookId != null) {
            rankingService.cleanupUnverifiedBook(userId, unverifiedBookId);
        }

        Ranking existingRanking = duplicateWorkOlid != null
            ? rankingRepository.findByUserIdAndBookWorkOlid(userId, duplicateWorkOlid)
            : null;

        if (existingRanking == null) {
            clearRankingState(session);
            return "redirect:/my-books";
        }

        Book existingBook = existingRanking.getBook();
        RankingState newState = new RankingState(existingBook.getWorkOlid(), existingBook.getTitle(), existingBook.getAuthor(), null, null);
        newState.setReviewBeingRanked(existingRanking.getReview());
        newState.setMode(RankingMode.CATEGORIZE);
        saveRankingState(session, newState);

        rankingService.deleteRankingAndCloseGap(userId, existingRanking);

        return "redirect:/rank/categorize";
    }

    @PostMapping("/select-book")
    public String selectBook(@RequestParam String workOlid,
                             @RequestParam String bookName,
                             @RequestParam String author,
                             @RequestParam(required = false) String editionOlid,
                             @RequestParam(required = false) Integer firstPublishYear,
                             @RequestParam(required = false) Integer coverId,
                             HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        rankingService.restoreAbandonedBook(userId, getRankingState(session));
        RankingState rankingState = getRankingState(session);
        if (rankingState == null) {
            rankingState = new RankingState(null, null, null, null, null);
        }

        bookService.findOrCreateBook(workOlid, editionOlid, bookName, author, firstPublishYear, coverId);

        rankingState.setBookInfo(workOlid, bookName, author);
        rankingState.setMode(RankingMode.SELECT_EDITION);
        saveRankingState(session, rankingState);

        session.removeAttribute(SESSION_CACHED_EDITIONS);
        session.removeAttribute(SESSION_EDITION_PAGE);

        session.setAttribute(SESSION_EDITION_SELECTION_SOURCE, EDITION_SOURCE_SEARCH);
        return "redirect:/rank/edition";
    }

    @Transactional
    @PostMapping("/select-edition")
    public String selectEdition(@RequestParam String editionOlid,
                                @RequestParam(required = false) String isbn13,
                                @RequestParam(required = false) String title,
                                @RequestParam(required = false) Integer coverId,
                                @RequestParam(required = false) String action,
                                HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getWorkOlidBeingRanked() == null) {
            return "redirect:/my-books";
        }

        Book book = bookService.findOrCreateBook(rankingState.getWorkOlidBeingRanked(),
            editionOlid, rankingState.getTitleBeingRanked(),
            rankingState.getAuthorBeingRanked(), null, coverId);
        book.setEditionOlid(editionOlid);
        book.setIsbn13(isbn13);
        if (coverId != null) {
            book.setCoverId(coverId);
        }
        if (title != null && !title.isBlank()) {
            book.setTitle(title);
            rankingState.setTitleBeingRanked(title);
        }
        bookRepository.save(book);

        if (rankingState.isWantToRead() || "want-to-read".equals(action)) {
            session.removeAttribute(SESSION_CACHED_EDITIONS);
            session.removeAttribute(SESSION_EDITION_PAGE);
            return addToWantToReadAndContinue(userId, rankingState, book, session);
        }

        rankingState.setEditionOlidBeingRanked(editionOlid);
        rankingState.setIsbn13BeingRanked(isbn13);
        rankingState.setEditionSelected(true);
        rankingState.setMode(RankingMode.CATEGORIZE);
        saveRankingState(session, rankingState);

        session.removeAttribute(SESSION_CACHED_EDITIONS);
        session.removeAttribute(SESSION_EDITION_PAGE);
        session.removeAttribute(SESSION_EDITION_SELECTION_SOURCE);
        return "redirect:/rank/categorize";
    }

    private Ranking createWantToReadRanking(Long userId, Book book) {
        List<Ranking> wantToReadRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        int position = wantToReadRankings.size();
        User userRef = userRepository.getReferenceById(userId);
        Ranking newRanking = new Ranking(userRef, book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, position);
        return rankingRepository.save(newRanking);
    }

    private String addToWantToReadAndContinue(Long userId, RankingState rankingState, Book book, HttpSession session) {
        Ranking newRanking = createWantToReadRanking(userId, book);
        newRanking.setReview(rankingState.getReviewBeingRanked());
        rankingRepository.save(newRanking);

        boolean wasRankAll = rankingState.isRankAll();
        clearRankingState(session);

        if (wasRankAll) {
            return startNextUnrankedBook(userId, Bookshelf.WANT_TO_READ, session);
        }
        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
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
        Long userId = getCurrentUserId(session);
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

        createWantToReadRanking(userId, book);

        return redirectTo;
    }

    @GetMapping("/editions/{workOlid}")
    public String showEditions(@PathVariable String workOlid,
                               @RequestParam(required = false) String title,
                               @RequestParam(required = false) String author,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false) Integer coverId,
                               Model model, HttpSession session) {
        addNavigationAttributes(model, "search");

        @SuppressWarnings("unchecked")
        List<OpenLibraryService.EditionResult> allEditions =
            (List<OpenLibraryService.EditionResult>) session.getAttribute(SESSION_BROWSE_EDITIONS_PREFIX + workOlid);

        if (allEditions == null) {
            allEditions = openLibraryService.getEditionsForWork(workOlid, OpenLibraryService.DEFAULT_EDITION_FETCH_LIMIT, 0);
            if (coverId != null) {
                allEditions = moveMatchingToFront(allEditions, e -> coverId.equals(e.coverId()));
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

        Long userId = getExistingUserId(session);
        if (userId != null) {
            Ranking existing = rankingRepository.findByUserIdAndBookWorkOlid(userId, workOlid);
            if (existing != null) {
                model.addAttribute("libraryBookshelf", existing.getBookshelf().name());
            }
        }

        return "editions";
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
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }
        rankingService.restoreAbandonedBook(userId, getRankingState(session));

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

        RankingState rankingState = getRankingState(session);
        if (rankingState == null) {
            rankingState = new RankingState(null, null, null, null, null);
        }
        rankingState.setBookInfo(workOlid, titleToUse, author);
        rankingState.setEditionOlidBeingRanked(editionOlid);
        rankingState.setIsbn13BeingRanked(isbn13);
        rankingState.setEditionSelected(true);
        rankingState.setMode(RankingMode.CATEGORIZE);
        saveRankingState(session, rankingState);

        return "redirect:/rank/categorize";
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
        RankingState rankingState = getRankingState(session);
        if (rankingState == null || rankingState.getTitleBeingRanked() == null) {
            return "redirect:/my-books";
        }

        String workOlid = rankingState.getWorkOlidBeingRanked();
        String bookName = rankingState.getTitleBeingRanked();
        String author = rankingState.getAuthorBeingRanked();
        String trimmedReview = trimReview(review);

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
            boolean wasRankAll = rankingState.isRankAll();
            Book book = bookService.findOrCreateBook(workOlid, null, bookName, author, null, null);
            User userRef = userRepository.getReferenceById(userId);
            Ranking newRanking = new Ranking(userRef, book, bookshelfEnum, bookCategory, 0);
            newRanking.setReview(trimmedReview);
            rankingRepository.save(newRanking);
            clearRankingState(session);
            session.removeAttribute(SESSION_BOOK_SEARCH_RESULTS);
            session.removeAttribute(SESSION_SKIP_RESOLVE);

            if (wasRankAll) {
                return startNextUnrankedBook(userId, bookshelfEnum, session);
            }
            return "redirect:/my-books?selectedBookshelf=" + bookshelfEnum.name();
        } else {
            int lowIndex = 0;
            int highIndex = currentList.size() - 1;
            int compareToIndex = (lowIndex + highIndex) / 2;
            rankingState.setBookInfo(workOlid, bookName, author);
            rankingState.setReviewBeingRanked(trimmedReview);
            rankingState.setBookshelf(bookshelfEnum);
            rankingState.setCategory(bookCategory);
            rankingState.setCompareToIndex(compareToIndex);
            rankingState.setLowIndex(lowIndex);
            rankingState.setHighIndex(highIndex);
            rankingState.setMode(RankingMode.RANK);
            saveRankingState(session, rankingState);
            return "redirect:/rank/compare";
        }
    }

    @GetMapping("/export-csv")
    public ResponseEntity<String> exportCsv(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        String csv = importExportService.generateCsv(userId);
        String filename = "books.csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", filename);

        return ResponseEntity.ok()
            .headers(headers)
            .body(csv);
    }

    private Long getExistingUserId(HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity != null) {
            User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
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
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     Model model, HttpSession session) {
        addNavigationAttributes(model, "search");
        model.addAttribute("searchType", type);
        model.addAttribute("query", query);

        Long currentUserId = getExistingUserId(session);
        boolean isLoggedIn = getOAuthIdentity() != null && currentUserId != null;
        User currentUser = isLoggedIn ? userRepository.findById(currentUserId).orElse(null) : null;
        boolean isRealUser = currentUser != null && !currentUser.isGuest();

        // --- Books tab ---
        Map<Bookshelf, Map<BookCategory, List<Ranking>>> allRankings = currentUserId != null ? rankingService.fetchAllRankingsGrouped(currentUserId) : Map.of();
        Map<String, UserBookRank> userBooks = currentUserId != null ? rankingService.buildUserBooksMap(allRankings) : Map.of();
        List<OpenLibraryService.BookResult> bookResults;
        if ("books".equals(type) && query != null && !query.isBlank()) {
            bookResults = SearchService.deduplicateResults(openLibraryService.searchBooks(query));
        } else {
            bookResults = searchService.getRandomBooksExcluding(userBooks);
        }
        model.addAttribute("bookResults", bookResults);
        if (currentUserId != null) {
            model.addAttribute("userBooks", userBooks);
        }

        // --- Profiles tab ---
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

        // --- Following tab ---
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

        // --- Followers tab ---
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

        // --- Curated tab ---
        if ("curated".equals(type)) {
            List<User> allCurated;
            if (query != null && !query.isBlank()) {
                allCurated = userRepository.searchCuratedProfiles(query.trim());
            } else {
                allCurated = userRepository.findByIsCurated(true);
            }
            PaginationResult<User> curatedPagination = PaginationResult.of(allCurated, page, pageSize);
            model.addAttribute("curatedResults", curatedPagination.pageItems());
            model.addAttribute("curatedPage", curatedPagination.page());
            model.addAttribute("curatedTotalPages", curatedPagination.totalPages());
            model.addAttribute("curatedTotalCount", curatedPagination.totalCount());
        } else {
            model.addAttribute("curatedResults", List.of());
        }

        model.addAttribute("canFollow", isRealUser);
        model.addAttribute("userHasPublished", currentUser != null && currentUser.isPublishLists());
        model.addAttribute("pageSize", pageSize);

        return "search";
    }

    @GetMapping("/search-profiles")
    public String showExplore(@RequestParam(required = false) String query, Model model, HttpSession session) {
        getCurrentUserId(session);
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
            long totalProfiles = userRepository.countPublicProfiles();
            long moreCount = Math.max(0, totalProfiles - recentProfiles.size());
            model.addAttribute("recentProfiles", profileDisplays);
            model.addAttribute("moreProfilesCount", moreCount);
        }

        return "explore";
    }

    private static final int MIN_RANKED_BOOKS_FOR_RECS = 10;
    private static final List<BookCategory> RANKED_CATEGORIES = List.of(BookCategory.LIKED, BookCategory.OK, BookCategory.DISLIKED);

    @GetMapping("/recs")
    public String showRecs(Model model, HttpSession session) {
        Long userId = getCurrentUserId(session);
        addNavigationAttributes(model, "recs");

        long fictionRankedCount = userId != null
                ? rankingRepository.countByUserIdAndBookshelfAndCategoryIn(userId, Bookshelf.FICTION, RANKED_CATEGORIES) : 0;
        long nonfictionRankedCount = userId != null
                ? rankingRepository.countByUserIdAndBookshelfAndCategoryIn(userId, Bookshelf.NONFICTION, RANKED_CATEGORIES) : 0;

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

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> allRankings = userId != null ? rankingService.fetchAllRankingsGrouped(userId) : Map.of();
        Map<String, UserBookRank> userBooks = userId != null ? rankingService.buildUserBooksMap(allRankings) : Map.of();
        model.addAttribute("userBooks", userBooks);

        return "recs";
    }

    @GetMapping("/user/{username}")
    public String viewUser(@PathVariable String username, Model model, HttpSession session) {
        getCurrentUserId(session);

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return "redirect:/";
        }

        if (!user.isCurated() && !user.isPublishLists()) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "search");

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> viewedUserRankings = rankingService.fetchAllRankingsGrouped(user.getId());
        BookLists fictionBooks = rankingService.getBookLists(Bookshelf.FICTION, viewedUserRankings);
        BookLists nonfictionBooks = rankingService.getBookLists(Bookshelf.NONFICTION, viewedUserRankings);
        boolean hasFiction = !fictionBooks.liked().isEmpty() || !fictionBooks.ok().isEmpty() || !fictionBooks.disliked().isEmpty() || !fictionBooks.unranked().isEmpty();
        boolean hasNonfiction = !nonfictionBooks.liked().isEmpty() || !nonfictionBooks.ok().isEmpty() || !nonfictionBooks.disliked().isEmpty() || !nonfictionBooks.unranked().isEmpty();

        Long currentUserId = getCurrentUserId(session);
        Map<Bookshelf, Map<BookCategory, List<Ranking>>> currentUserRankings = currentUserId != null ? rankingService.fetchAllRankingsGrouped(currentUserId) : Map.of();
        Map<String, UserBookRank> userBooks = currentUserId != null ? rankingService.buildUserBooksMap(currentUserRankings) : Map.of();

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
        OAuthIdentity identity = getOAuthIdentity();
        if (identity != null) {
            User existingUser = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
            if (existingUser == null) {
                return "redirect:/setup-username";
            }
        }

        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/";
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "profile");

        Map<Bookshelf, Map<BookCategory, List<Ranking>>> profileRankings = rankingService.fetchAllRankingsGrouped(userId);
        long fictionCount = rankingService.countBooksByBookshelf(profileRankings, Bookshelf.FICTION);
        long nonfictionCount = rankingService.countBooksByBookshelf(profileRankings, Bookshelf.NONFICTION);

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
        if (getOAuthIdentity() == null) {
            return "redirect:/search?type=profiles";
        }

        Long currentUserId = getCurrentUserId(session);
        User currentUser = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
        if (currentUser == null || currentUser.isGuest()) {
            return "redirect:/search?type=profiles";
        }

        if (currentUserId.equals(userId)) {
            return "redirect:/search?type=profiles";
        }

        User targetUser = userRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            return "redirect:/search?type=profiles";
        }

        if (!followRepository.existsByFollowerIdAndFollowedId(currentUserId, userId)) {
            Follow follow = new Follow(currentUser, targetUser);
            followRepository.save(follow);
        }

        return isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=profiles";
    }

    @PostMapping("/unfollow")
    public String unfollowUser(@RequestParam Long userId, @RequestParam(required = false) String returnUrl, HttpSession session) {
        if (getOAuthIdentity() == null) {
            return "redirect:/search?type=profiles";
        }

        Long currentUserId = getCurrentUserId(session);
        User currentUser = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;
        if (currentUser == null || currentUser.isGuest()) {
            return "redirect:/search?type=profiles";
        }

        followRepository.findByFollowerIdAndFollowedId(currentUserId, userId)
            .ifPresent(followRepository::delete);

        return isSafeRedirectUrl(returnUrl) ? "redirect:" + returnUrl : "redirect:/search?type=profiles";
    }

    @PostMapping("/toggle-publish-lists")
    public String togglePublishLists(HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        user.setPublishLists(!user.isPublishLists());
        userRepository.save(user);

        return "redirect:/my-profile";
    }

    @GetMapping("/setup-username")
    public String showUsernameSetup(Model model) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        User existingUser = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (existingUser != null) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "setup");
        return "setup-username";
    }

    @PostMapping("/setup-username")
    public String submitUsername(@RequestParam String username, Model model, HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "setup");

        String validationError = userService.validateUsername(username);
        if (validationError != null) {
            model.addAttribute("error", validationError);
            if (username != null && !username.isBlank()) {
                model.addAttribute("username", username.trim());
            }
            return "setup-username";
        }
        username = username.trim();

        User newUser = new User(username, identity.subject(), identity.provider());
        newUser.setGuest(false);

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

        Long guestUserId = (Long) session.getAttribute(SESSION_GUEST_USER_ID);
        if (guestUserId != null) {
            userService.migrateGuestDataToUser(guestUserId, newUser.getId());
            session.removeAttribute(SESSION_GUEST_USER_ID);
        }

        return "redirect:/my-profile";
    }

    @GetMapping("/change-username")
    public String showChangeUsername(Model model, HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }
        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }
        addNavigationAttributes(model, "profile");
        model.addAttribute("username", user.getUsername());
        return "change-username";
    }

    @PostMapping("/change-username")
    public String changeUsername(@RequestParam String username, Model model, HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }
        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        addNavigationAttributes(model, "profile");

        if (username != null && username.trim().equals(user.getUsername())) {
            return "redirect:/my-profile";
        }

        String validationError = userService.validateUsername(username);
        if (validationError != null) {
            model.addAttribute("error", validationError);
            model.addAttribute("username", username != null && !username.isBlank() ? username.trim() : user.getUsername());
            return "change-username";
        }
        username = username.trim();

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

    @PostMapping("/import-goodreads")
    public String importGoodreads(@RequestParam("file") MultipartFile file, HttpSession session) {
        Long userId = getCurrentUserId(session);

        try {
            ImportExportService.ImportResult result = importExportService.importGoodreads(file.getInputStream(), userId);
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        } catch (java.io.IOException e) {
            log.error("Error reading uploaded file: {}", e.getMessage());
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }
    }

    @Transactional
    @PostMapping("/rank-unranked-book")
    public String rankUnrankedBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.UNRANKED) {
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }

        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null);
        rankingState.setBookshelf(Bookshelf.UNRANKED);
        rankingState.setReviewBeingRanked(ranking.getReview());
        boolean needsResolve = ranking.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.SELECT_EDITION);
        }
        saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/edition";
    }

    @Transactional
    @PostMapping("/want-to-read-unranked-book")
    public String wantToReadUnrankedBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.UNRANKED) {
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }

        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null);
        rankingState.setBookshelf(Bookshelf.UNRANKED);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingState.setWantToRead(true);
        boolean needsResolve = ranking.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.SELECT_EDITION);
        }
        saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/edition";
    }

    @Transactional
    @PostMapping("/rank-all")
    public String rankAll(HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return "redirect:/setup-username";
        }

        return startNextUnrankedBook(userId, null, session);
    }

    private String startNextUnrankedBook(Long userId, Bookshelf lastRankedBookshelf, HttpSession session) {
        List<Ranking> unrankedBooks = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
        if (unrankedBooks.isEmpty()) {
            String selectedBookshelf = lastRankedBookshelf != null ? lastRankedBookshelf.name() : "FICTION";
            return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
        }

        Ranking nextBook = unrankedBooks.get(0);

        RankingState rankingState = new RankingState(nextBook.getBook().getWorkOlid(), nextBook.getBook().getTitle(), nextBook.getBook().getAuthor(), null, null);
        rankingState.setBookshelf(Bookshelf.UNRANKED);
        rankingState.setReviewBeingRanked(nextBook.getReview());
        rankingState.setRankAll(true);
        boolean needsResolve = nextBook.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.SELECT_EDITION);
        }
        saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, nextBook);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/edition";
    }

    @Transactional
    @PostMapping("/delete-profile")
    public String deleteProfile(HttpSession session) {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) {
            return "redirect:/";
        }

        User user = userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider()).orElse(null);
        if (user == null || user.isGuest()) {
            return "redirect:/";
        }

        userService.deleteUserAndData(user);

        session.invalidate();

        return "redirect:/logout";
    }
}
