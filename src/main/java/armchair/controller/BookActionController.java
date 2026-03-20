package armchair.controller;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.service.BookService;
import armchair.service.RankingService;
import armchair.service.SessionStateManager;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import static armchair.controller.ControllerUtils.*;

@Controller
public class BookActionController extends BaseController {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private BookService bookService;

    @Autowired
    private SessionStateManager sessionState;

    @PostMapping("/start-rerank")
    public String startRerank(@RequestParam String bookshelf, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        Bookshelf bookshlf;
        try {
            bookshlf = Bookshelf.fromString(bookshelf);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, bookshlf, null);
        rankingState.setMode(RankingMode.RE_RANK);
        sessionState.saveRankingState(session, rankingState);
        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/select-rerank-book")
    public String selectRerankBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.RE_RANK) {
            return "redirect:/my-books";
        }

        rankingState.getBookIdentity().setBookInfo(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor());
        sessionState.saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return "redirect:/my-books";
    }

    @PostMapping("/start-remove")
    public String startRemove(@RequestParam String bookshelf, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        Bookshelf bookshlf;
        try {
            bookshlf = Bookshelf.fromString(bookshelf);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, bookshlf, null);
        rankingState.setMode(RankingMode.REMOVE);
        sessionState.saveRankingState(session, rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/start-remove-wtr")
    public String startRemoveWantToRead(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        rankingState.setMode(RankingMode.REMOVE);
        sessionState.saveRankingState(session, rankingState);
        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @Transactional
    @PostMapping("/select-remove-wtr-book")
    public String selectRemoveWantToReadBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REMOVE || rankingState.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        sessionState.clearRankingState(session);
        sessionState.clearSearchAndResolveState(session);

        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @PostMapping("/direct-review")
    public String directReview(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
        RankingState rankingState = new RankingState(null, null, null, ranking.getBookshelf(), null);
        rankingState.setBookIdBeingReviewed(bookId);
        rankingState.setMode(RankingMode.REVIEW);
        sessionState.saveRankingState(session, rankingState);

        return "redirect:/review/" + bookId;
    }

    @Transactional
    @PostMapping("/direct-rerank")
    public String directRerank(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        rankingService.restoreAbandonedBook(userId, sessionState.getRankingState(session));
        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), ranking.getBookshelf(), null);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingState.getRestoration().setOriginalCategory(ranking.getCategory());
        rankingState.getRestoration().setOriginalPosition(ranking.getPosition());
        rankingState.setMode(RankingMode.CATEGORIZE);
        sessionState.saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return "redirect:/rank/categorize";
    }

    @Transactional
    @PostMapping("/direct-remove")
    public String directRemove(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
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
    @PostMapping("/select-remove-book")
    public String selectRemoveBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REMOVE) {
            return "redirect:/my-books";
        }

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        sessionState.clearRankingState(session);
        sessionState.clearSearchAndResolveState(session);

        return "redirect:/my-books";
    }

    @PostMapping("/start-review")
    public String startReview(@RequestParam String bookshelf, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }
        Bookshelf bookshlf;
        try {
            bookshlf = Bookshelf.fromString(bookshelf);
        } catch (IllegalArgumentException e) {
            return "redirect:/my-books";
        }
        RankingState rankingState = new RankingState(null, null, null, bookshlf, null);
        rankingState.setMode(RankingMode.REVIEW);
        sessionState.saveRankingState(session, rankingState);
        return "redirect:/my-books";
    }

    @PostMapping("/select-review-book")
    public String selectReviewBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REVIEW) {
            return "redirect:/my-books";
        }

        rankingState.setBookIdBeingReviewed(bookId);
        sessionState.saveRankingState(session, rankingState);

        return "redirect:/my-books";
    }

    @PostMapping("/save-review")
    public String saveReview(@RequestParam(required = false) String review, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        RankingState rankingState = sessionState.getRankingState(session);
        if (rankingState == null || rankingState.getMode() != RankingMode.REVIEW || rankingState.getBookIdBeingReviewed() == null) {
            return "redirect:/my-books";
        }

        Ranking ranking = rankingRepository.findById(rankingState.getBookIdBeingReviewed()).orElse(null);
        if (ranking != null && ranking.getUser().getId().equals(userId)) {
            String trimmedReview = trimReview(review);
            ranking.setReview(trimmedReview);
            rankingRepository.save(ranking);
        }

        sessionState.clearRankingState(session);
        sessionState.clearSearchAndResolveState(session);

        return "redirect:/my-books";
    }

    @Transactional
    @PostMapping("/mark-as-read")
    public String markAsRead(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
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
            rankingState.setMode(RankingMode.CATEGORIZE);
        }
        sessionState.saveRankingState(session, rankingState);

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/categorize";
    }

    @Transactional
    @PostMapping("/remove-from-reading-list")
    public String removeFromReadingList(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.WANT_TO_READ) {
            return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
        }

        rankingService.deleteRankingAndCloseGap(userId, ranking);

        return "redirect:/my-books?selectedBookshelf=WANT_TO_READ";
    }

    @PostMapping("/rank-unranked-book")
    public String rankUnrankedBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.UNRANKED) {
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }

        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null);
        rankingState.setBookshelf(Bookshelf.UNRANKED);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingState.setUnrankedRankingId(ranking.getId());
        boolean needsResolve = ranking.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.CATEGORIZE);
        }
        sessionState.saveRankingState(session, rankingState);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/categorize";
    }

    @PostMapping("/want-to-read-unranked-book")
    public String wantToReadUnrankedBook(@RequestParam Long bookId, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null || ranking.getBookshelf() != Bookshelf.UNRANKED) {
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }

        RankingState rankingState = new RankingState(ranking.getBook().getWorkOlid(), ranking.getBook().getTitle(), ranking.getBook().getAuthor(), null, null);
        rankingState.setBookshelf(Bookshelf.UNRANKED);
        rankingState.setReviewBeingRanked(ranking.getReview());
        rankingState.setWantToRead(true);
        rankingState.setUnrankedRankingId(ranking.getId());
        boolean needsResolve = ranking.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.CATEGORIZE);
        }
        sessionState.saveRankingState(session, rankingState);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/categorize";
    }

    @PostMapping("/rank-all")
    public String rankAll(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        return startNextUnrankedBook(userId, null, session);
    }

    String startNextUnrankedBook(Long userId, Bookshelf lastRankedBookshelf, HttpSession session) {
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
        rankingState.setUnrankedRankingId(nextBook.getId());
        boolean needsResolve = nextBook.getBook().getWorkOlid() == null;
        if (needsResolve) {
            rankingState.setMode(RankingMode.RESOLVE);
        } else {
            rankingState.setMode(RankingMode.CATEGORIZE);
        }
        sessionState.saveRankingState(session, rankingState);

        return needsResolve ? "redirect:/resolve" : "redirect:/rank/categorize";
    }

    @Transactional
    @PostMapping("/update-book-edition")
    public String updateBookEdition(@RequestParam Long bookId,
                                    @RequestParam String editionOlid,
                                    @RequestParam(required = false) String isbn13,
                                    @RequestParam(required = false) Integer coverId,
                                    @RequestParam(required = false) String editionTitle) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        Ranking ranking = rankingService.findRankingForUser(bookId, userId);
        if (ranking == null) {
            return "redirect:/my-books";
        }

        Book book = ranking.getBook();
        book.setEditionOlid(editionOlid);
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

        String selectedBookshelf = ranking.getBookshelf().name();
        return "redirect:/my-books?selectedBookshelf=" + selectedBookshelf;
    }
}
