package armchair.service;

import armchair.dto.BookInfo;
import armchair.dto.BookLists;
import armchair.dto.UserBookRank;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookRanking;
import armchair.entity.Bookshelf;
import armchair.entity.CuratedRanking;
import armchair.entity.Ranking;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.CuratedRankingRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RankingService {

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private BookService bookService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CuratedRankingRepository curatedRankingRepository;

    public void closePositionGap(Long userId, Bookshelf bookshelf, BookCategory category, int removedPosition) {
        rankingRepository.decrementPositionsAbove(userId, bookshelf, category, removedPosition);
    }

    public void deleteRankingAndCloseGap(Long userId, Ranking ranking) {
        int removedPosition = ranking.getPosition();
        rankingRepository.delete(ranking);
        closePositionGap(userId, ranking.getBookshelf(), ranking.getCategory(), removedPosition);
    }

    public Ranking findRankingForUser(Long bookId, Long userId) {
        Ranking ranking = rankingRepository.findById(bookId).orElse(null);
        if (ranking == null || !ranking.getUser().getId().equals(userId)) {
            return null;
        }
        return ranking;
    }

    public void restoreAbandonedBook(Long userId, RankingState state) {
        if (state == null || state.getBookIdentity().getTitle() == null) return;

        Book book = bookService.findOrCreateBook(state.getBookIdentity().getWorkOlid(),
            null, state.getBookIdentity().getTitle(), state.getBookIdentity().getAuthor(), null, null);

        if (!rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
            User userRef = userRepository.getReferenceById(userId);
            if (state.getRestoration().getOriginalCategory() != null && state.getRestoration().getOriginalPosition() != null) {
                Bookshelf bookshelf = state.getBookshelf();
                BookCategory category = state.getRestoration().getOriginalCategory();
                int position = state.getRestoration().getOriginalPosition();

                List<Ranking> rankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, bookshelf, category);
                for (Ranking r : rankings) {
                    if (r.getPosition() >= position) {
                        r.setPosition(r.getPosition() + 1);
                        rankingRepository.save(r);
                    }
                }

                Ranking restored = new Ranking(userRef, book, bookshelf, category, position);
                restored.setReview(state.getReviewBeingRanked());
                rankingRepository.save(restored);
            } else {
                List<Ranking> unranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                    userId, Bookshelf.UNRANKED, BookCategory.UNRANKED);
                Ranking restored = new Ranking(userRef, book, Bookshelf.UNRANKED, BookCategory.UNRANKED, unranked.size());
                restored.setReview(state.getReviewBeingRanked());
                rankingRepository.save(restored);
            }
        }
    }

    public void deleteUnrankedRankingById(Long unrankedRankingId, Long userId) {
        if (unrankedRankingId == null) return;
        Ranking ranking = rankingRepository.findById(unrankedRankingId).orElse(null);
        if (ranking == null) return;
        if (!ranking.getUser().getId().equals(userId)) return;
        if (ranking.getBookshelf() != Bookshelf.UNRANKED) return;
        deleteRankingAndCloseGap(userId, ranking);
    }

    @Transactional
    public void insertBookAtPosition(String workOlid, String title, String author, String review,
                                     Bookshelf bookshelf, BookCategory category, int position, Long userId,
                                     Long unrankedRankingId) {
        deleteUnrankedRankingById(unrankedRankingId, userId);
        Book book = bookService.findOrCreateBook(workOlid, null, title, author, null, null);
        if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
            return;
        }

        List<Ranking> rankingsToShift = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
            userId, bookshelf, category
        );
        for (int i = rankingsToShift.size() - 1; i >= position; i--) {
            Ranking ranking = rankingsToShift.get(i);
            ranking.setPosition(ranking.getPosition() + 1);
            rankingRepository.save(ranking);
        }

        User userRef = userRepository.getReferenceById(userId);
        Ranking newRanking = new Ranking(userRef, book, bookshelf, category, position);
        newRanking.setReview(review);
        rankingRepository.save(newRanking);
    }

    public Map<Bookshelf, Map<BookCategory, List<Ranking>>> fetchAllRankingsGrouped(Long userId) {
        List<Ranking> allRankings = rankingRepository.findByUserId(userId);
        Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
        for (Ranking r : allRankings) {
            grouped.computeIfAbsent(r.getBookshelf(), k -> new HashMap<>())
                   .computeIfAbsent(r.getCategory(), k -> new ArrayList<>())
                   .add(r);
        }
        for (Map<BookCategory, List<Ranking>> byCategory : grouped.values()) {
            for (List<Ranking> rankings : byCategory.values()) {
                rankings.sort(Comparator.comparingInt(r -> r.getPosition() != null ? r.getPosition() : 0));
            }
        }
        return grouped;
    }

    public Map<Bookshelf, Map<BookCategory, List<CuratedRanking>>> fetchAllCuratedRankingsGrouped(Long curatedListId) {
        List<CuratedRanking> allRankings = curatedRankingRepository.findByCuratedListId(curatedListId);
        Map<Bookshelf, Map<BookCategory, List<CuratedRanking>>> grouped = new HashMap<>();
        for (CuratedRanking r : allRankings) {
            grouped.computeIfAbsent(r.getBookshelf(), k -> new HashMap<>())
                   .computeIfAbsent(r.getCategory(), k -> new ArrayList<>())
                   .add(r);
        }
        for (Map<BookCategory, List<CuratedRanking>> byCategory : grouped.values()) {
            for (List<CuratedRanking> rankings : byCategory.values()) {
                rankings.sort(Comparator.comparingInt(r -> r.getPosition() != null ? r.getPosition() : 0));
            }
        }
        return grouped;
    }

    public <T extends BookRanking> List<T> getRankings(Map<Bookshelf, Map<BookCategory, List<T>>> grouped,
                                     Bookshelf bookshelf, BookCategory category) {
        return grouped.getOrDefault(bookshelf, Map.of()).getOrDefault(category, List.of());
    }

    public <T extends BookRanking> BookLists getBookLists(Bookshelf bookshelf, Map<Bookshelf, Map<BookCategory, List<T>>> grouped) {
        List<BookInfo> liked = getRankings(grouped, bookshelf, BookCategory.LIKED).stream().map(RankingService::toBookInfo).toList();
        List<BookInfo> ok = getRankings(grouped, bookshelf, BookCategory.OK).stream().map(RankingService::toBookInfo).toList();
        List<BookInfo> disliked = getRankings(grouped, bookshelf, BookCategory.DISLIKED).stream().map(RankingService::toBookInfo).toList();
        List<BookInfo> unranked = getRankings(grouped, bookshelf, BookCategory.UNRANKED).stream().map(RankingService::toBookInfo).toList();
        return new BookLists(liked, ok, disliked, unranked);
    }

    public <T extends BookRanking> Map<String, UserBookRank> buildUserBooksMap(Map<Bookshelf, Map<BookCategory, List<T>>> grouped) {
        Map<String, UserBookRank> userBooks = new HashMap<>();

        for (Bookshelf bookshelf : List.of(Bookshelf.FICTION, Bookshelf.NONFICTION)) {
            int rank = 1;
            for (BookCategory category : List.of(BookCategory.LIKED, BookCategory.OK, BookCategory.DISLIKED)) {
                for (BookRanking ranking : getRankings(grouped, bookshelf, category)) {
                    UserBookRank ubr = new UserBookRank(ranking.getId(), rank, category.name().toLowerCase(), bookshelf.name());
                    putBookKeys(userBooks, ranking.getBook(), ubr);
                    rank++;
                }
            }
        }

        for (BookRanking ranking : getRankings(grouped, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED)) {
            UserBookRank ubr = new UserBookRank(ranking.getId(), 0, "want_to_read", "WANT_TO_READ");
            putBookKeys(userBooks, ranking.getBook(), ubr);
        }

        for (BookRanking ranking : getRankings(grouped, Bookshelf.UNRANKED, BookCategory.UNRANKED)) {
            UserBookRank ubr = new UserBookRank(ranking.getId(), 0, "unranked", "UNRANKED");
            putBookKeys(userBooks, ranking.getBook(), ubr);
        }

        return userBooks;
    }

    public long countBooksByBookshelf(Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped, Bookshelf bookshelf) {
        Map<BookCategory, List<Ranking>> byCategory = grouped.getOrDefault(bookshelf, Map.of());
        return byCategory.values().stream().mapToLong(List::size).sum();
    }

    public static BookInfo toBookInfo(BookRanking r) {
        return new BookInfo(r.getId(), r.getBook().getWorkOlid(), r.getBook().getEditionOlid(),
            r.getBook().getTitle(), r.getBook().getAuthor(), r.getReview(),
            r.getBook().getFirstPublishYear(), r.getBook().getCoverId());
    }

    public Ranking createWantToReadRanking(Long userId, Book book) {
        if (rankingRepository.existsByUserIdAndBookId(userId, book.getId())) {
            return rankingRepository.findByUserId(userId).stream()
                .filter(r -> r.getBook().getId().equals(book.getId()))
                .findFirst().orElse(null);
        }
        List<Ranking> wantToReadRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        int position = wantToReadRankings.size();
        User userRef = userRepository.getReferenceById(userId);
        Ranking newRanking = new Ranking(userRef, book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, position);
        return rankingRepository.save(newRanking);
    }

    public void cleanupUnverifiedBook(Long userId, Long bookId) {
        List<Ranking> userRankings = rankingRepository.findByUserId(userId).stream()
            .filter(r -> r.getBook().getId().equals(bookId))
            .toList();
        for (Ranking r : userRankings) {
            deleteRankingAndCloseGap(userId, r);
        }

        bookService.deleteIfOrphaned(bookId);
    }

    private void putBookKeys(Map<String, UserBookRank> userBooks, Book book, UserBookRank ubr) {
        if (book.getWorkOlid() != null) {
            userBooks.put(book.getWorkOlid(), ubr);
        }
    }
}
