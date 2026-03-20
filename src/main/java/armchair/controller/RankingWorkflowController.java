package armchair.controller;

import armchair.controller.ControllerUtils.PaginationResult;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.service.BookService;
import armchair.service.OpenLibraryService;
import armchair.service.RankingService;
import armchair.service.SearchService;
import armchair.service.SessionStateManager;
import armchair.service.PageAssemblyService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import static armchair.controller.ControllerUtils.*;
import static armchair.service.SessionStateManager.*;

@Controller
public class RankingWorkflowController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(RankingWorkflowController.class);

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private BookService bookService;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private OpenLibraryService openLibraryService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private SessionStateManager sessionState;

    @Autowired
    private BookActionController bookActionController;

    @GetMapping("/rank/categorize")
    public String showCategorize(Model model, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rs = sessionState.getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.CATEGORIZE) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);
        model.addAttribute("isRerank", rs.getRestoration().getOriginalPosition() != null);
        model.addAttribute("isRankAll", rs.isRankAll());
        if (rs.isRankAll()) {
            List<Ranking> unrankedBooks = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
            model.addAttribute("rankAllRemaining", unrankedBooks.size());
        }
        model.addAttribute("mode", PageAssemblyService.Mode.CATEGORIZE);

        return "index";
    }

    @GetMapping("/rank/compare")
    public String showRankCompare(Model model, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rs = sessionState.getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.RANK) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);

        List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, rs.getBookshelf(), rs.getCategory()
        );
        if (rs.getBinarySearch().getCompareToIndex() >= currentList.size()) {
            return "redirect:/my-books";
        }
        Ranking compRanking = currentList.get(rs.getBinarySearch().getCompareToIndex());
        model.addAttribute("comparisonBookTitle", compRanking.getBook().getTitle());
        model.addAttribute("comparisonBookAuthor", compRanking.getBook().getAuthor());
        model.addAttribute("comparisonBookWorkOlid", compRanking.getBook().getWorkOlid());
        model.addAttribute("comparisonBookCoverId", compRanking.getBook().getCoverId());
        model.addAttribute("comparisonBookEditionOlid", compRanking.getBook().getEditionOlid());

        if (rs.getBookIdentity().getWorkOlid() != null) {
            bookRepository.findByWorkOlid(rs.getBookIdentity().getWorkOlid())
                .ifPresent(b -> {
                    model.addAttribute("rankingBookCoverId", b.getCoverId());
                    model.addAttribute("rankingBookEditionOlid", b.getEditionOlid());
                });
        }

        model.addAttribute("mode", PageAssemblyService.Mode.RANK);
        return "index";
    }

    @GetMapping("/resolve")
    public String showResolve(Model model, HttpSession session,
                              @RequestParam(required = false) String resolveQuery) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rs = sessionState.getRankingState(session);

        if (rs == null || rs.getMode() != RankingMode.RESOLVE) {
            return "redirect:/my-books";
        }

        addNavigationAttributes(model, "list");
        model.addAttribute("rankingState", rs);

        String skipResolve = sessionState.getSkipResolve(session);

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
            model.addAttribute("mode", PageAssemblyService.Mode.MANUAL_RESOLVE);
            return "index";
        }

        int maxResults = SKIP_RESOLVE_EXPANDED.equals(skipResolve) ? SEARCH_RESULTS_EXPANDED : SEARCH_RESULTS_DEFAULT;
        List<OpenLibraryService.BookResult> resolveResults = searchService.combinedSearch(
                rs.getBookIdentity().getTitle(),
                rs.getBookIdentity().getAuthor(),
                maxResults);

        if (!resolveResults.isEmpty()) {
            model.addAttribute("resolveResults", resolveResults);
            if (skipResolve == null && resolveResults.size() < maxResults) {
                sessionState.setSkipResolve(session, SKIP_RESOLVE_EXPANDED);
            }
            model.addAttribute("mode", PageAssemblyService.Mode.RESOLVE);
            return "index";
        }

        if (skipResolve == null) {
            resolveResults = searchService.combinedSearch(
                rs.getBookIdentity().getTitle(), rs.getBookIdentity().getAuthor(), SEARCH_RESULTS_EXPANDED);
            if (!resolveResults.isEmpty()) {
                model.addAttribute("resolveResults", resolveResults);
                sessionState.setSkipResolve(session, SKIP_RESOLVE_EXPANDED);
                model.addAttribute("mode", PageAssemblyService.Mode.RESOLVE);
                return "index";
            }
        }

        log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rs.getBookIdentity().getTitle(), rs.getBookIdentity().getAuthor());
        sessionState.setSkipResolve(session, SKIP_RESOLVE_MANUAL);
        return "redirect:/resolve";
    }

    @GetMapping("/review/{rankingId}")
    public String showReview(@PathVariable Long rankingId, Model model, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rs = sessionState.getRankingState(session);

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
        model.addAttribute("mode", PageAssemblyService.Mode.REVIEW);

        return "index";
    }

    @Transactional
    @PostMapping("/choose")
    public String chooseBook(@RequestParam String choice, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null) {
            return "redirect:/my-books";
        }

        List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, rankingState.getBookshelf(), rankingState.getCategory()
        );

        int newLowIndex = rankingState.getBinarySearch().getLowIndex();
        int newHighIndex = rankingState.getBinarySearch().getHighIndex();

        if ("new".equals(choice)) {
            newHighIndex = rankingState.getBinarySearch().getCompareToIndex() - 1;
        } else {
            newLowIndex = rankingState.getBinarySearch().getCompareToIndex() + 1;
        }

        if (newLowIndex > newHighIndex) {
            Bookshelf rankedBookshelf = rankingState.getBookshelf();
            boolean wasRankAll = rankingState.isRankAll();
            rankingService.insertBookAtPosition(rankingState.getBookIdentity().getWorkOlid(), rankingState.getBookIdentity().getTitle(), rankingState.getBookIdentity().getAuthor(),
                rankingState.getReviewBeingRanked(), rankedBookshelf, rankingState.getCategory(), newLowIndex, userId,
                rankingState.getUnrankedRankingId());
            sessionState.clearRankingState(session);
            sessionState.clearSearchAndResolveState(session);

            if (wasRankAll) {
                return bookActionController.startNextUnrankedBook(userId, rankedBookshelf, session);
            }
            return "redirect:/my-books?selectedBookshelf=" + rankedBookshelf.name();
        } else {
            int newCompareToIndex = (newLowIndex + newHighIndex) / 2;
            rankingState.getBinarySearch().setCompareToIndex(newCompareToIndex);
            rankingState.getBinarySearch().setLowIndex(newLowIndex);
            rankingState.getBinarySearch().setHighIndex(newHighIndex);
            sessionState.saveRankingState(session, rankingState);
            return "redirect:/rank/compare";
        }
    }

    @Transactional
    @PostMapping("/categorize")
    public String categorizeBook(@RequestParam(required = false) String bookshelf,
                                  @RequestParam String category,
                                  @RequestParam(required = false) String review,
                                  HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getBookIdentity().getTitle() == null) {
            return "redirect:/my-books";
        }

        if ("want-to-read".equals(category)) {
            String workOlid = rankingState.getBookIdentity().getWorkOlid();
            String bookName = rankingState.getBookIdentity().getTitle();
            String author = rankingState.getBookIdentity().getAuthor();
            Book book = bookService.findOrCreateBook(workOlid,
                null, bookName, author, null, null);
            return addToWantToReadAndContinue(userId, rankingState, book, session);
        }

        String workOlid = rankingState.getBookIdentity().getWorkOlid();
        String bookName = rankingState.getBookIdentity().getTitle();
        String author = rankingState.getBookIdentity().getAuthor();
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
            rankingService.deleteUnrankedRankingById(rankingState.getUnrankedRankingId(), userId);
            Book book = bookService.findOrCreateBook(workOlid, null, bookName, author, null, null);
            if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
                sessionState.clearRankingState(session);
                if (wasRankAll) {
                    return bookActionController.startNextUnrankedBook(userId, bookshelfEnum, session);
                }
                return "redirect:/my-books";
            }
            User userRef = userRepository.getReferenceById(userId);
            Ranking newRanking = new Ranking(userRef, book, bookshelfEnum, bookCategory, 0);
            newRanking.setReview(trimmedReview);
            rankingRepository.save(newRanking);
            sessionState.clearRankingState(session);
            sessionState.clearSearchAndResolveState(session);

            if (wasRankAll) {
                return bookActionController.startNextUnrankedBook(userId, bookshelfEnum, session);
            }
            return "redirect:/my-books?selectedBookshelf=" + bookshelfEnum.name();
        } else {
            boolean wasRankAll = rankingState.isRankAll();
            int lowIndex = 0;
            int highIndex = currentList.size() - 1;
            int compareToIndex = (lowIndex + highIndex) / 2;
            rankingState.getBookIdentity().setBookInfo(workOlid, bookName, author);
            rankingState.setReviewBeingRanked(trimmedReview);
            rankingState.setBookshelf(bookshelfEnum);
            rankingState.setCategory(bookCategory);
            rankingState.getBinarySearch().setCompareToIndex(compareToIndex);
            rankingState.getBinarySearch().setLowIndex(lowIndex);
            rankingState.getBinarySearch().setHighIndex(highIndex);
            rankingState.setMode(RankingMode.RANK);
            rankingState.setRankAll(wasRankAll);
            sessionState.saveRankingState(session, rankingState);
            return "redirect:/rank/compare";
        }
    }

    @PostMapping("/cancel-add")
    public String cancelAdd(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rs = sessionState.getRankingState(session);
        String selectedBookshelf = rs != null && rs.getBookshelf() != null ? rs.getBookshelf().name() : null;
        rankingService.restoreAbandonedBook(userId, rs);
        sessionState.clearRankingState(session);
        sessionState.clearSearchAndResolveState(session);
        if (selectedBookshelf != null) {
            return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
        }
        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/back-to-resolve")
    public String backToResolve(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rs = sessionState.getRankingState(session);
        if (rs == null || rs.getBookIdentity().getTitle() == null) {
            return "redirect:/my-books";
        }

        if (rs.getRestoration().getOriginalResolveTitle() != null) {
            Book book = bookService.findOrCreateBook(rs.getBookIdentity().getWorkOlid(),
                null, rs.getBookIdentity().getTitle(), rs.getBookIdentity().getAuthor(), null, null);
            book.setTitle(rs.getRestoration().getOriginalResolveTitle());
            book.setAuthor(rs.getRestoration().getOriginalResolveAuthor());
            book.setWorkOlid(rs.getRestoration().getOriginalResolveWorkOlid());
            book.setEditionOlid(rs.getRestoration().getOriginalResolveEditionOlid());
            bookRepository.save(book);

            rs.getBookIdentity().setTitle(rs.getRestoration().getOriginalResolveTitle());
            rs.getBookIdentity().setAuthor(rs.getRestoration().getOriginalResolveAuthor());
            rs.getBookIdentity().setWorkOlid(rs.getRestoration().getOriginalResolveWorkOlid());

            rs.getRestoration().setOriginalResolveTitle(null);
            rs.getRestoration().setOriginalResolveAuthor(null);
            rs.getRestoration().setOriginalResolveWorkOlid(null);
            rs.getRestoration().setOriginalResolveEditionOlid(null);
        }

        rs.setMode(RankingMode.RESOLVE);
        sessionState.saveRankingState(session, rs);
        sessionState.clearSearchAndResolveState(session);
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
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getBookIdentity().getTitle() == null) {
            return "redirect:/my-books";
        }

        if (rankingRepository.existsByUserIdAndBookWorkOlid(userId, workOlid)) {
            Book unverifiedBook = bookService.findOrCreateBook(rankingState.getBookIdentity().getWorkOlid(),
                null, rankingState.getBookIdentity().getTitle(), rankingState.getBookIdentity().getAuthor(), null, null);
            sessionState.setDuplicateResolveState(session, title, workOlid, unverifiedBook.getId());
            session.removeAttribute(SESSION_SKIP_RESOLVE);
            return "redirect:/my-books";
        }

        Book existingBook = bookService.findOrCreateBook(rankingState.getBookIdentity().getWorkOlid(),
            null, rankingState.getBookIdentity().getTitle(), rankingState.getBookIdentity().getAuthor(), null, null);

        rankingState.getRestoration().setOriginalResolveTitle(existingBook.getTitle());
        rankingState.getRestoration().setOriginalResolveAuthor(existingBook.getAuthor());
        rankingState.getRestoration().setOriginalResolveWorkOlid(existingBook.getWorkOlid());
        rankingState.getRestoration().setOriginalResolveEditionOlid(existingBook.getEditionOlid());

        existingBook.setWorkOlid(workOlid);
        existingBook.setEditionOlid(editionOlid);
        existingBook.setTitle(title);
        existingBook.setAuthor(author);
        existingBook.setFirstPublishYear(firstPublishYear);
        existingBook.setCoverId(coverId);
        bookRepository.save(existingBook);

        rankingState.getBookIdentity().setBookInfo(workOlid, title, author);
        rankingState.setMode(RankingMode.CATEGORIZE);
        sessionState.saveRankingState(session, rankingState);

        session.removeAttribute(SESSION_SKIP_RESOLVE);
        return "redirect:/rank/categorize";
    }

    @PostMapping("/skip-resolve")
    public String skipResolve(HttpSession session) {
        String current = sessionState.getSkipResolve(session);
        if (SKIP_RESOLVE_EXPANDED.equals(current)) {
            sessionState.setSkipResolve(session, SKIP_RESOLVE_MANUAL);
        } else {
            sessionState.setSkipResolve(session, SKIP_RESOLVE_EXPANDED);
        }
        return "redirect:/resolve";
    }

    @PostMapping("/abandon-resolve")
    public String abandonResolve(HttpSession session) {
        RankingState rs = sessionState.getRankingState(session);
        String title = "unknown book";
        boolean wasRankAll = false;
        if (rs != null) {
            title = rs.getBookIdentity().getTitle();
            wasRankAll = rs.isRankAll();
            log.warn("RESOLVE abandoned: user skipped manual search for \"{}\" by {}", title, rs.getBookIdentity().getAuthor());
            sessionState.clearRankingState(session);
        }
        sessionState.clearSearchAndResolveState(session);
        if (wasRankAll) {
            Long userId = getCurrentUserId();
            if (userId != null) {
                return bookActionController.startNextUnrankedBook(userId, Bookshelf.UNRANKED, session);
            }
        }
        sessionState.setResolveWarning(session, title);
        return "redirect:/my-books?selectedBookshelf=UNRANKED";
    }

    @Transactional
    @PostMapping("/skip-duplicate-resolve")
    public String skipDuplicateResolve(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Long unverifiedBookId = sessionState.getDuplicateResolveBookId(session);
        sessionState.clearDuplicateResolveSession(session);

        RankingState rs = sessionState.getRankingState(session);
        boolean wasRankAll = rs != null && rs.isRankAll();
        sessionState.clearRankingState(session);
        sessionState.clearSearchAndResolveState(session);

        if (unverifiedBookId != null) {
            rankingService.cleanupUnverifiedBook(userId, unverifiedBookId);
        }

        if (wasRankAll) {
            return bookActionController.startNextUnrankedBook(userId, Bookshelf.UNRANKED, session);
        }
        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/rerank-duplicate-resolve")
    public String rerankDuplicateResolve(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Long unverifiedBookId = sessionState.getDuplicateResolveBookId(session);
        String duplicateWorkOlid = sessionState.getDuplicateResolveWorkOlid(session);
        sessionState.clearDuplicateResolveSession(session);
        sessionState.clearSearchAndResolveState(session);

        if (unverifiedBookId != null) {
            rankingService.cleanupUnverifiedBook(userId, unverifiedBookId);
        }

        Ranking existingRanking = duplicateWorkOlid != null
            ? rankingRepository.findByUserIdAndBookWorkOlid(userId, duplicateWorkOlid)
            : null;

        if (existingRanking == null) {
            sessionState.clearRankingState(session);
            return "redirect:/my-books";
        }

        Book existingBook = existingRanking.getBook();
        RankingState newState = new RankingState(existingBook.getWorkOlid(), existingBook.getTitle(), existingBook.getAuthor(), null, null);
        newState.setReviewBeingRanked(existingRanking.getReview());
        newState.setMode(RankingMode.CATEGORIZE);
        sessionState.saveRankingState(session, newState);

        rankingService.deleteRankingAndCloseGap(userId, existingRanking);

        return "redirect:/rank/categorize";
    }

    private String addToWantToReadAndContinue(Long userId, RankingState rankingState, Book book, HttpSession session) {
        Ranking newRanking = rankingService.createWantToReadRanking(userId, book);
        newRanking.setReview(rankingState.getReviewBeingRanked());
        rankingRepository.save(newRanking);
        rankingService.deleteUnrankedRankingById(rankingState.getUnrankedRankingId(), userId);

        boolean wasRankAll = rankingState.isRankAll();
        sessionState.clearRankingState(session);

        if (wasRankAll) {
            return bookActionController.startNextUnrankedBook(userId, Bookshelf.WANT_TO_READ, session);
        }
        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }
}
