package armchair.service;

import armchair.controller.ControllerUtils;
import armchair.dto.BookInfo;
import armchair.dto.BookLists;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;

import static armchair.service.SessionStateManager.*;

@Service
public class PageAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(PageAssemblyService.class);

    public enum Mode {
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

    @Autowired
    private RankingService rankingService;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OpenLibraryService openLibraryService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private SessionStateManager sessionState;

    public Mode determineMode(RankingState rankingState) {
        if (rankingState == null) {
            return Mode.LIST;
        }
        if (rankingState.getMode() != null) {
            return convertRankingMode(rankingState.getMode());
        }
        return Mode.LIST;
    }

    public Mode convertRankingMode(RankingMode rankingMode) {
        return switch (rankingMode) {
            case RESOLVE -> Mode.RESOLVE;
            case CATEGORIZE -> Mode.CATEGORIZE;
            case RANK -> Mode.RANK;
            case REVIEW -> Mode.REVIEW;
            case RE_RANK -> Mode.RE_RANK;
            case REMOVE -> Mode.REMOVE;
        };
    }

    /**
     * Assembles the /my-books page model. Returns null if a redirect is needed,
     * in which case the redirect URL is set via the returned string.
     */
    public String assemblePage(Long userId, Model model, HttpSession session,
                               String selectedBookshelf, String resolveQuery) {

        String resolveWarning = sessionState.getResolveWarning(session);
        if (resolveWarning != null) {
            model.addAttribute(SESSION_RESOLVE_WARNING, resolveWarning);
        }

        RankingState rankingState = sessionState.getRankingState(session);
        Mode mode = determineMode(rankingState);

        String duplicateResolveTitle = sessionState.getDuplicateResolveTitle(session);
        if (duplicateResolveTitle != null) {
            mode = Mode.DUPLICATE_RESOLVE;
            model.addAttribute(SESSION_DUPLICATE_RESOLVE_TITLE, duplicateResolveTitle);
        }

        boolean needsResolve = mode == Mode.RESOLVE ||
            (mode == Mode.CATEGORIZE && rankingState != null && rankingState.getBookIdentity().getWorkOlid() == null);
        if (needsResolve && rankingState != null) {
            String skipResolve = sessionState.getSkipResolve(session);
            if (SKIP_RESOLVE_MANUAL.equals(skipResolve)) {
                mode = Mode.MANUAL_RESOLVE;
                if (resolveQuery != null && !resolveQuery.isBlank()) {
                    List<OpenLibraryService.BookResult> resolveResults =
                        SearchService.deduplicateResults(openLibraryService.searchBooks(resolveQuery, ControllerUtils.MANUAL_SEARCH_RESULTS));
                    model.addAttribute("resolveResults", resolveResults);
                    model.addAttribute("resolveQuery", resolveQuery);
                    if (resolveResults.isEmpty()) {
                        model.addAttribute("resolveNoResults", true);
                    }
                }
            } else if (skipResolve == null || SKIP_RESOLVE_EXPANDED.equals(skipResolve)) {
                int maxResults = SKIP_RESOLVE_EXPANDED.equals(skipResolve) ? ControllerUtils.SEARCH_RESULTS_EXPANDED : ControllerUtils.SEARCH_RESULTS_DEFAULT;
                List<OpenLibraryService.BookResult> resolveResults = searchService.combinedSearch(
                        rankingState.getBookIdentity().getTitle(),
                        rankingState.getBookIdentity().getAuthor(),
                        maxResults);
                if (!resolveResults.isEmpty()) {
                    mode = Mode.RESOLVE;
                    model.addAttribute("resolveResults", resolveResults);
                    if (skipResolve == null && resolveResults.size() < maxResults) {
                        sessionState.setSkipResolve(session, SKIP_RESOLVE_EXPANDED);
                    }
                } else if (skipResolve == null) {
                    resolveResults = searchService.combinedSearch(
                        rankingState.getBookIdentity().getTitle(),
                        rankingState.getBookIdentity().getAuthor(),
                        ControllerUtils.SEARCH_RESULTS_EXPANDED);
                    if (!resolveResults.isEmpty()) {
                        mode = Mode.RESOLVE;
                        model.addAttribute("resolveResults", resolveResults);
                    } else {
                        log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rankingState.getBookIdentity().getTitle(), rankingState.getBookIdentity().getAuthor());
                        sessionState.setSkipResolve(session, SKIP_RESOLVE_MANUAL);
                        return "redirect:/my-books";
                    }
                } else {
                    log.warn("RESOLVE auto-search found nothing for \"{}\" by {}", rankingState.getBookIdentity().getTitle(), rankingState.getBookIdentity().getAuthor());
                    sessionState.setSkipResolve(session, SKIP_RESOLVE_MANUAL);
                    return "redirect:/my-books";
                }
            }
        }

        String userName = null;
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
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
        if (effectiveSelectedBookshelf != null && !effectiveSelectedBookshelf.isEmpty()) {
            effectiveSelectedBookshelf = effectiveSelectedBookshelf.toUpperCase();
        }
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
        model.addAttribute("fictionRankedBooks", fictionBooks.toRankedList());
        model.addAttribute("nonfictionRankedBooks", nonfictionBooks.toRankedList());
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
            sessionState.clearRankingState(session);
            rankingState = null;
            mode = Mode.LIST;
            model.addAttribute("mode", Mode.LIST);
            model.addAttribute("rerankType", null);
            model.addAttribute("removeType", null);
            model.addAttribute("reviewType", null);
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

        if (rankingState != null && mode == Mode.RANK && rankingState.getBookIdentity().getTitle() != null) {
            List<Ranking> currentList = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                userId, rankingState.getBookshelf(), rankingState.getCategory()
            );
            Ranking compRanking = currentList.get(rankingState.getBinarySearch().getCompareToIndex());
            model.addAttribute("comparisonBookTitle", compRanking.getBook().getTitle());
            model.addAttribute("comparisonBookAuthor", compRanking.getBook().getAuthor());
            model.addAttribute("comparisonBookWorkOlid", compRanking.getBook().getWorkOlid());
            model.addAttribute("comparisonBookCoverId", compRanking.getBook().getCoverId());
            model.addAttribute("comparisonBookEditionOlid", compRanking.getBook().getEditionOlid());
            if (rankingState.getBookIdentity().getWorkOlid() != null) {
                bookRepository.findByWorkOlid(rankingState.getBookIdentity().getWorkOlid())
                    .ifPresent(b -> {
                        model.addAttribute("rankingBookCoverId", b.getCoverId());
                        model.addAttribute("rankingBookEditionOlid", b.getEditionOlid());
                    });
            }
        }

        return null; // no redirect needed
    }

}
