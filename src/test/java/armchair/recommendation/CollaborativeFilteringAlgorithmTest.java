package armchair.recommendation;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborativeFilteringAlgorithmTest {

    @Mock
    private RankingRepository rankingRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private UserRepository userRepository;

    private CollaborativeFilteringAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        algorithm = new CollaborativeFilteringAlgorithm(rankingRepository, bookRepository, userRepository);
    }

    private static Book book(long id, String title) {
        Book b = new Book(null, null, title, "Author", null, null);
        b.setId(id);
        return b;
    }

    private static Ranking ranking(Long userId, Book book, Bookshelf bookshelf, BookCategory category, int position) {
        return new Ranking(userId, book, bookshelf, category, position);
    }

    // --- computeSimilarity tests ---

    @Nested
    class ComputeSimilarity {

        @Test
        void identicalScoresReturnOne() {
            Map<Long, Double> scores = Map.of(1L, 0.8, 2L, -0.5, 3L, 0.3);
            // Cosine similarity of identical vectors is 1.0
            // With 3 overlapping books and threshold 5: confidence = 3/5 = 0.6
            double sim = algorithm.computeSimilarity(scores, scores);
            assertEquals(0.6, sim, 1e-9);
        }

        @Test
        void identicalScoresAtThresholdReturnOne() {
            // 5 overlapping books = full confidence
            Map<Long, Double> scores = Map.of(1L, 0.8, 2L, -0.5, 3L, 0.3, 4L, 0.1, 5L, -0.2);
            double sim = algorithm.computeSimilarity(scores, scores);
            assertEquals(1.0, sim, 1e-9);
        }

        @Test
        void aboveThresholdStillCapsAtOne() {
            Map<Long, Double> scores = Map.of(1L, 0.8, 2L, -0.5, 3L, 0.3, 4L, 0.1, 5L, -0.2, 6L, 0.7);
            double sim = algorithm.computeSimilarity(scores, scores);
            // 6 overlapping, confidence = min(6,5)/5 = 1.0, cosine = 1.0
            assertEquals(1.0, sim, 1e-9);
        }

        @Test
        void disjointSetsReturnZero() {
            Map<Long, Double> a = Map.of(1L, 0.5, 2L, 0.3);
            Map<Long, Double> b = Map.of(3L, 0.5, 4L, 0.3);
            assertEquals(0.0, algorithm.computeSimilarity(a, b));
        }

        @Test
        void emptyScoresReturnZero() {
            Map<Long, Double> empty = Map.of();
            Map<Long, Double> some = Map.of(1L, 0.5);
            assertEquals(0.0, algorithm.computeSimilarity(empty, some));
            assertEquals(0.0, algorithm.computeSimilarity(some, empty));
            assertEquals(0.0, algorithm.computeSimilarity(empty, empty));
        }

        @Test
        void oppositeScoresReturnNegative() {
            // Vectors pointing in opposite directions: cosine = -1
            Map<Long, Double> a = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0, 4L, 1.0, 5L, 1.0);
            Map<Long, Double> b = Map.of(1L, -1.0, 2L, -1.0, 3L, -1.0, 4L, -1.0, 5L, -1.0);
            double sim = algorithm.computeSimilarity(a, b);
            assertEquals(-1.0, sim, 1e-9);
        }

        @Test
        void orthogonalVectorsReturnZero() {
            // (1, 0) and (0, 1) are orthogonal => cosine = 0
            Map<Long, Double> a = Map.of(1L, 1.0, 2L, 0.0);
            Map<Long, Double> b = Map.of(1L, 0.0, 2L, 1.0);
            // normA = 1, normB = 1, dot = 0 => cosine = 0
            // But one vector has a zero norm component... let me check:
            // a = {1: 1.0, 2: 0.0}, b = {1: 0.0, 2: 1.0}
            // dot = 1*0 + 0*1 = 0, normA = 1, normB = 1
            double sim = algorithm.computeSimilarity(a, b);
            assertEquals(0.0, sim, 1e-9);
        }

        @Test
        void partialOverlapUsesOnlySharedBooks() {
            // a has books 1,2,3; b has books 2,3,4
            // overlap is books 2,3
            Map<Long, Double> a = Map.of(1L, 0.9, 2L, 0.5, 3L, -0.3);
            Map<Long, Double> b = Map.of(2L, 0.5, 3L, -0.3, 4L, 0.7);
            // dot = 0.5*0.5 + (-0.3)*(-0.3) = 0.25 + 0.09 = 0.34
            // normA = 0.25 + 0.09 = 0.34, normB = 0.25 + 0.09 = 0.34
            // cosine = 0.34 / sqrt(0.34)*sqrt(0.34) = 0.34/0.34 = 1.0
            // confidence = min(2,5)/5 = 0.4
            double sim = algorithm.computeSimilarity(a, b);
            assertEquals(0.4, sim, 1e-9);
        }

        @Test
        void confidenceDiscountScalesLinearly() {
            // Same vectors, but vary overlap size to check confidence scaling
            Map<Long, Double> one = Map.of(1L, 1.0);
            double sim1 = algorithm.computeSimilarity(one, one);
            // cosine=1, confidence=1/5=0.2
            assertEquals(0.2, sim1, 1e-9);

            Map<Long, Double> two = Map.of(1L, 1.0, 2L, 0.5);
            double sim2 = algorithm.computeSimilarity(two, two);
            // cosine=1, confidence=2/5=0.4
            assertEquals(0.4, sim2, 1e-9);
        }

        @Test
        void zeroNormVectorReturnsZero() {
            // All scores are 0 -> norm is 0
            Map<Long, Double> zeros = Map.of(1L, 0.0, 2L, 0.0);
            Map<Long, Double> nonzero = Map.of(1L, 0.5, 2L, 0.5);
            assertEquals(0.0, algorithm.computeSimilarity(zeros, nonzero));
        }
    }

    // --- computeUserScores tests ---

    @Nested
    class ComputeUserScores {

        @Test
        void emptyRankingsReturnEmptyMap() {
            Map<Long, Double> scores = algorithm.computeUserScores(List.of(), false);
            assertTrue(scores.isEmpty());
        }

        @Test
        void curatedUserGetsFixedScore() {
            Book b1 = book(1L, "Book 1");
            Book b2 = book(2L, "Book 2");
            List<Ranking> rankings = List.of(
                    ranking(1L, b1, Bookshelf.FICTION, BookCategory.LIKED, 0),
                    ranking(1L, b2, Bookshelf.FICTION, BookCategory.DISLIKED, 1)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, true);
            assertEquals(0.75, scores.get(1L));
            assertEquals(0.75, scores.get(2L));
        }

        @Test
        void unrankedBooksAreExcluded() {
            Book b1 = book(1L, "Ranked");
            Book b2 = book(2L, "Unranked");
            List<Ranking> rankings = List.of(
                    ranking(1L, b1, Bookshelf.FICTION, BookCategory.LIKED, 0),
                    ranking(1L, b2, Bookshelf.FICTION, BookCategory.UNRANKED, 1)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            assertTrue(scores.containsKey(1L));
            assertFalse(scores.containsKey(2L));
        }

        @Test
        void singleLikedBookGetsPointSevenFive() {
            Book b = book(1L, "Only Book");
            List<Ranking> rankings = List.of(
                    ranking(1L, b, Bookshelf.FICTION, BookCategory.LIKED, 0)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            // Single LIKED book: n=1, computeScore returns 0.75
            assertEquals(0.75, scores.get(1L), 1e-9);
        }

        @Test
        void singleDislikedBookGetsNegativePointSevenFive() {
            Book b = book(1L, "Bad Book");
            List<Ranking> rankings = List.of(
                    ranking(1L, b, Bookshelf.FICTION, BookCategory.DISLIKED, 0)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            assertEquals(-0.75, scores.get(1L), 1e-9);
        }

        @Test
        void singleOkBookGetsZero() {
            Book b = book(1L, "Ok Book");
            List<Ranking> rankings = List.of(
                    ranking(1L, b, Bookshelf.FICTION, BookCategory.OK, 0)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            assertEquals(0.0, scores.get(1L), 1e-9);
        }

        @Test
        void likedCategoryScoreRange() {
            // Two LIKED books: worst gets 0.5, best gets 1.0
            // Rankings ordered by position asc (position 0 = best)
            Book best = book(1L, "Best");
            Book worst = book(2L, "Worst");
            List<Ranking> rankings = List.of(
                    ranking(1L, best, Bookshelf.FICTION, BookCategory.LIKED, 0),
                    ranking(1L, worst, Bookshelf.FICTION, BookCategory.LIKED, 1)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            // n=2, best is idx=0 -> i=(2-1)-0=1 -> 0.5 + 0.5*1/1 = 1.0
            assertEquals(1.0, scores.get(1L), 1e-9);
            // worst is idx=1 -> i=(2-1)-1=0 -> 0.5 + 0.5*0/1 = 0.5
            assertEquals(0.5, scores.get(2L), 1e-9);
        }

        @Test
        void dislikedCategoryScoreRange() {
            // Two DISLIKED books: worst gets -1.0, best gets -0.5
            Book best = book(1L, "Less Bad");
            Book worst = book(2L, "Worst");
            List<Ranking> rankings = List.of(
                    ranking(1L, best, Bookshelf.FICTION, BookCategory.DISLIKED, 0),
                    ranking(1L, worst, Bookshelf.FICTION, BookCategory.DISLIKED, 1)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            // best: idx=0 -> i=1 -> -1.0 + 0.5*1/1 = -0.5
            assertEquals(-0.5, scores.get(1L), 1e-9);
            // worst: idx=1 -> i=0 -> -1.0 + 0.5*0/1 = -1.0
            assertEquals(-1.0, scores.get(2L), 1e-9);
        }

        @Test
        void okCategoryScoreRange() {
            // Two OK books: worst gets -0.5, best gets 0.5
            Book best = book(1L, "Better OK");
            Book worst = book(2L, "Worse OK");
            List<Ranking> rankings = List.of(
                    ranking(1L, best, Bookshelf.FICTION, BookCategory.OK, 0),
                    ranking(1L, worst, Bookshelf.FICTION, BookCategory.OK, 1)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            // best: idx=0 -> i=1 -> -0.5 + 1.0*1/1 = 0.5
            assertEquals(0.5, scores.get(1L), 1e-9);
            // worst: idx=1 -> i=0 -> -0.5 + 1.0*0/1 = -0.5
            assertEquals(-0.5, scores.get(2L), 1e-9);
        }

        @Test
        void mixedCategoriesScoredIndependently() {
            Book liked = book(1L, "Liked");
            Book disliked = book(2L, "Disliked");
            Book ok = book(3L, "Ok");
            List<Ranking> rankings = List.of(
                    ranking(1L, liked, Bookshelf.FICTION, BookCategory.LIKED, 0),
                    ranking(1L, ok, Bookshelf.FICTION, BookCategory.OK, 1),
                    ranking(1L, disliked, Bookshelf.FICTION, BookCategory.DISLIKED, 2)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            // Each category has n=1, so single-item formulas apply
            assertEquals(0.75, scores.get(1L), 1e-9);   // LIKED single
            assertEquals(0.0, scores.get(3L), 1e-9);    // OK single
            assertEquals(-0.75, scores.get(2L), 1e-9);  // DISLIKED single
        }

        @Test
        void threeLikedBooksScoreDistribution() {
            Book b0 = book(1L, "Best");
            Book b1 = book(2L, "Mid");
            Book b2 = book(3L, "Worst");
            List<Ranking> rankings = List.of(
                    ranking(1L, b0, Bookshelf.FICTION, BookCategory.LIKED, 0),
                    ranking(1L, b1, Bookshelf.FICTION, BookCategory.LIKED, 1),
                    ranking(1L, b2, Bookshelf.FICTION, BookCategory.LIKED, 2)
            );
            Map<Long, Double> scores = algorithm.computeUserScores(rankings, false);
            // n=3
            // b0: idx=0 -> i=2 -> 0.5 + 0.5*2/2 = 1.0
            assertEquals(1.0, scores.get(1L), 1e-9);
            // b1: idx=1 -> i=1 -> 0.5 + 0.5*1/2 = 0.75
            assertEquals(0.75, scores.get(2L), 1e-9);
            // b2: idx=2 -> i=0 -> 0.5 + 0.5*0/2 = 0.5
            assertEquals(0.5, scores.get(3L), 1e-9);
        }
    }

    // --- Full recommendation flow tests ---

    @Nested
    class Recommendations {

        private User user(long id, boolean curated) {
            User u = new User("user" + id);
            u.setId(id);
            u.setCurated(curated);
            return u;
        }

        @Test
        void emptyBookshelfReturnsRandomBooks() {
            User me = user(1L, false);
            Book random = book(99L, "Random");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of());
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L));
            when(rankingRepository.findByUserId(1L)).thenReturn(List.of());
            when(bookRepository.findRandomBooks()).thenReturn(List.of(random));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 10);
            assertEquals(1, recs.size());
            assertEquals("Random", recs.get(0).getTitle());
        }

        @Test
        void noOtherUsersReturnsRandomBooks() {
            User me = user(1L, false);
            Book myBook = book(1L, "My Book");
            Book random = book(99L, "Random");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(1L, myBook, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, myBook, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(bookRepository.findRandomBooks()).thenReturn(List.of(random));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 10);
            assertEquals(1, recs.size());
            assertEquals("Random", recs.get(0).getTitle());
        }

        @Test
        void excludesBooksUserAlreadyHas() {
            Book shared = book(1L, "Shared");
            Book unique = book(2L, "Unique");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(1L, shared, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, shared, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.FICTION))
                    .thenReturn(List.of(
                            ranking(2L, shared, Bookshelf.FICTION, BookCategory.LIKED, 0),
                            ranking(2L, unique, Bookshelf.FICTION, BookCategory.LIKED, 1)
                    ));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 10);
            assertEquals(1, recs.size());
            assertEquals("Unique", recs.get(0).getTitle());
        }

        @Test
        void similarUsersBooksRankedHigher() {
            // User 1 likes books A, B. User 2 also likes A, B and has C.
            // User 3 dislikes A, B and has D.
            // C should rank higher than D because user 2 is more similar.
            Book a = book(1L, "A");
            Book b = book(2L, "B");
            Book c = book(3L, "C");
            Book d = book(4L, "D");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());

            // User 1's rankings
            List<Ranking> myRankings = List.of(
                    ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0),
                    ranking(1L, b, Bookshelf.FICTION, BookCategory.LIKED, 1)
            );
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(myRankings);
            when(rankingRepository.findByUserId(1L)).thenReturn(myRankings);
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L, 3L));

            // User 2: likes same books + has C
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.FICTION))
                    .thenReturn(List.of(
                            ranking(2L, a, Bookshelf.FICTION, BookCategory.LIKED, 0),
                            ranking(2L, b, Bookshelf.FICTION, BookCategory.LIKED, 1),
                            ranking(2L, c, Bookshelf.FICTION, BookCategory.LIKED, 2)
                    ));

            // User 3: dislikes same books + has D
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(3L, Bookshelf.FICTION))
                    .thenReturn(List.of(
                            ranking(3L, a, Bookshelf.FICTION, BookCategory.DISLIKED, 0),
                            ranking(3L, b, Bookshelf.FICTION, BookCategory.DISLIKED, 1),
                            ranking(3L, d, Bookshelf.FICTION, BookCategory.LIKED, 2)
                    ));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 10);
            assertEquals(2, recs.size());
            assertEquals("C", recs.get(0).getTitle());
            assertEquals("D", recs.get(1).getTitle());
        }

        @Test
        void noOverlapFallbackTreatsAllSimilaritiesAsOne() {
            // User 1 has book A. User 2 has book B (no overlap).
            // With no overlap, similarity is forced to 1.0
            Book a = book(1L, "A");
            Book b = book(2L, "B");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(2L, b, Bookshelf.FICTION, BookCategory.LIKED, 0)));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 10);
            assertEquals(1, recs.size());
            assertEquals("B", recs.get(0).getTitle());
        }

        @Test
        void limitRestrictsResultCount() {
            Book a = book(1L, "A");
            Book b = book(2L, "B");
            Book c = book(3L, "C");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.FICTION))
                    .thenReturn(List.of(
                            ranking(2L, a, Bookshelf.FICTION, BookCategory.LIKED, 0),
                            ranking(2L, b, Bookshelf.FICTION, BookCategory.LIKED, 1),
                            ranking(2L, c, Bookshelf.FICTION, BookCategory.LIKED, 2)
                    ));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 1);
            assertEquals(1, recs.size());
        }

        @Test
        void curatedUserScoresAreFlat() {
            // A curated user's books all get 0.75 regardless of category/position
            User curated = user(2L, true);
            Book a = book(1L, "A");
            Book b = book(2L, "B");
            Book c = book(3L, "C");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of(curated));
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));

            // Curated user has books in different categories - shouldn't matter
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.FICTION))
                    .thenReturn(List.of(
                            ranking(2L, a, Bookshelf.FICTION, BookCategory.LIKED, 0),
                            ranking(2L, b, Bookshelf.FICTION, BookCategory.DISLIKED, 1),
                            ranking(2L, c, Bookshelf.FICTION, BookCategory.OK, 2)
                    ));

            List<Book> recs = algorithm.getFictionRecommendations(1L, 10);
            // Both b and c should be recommended with same score (0.75 * similarity)
            assertEquals(2, recs.size());
        }

        @Test
        void nonfictionUsesCorrectBookshelf() {
            Book a = book(1L, "NF A");
            Book b = book(2L, "NF B");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.NONFICTION))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.NONFICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.NONFICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.NONFICTION))
                    .thenReturn(List.of(
                            ranking(2L, a, Bookshelf.NONFICTION, BookCategory.LIKED, 0),
                            ranking(2L, b, Bookshelf.NONFICTION, BookCategory.LIKED, 1)
                    ));

            List<Book> recs = algorithm.getNonfictionRecommendations(1L, 10);
            assertEquals(1, recs.size());
            assertEquals("NF B", recs.get(0).getTitle());
        }

        @Test
        void otherUserWithOnlyUnrankedBooksThrowsNpe() {
            // BUG: When another user has only UNRANKED books, computeUserScores
            // excludes them from the scores map, but the recommendation loop
            // still iterates otherRankings and calls otherScores.get(bookId),
            // which returns null and causes a NullPointerException on unboxing.
            Book a = book(1L, "A");
            Book b = book(2L, "B");

            when(userRepository.findByIsCurated(true)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(1L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findDistinctUserIds()).thenReturn(List.of(1L, 2L));
            when(rankingRepository.findByUserId(1L))
                    .thenReturn(List.of(ranking(1L, a, Bookshelf.FICTION, BookCategory.LIKED, 0)));
            when(rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(2L, Bookshelf.FICTION))
                    .thenReturn(List.of(ranking(2L, b, Bookshelf.FICTION, BookCategory.UNRANKED, 0)));

            assertThrows(NullPointerException.class,
                    () -> algorithm.getFictionRecommendations(1L, 10));
        }
    }
}
