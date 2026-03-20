package armchair.service;

import armchair.dto.BookInfo;
import armchair.dto.BookLists;
import armchair.dto.UserBookRank;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookRanking;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.CuratedRankingRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RankingServiceTest {

    private RankingService service;
    private RankingRepository rankingRepository;
    private BookService bookService;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        service = new RankingService();
        rankingRepository = mock(RankingRepository.class);
        bookService = mock(BookService.class);
        userRepository = mock(UserRepository.class);
        CuratedRankingRepository curatedRankingRepository = mock(CuratedRankingRepository.class);
        ReflectionTestUtils.setField(service, "rankingRepository", rankingRepository);
        ReflectionTestUtils.setField(service, "bookService", bookService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "curatedRankingRepository", curatedRankingRepository);
    }

    private Ranking createRanking(Long id, String title, String author, Bookshelf shelf,
                                   BookCategory category, int position) {
        User user = new User("testuser");
        user.setId(1L);
        Book book = new Book(null, null, title, author, null, null);
        book.setId(id);
        Ranking ranking = new Ranking(user, book, shelf, category, position);
        ranking.setId(id);
        return ranking;
    }

    private Ranking createRanking(Long id, String workOlid, String title, String author,
                                   Bookshelf shelf, BookCategory category, int position) {
        User user = new User("testuser");
        user.setId(1L);
        Book book = new Book(workOlid, null, title, author, null, null);
        book.setId(id);
        Ranking ranking = new Ranking(user, book, shelf, category, position);
        ranking.setId(id);
        return ranking;
    }

    @Nested
    class FetchAllRankingsGrouped {

        @Test
        void groupsByBookshelfAndCategory() {
            List<Ranking> rankings = List.of(
                createRanking(1L, "Book1", "Author1", Bookshelf.FICTION, BookCategory.LIKED, 0),
                createRanking(2L, "Book2", "Author2", Bookshelf.FICTION, BookCategory.LIKED, 1),
                createRanking(3L, "Book3", "Author3", Bookshelf.FICTION, BookCategory.DISLIKED, 0),
                createRanking(4L, "Book4", "Author4", Bookshelf.NONFICTION, BookCategory.OK, 0)
            );
            when(rankingRepository.findByUserId(1L)).thenReturn(rankings);

            var grouped = service.fetchAllRankingsGrouped(1L);

            assertThat(grouped.get(Bookshelf.FICTION).get(BookCategory.LIKED)).hasSize(2);
            assertThat(grouped.get(Bookshelf.FICTION).get(BookCategory.DISLIKED)).hasSize(1);
            assertThat(grouped.get(Bookshelf.NONFICTION).get(BookCategory.OK)).hasSize(1);
        }

        @Test
        void sortsRankingsByPosition() {
            Ranking r1 = createRanking(1L, "Book1", "A", Bookshelf.FICTION, BookCategory.LIKED, 2);
            Ranking r2 = createRanking(2L, "Book2", "A", Bookshelf.FICTION, BookCategory.LIKED, 0);
            Ranking r3 = createRanking(3L, "Book3", "A", Bookshelf.FICTION, BookCategory.LIKED, 1);
            when(rankingRepository.findByUserId(1L)).thenReturn(List.of(r1, r2, r3));

            var grouped = service.fetchAllRankingsGrouped(1L);
            List<Ranking> liked = grouped.get(Bookshelf.FICTION).get(BookCategory.LIKED);

            assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book2");
            assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book3");
            assertThat(liked.get(2).getBook().getTitle()).isEqualTo("Book1");
        }

        @Test
        void returnsEmptyMapForNoRankings() {
            when(rankingRepository.findByUserId(1L)).thenReturn(List.of());

            var grouped = service.fetchAllRankingsGrouped(1L);

            assertThat(grouped).isEmpty();
        }

        @Test
        void handlesNullPosition() {
            Ranking r1 = createRanking(1L, "Book1", "A", Bookshelf.FICTION, BookCategory.LIKED, 1);
            Ranking r2 = createRanking(2L, "Book2", "A", Bookshelf.FICTION, BookCategory.LIKED, 0);
            // Set null position on r2 to test the null-safe comparator
            r2.setPosition(null);
            when(rankingRepository.findByUserId(1L)).thenReturn(List.of(r1, r2));

            var grouped = service.fetchAllRankingsGrouped(1L);
            List<Ranking> liked = grouped.get(Bookshelf.FICTION).get(BookCategory.LIKED);

            // null position treated as 0, so should come first
            assertThat(liked.get(0).getPosition()).isNull();
            assertThat(liked.get(1).getPosition()).isEqualTo(1);
        }
    }

    @Nested
    class GetRankings {

        @Test
        void returnsRankingsForMatchingShelfAndCategory() {
            Ranking r1 = createRanking(1L, "Book1", "A", Bookshelf.FICTION, BookCategory.LIKED, 0);
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            grouped.computeIfAbsent(Bookshelf.FICTION, k -> new HashMap<>())
                   .put(BookCategory.LIKED, List.of(r1));

            List<Ranking> result = service.getRankings(grouped, Bookshelf.FICTION, BookCategory.LIKED);

            assertThat(result).hasSize(1);
        }

        @Test
        void returnsEmptyListForMissingBookshelf() {
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();

            List<Ranking> result = service.getRankings(grouped, Bookshelf.FICTION, BookCategory.LIKED);

            assertThat(result).isEmpty();
        }

        @Test
        void returnsEmptyListForMissingCategory() {
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            grouped.put(Bookshelf.FICTION, new HashMap<>());

            List<Ranking> result = service.getRankings(grouped, Bookshelf.FICTION, BookCategory.LIKED);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class GetBookLists {

        @Test
        void splitsRankingsIntoCategories() {
            Ranking liked = createRanking(1L, "OL1W", "Liked Book", "A", Bookshelf.FICTION, BookCategory.LIKED, 0);
            Ranking ok = createRanking(2L, "OL2W", "OK Book", "B", Bookshelf.FICTION, BookCategory.OK, 0);
            Ranking disliked = createRanking(3L, "OL3W", "Bad Book", "C", Bookshelf.FICTION, BookCategory.DISLIKED, 0);
            Ranking unranked = createRanking(4L, "OL4W", "New Book", "D", Bookshelf.FICTION, BookCategory.UNRANKED, 0);

            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            Map<BookCategory, List<Ranking>> categories = new HashMap<>();
            categories.put(BookCategory.LIKED, List.of(liked));
            categories.put(BookCategory.OK, List.of(ok));
            categories.put(BookCategory.DISLIKED, List.of(disliked));
            categories.put(BookCategory.UNRANKED, List.of(unranked));
            grouped.put(Bookshelf.FICTION, categories);

            BookLists result = service.getBookLists(Bookshelf.FICTION, grouped);

            assertThat(result.liked()).hasSize(1);
            assertThat(result.liked().get(0).title()).isEqualTo("Liked Book");
            assertThat(result.ok()).hasSize(1);
            assertThat(result.disliked()).hasSize(1);
            assertThat(result.unranked()).hasSize(1);
        }

        @Test
        void returnsEmptyListsForEmptyGrouped() {
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();

            BookLists result = service.getBookLists(Bookshelf.FICTION, grouped);

            assertThat(result.liked()).isEmpty();
            assertThat(result.ok()).isEmpty();
            assertThat(result.disliked()).isEmpty();
            assertThat(result.unranked()).isEmpty();
        }
    }

    @Nested
    class ToBookInfo {

        @Test
        void convertsBookRankingToBookInfo() {
            Ranking ranking = createRanking(1L, "OL1W", "Dune", "Frank Herbert",
                Bookshelf.FICTION, BookCategory.LIKED, 0);
            ranking.setReview("Great book");
            ranking.getBook().setFirstPublishYear(1965);
            ranking.getBook().setCoverId(789);
            ranking.getBook().setEditionOlid("OL1M");

            BookInfo info = RankingService.toBookInfo(ranking);

            assertThat(info.id()).isEqualTo(1L);
            assertThat(info.workOlid()).isEqualTo("OL1W");
            assertThat(info.editionOlid()).isEqualTo("OL1M");
            assertThat(info.title()).isEqualTo("Dune");
            assertThat(info.author()).isEqualTo("Frank Herbert");
            assertThat(info.review()).isEqualTo("Great book");
            assertThat(info.firstPublishYear()).isEqualTo(1965);
            assertThat(info.coverId()).isEqualTo(789);
        }
    }

    @Nested
    class BuildUserBooksMap {

        @Test
        void mapsVerifiedBooksByWorkOlid() {
            Ranking r = createRanking(1L, "OL1W", "Dune", "Frank Herbert",
                Bookshelf.FICTION, BookCategory.LIKED, 0);
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            grouped.computeIfAbsent(Bookshelf.FICTION, k -> new HashMap<>())
                   .put(BookCategory.LIKED, List.of(r));

            Map<String, UserBookRank> result = service.buildUserBooksMap(grouped);

            assertThat(result).containsKey("OL1W");
            UserBookRank ubr = result.get("OL1W");
            assertThat(ubr.rank()).isEqualTo(1);
            assertThat(ubr.category()).isEqualTo("liked");
        }

        @Test
        void assignsSequentialRanksAcrossCategories() {
            Ranking liked = createRanking(1L, "OL1W", "Book1", "A", Bookshelf.FICTION, BookCategory.LIKED, 0);
            Ranking ok = createRanking(2L, "OL2W", "Book2", "B", Bookshelf.FICTION, BookCategory.OK, 0);

            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            Map<BookCategory, List<Ranking>> categories = new HashMap<>();
            categories.put(BookCategory.LIKED, List.of(liked));
            categories.put(BookCategory.OK, List.of(ok));
            grouped.put(Bookshelf.FICTION, categories);

            Map<String, UserBookRank> result = service.buildUserBooksMap(grouped);

            assertThat(result.get("OL1W").rank()).isEqualTo(1);
            assertThat(result.get("OL2W").rank()).isEqualTo(2);
        }

        @Test
        void wantToReadBooksHaveZeroRank() {
            Ranking wtr = createRanking(1L, "OL1W", "WTR Book", "A",
                Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            grouped.computeIfAbsent(Bookshelf.WANT_TO_READ, k -> new HashMap<>())
                   .put(BookCategory.UNRANKED, List.of(wtr));

            Map<String, UserBookRank> result = service.buildUserBooksMap(grouped);

            assertThat(result.get("OL1W").rank()).isEqualTo(0);
            assertThat(result.get("OL1W").category()).isEqualTo("want_to_read");
        }

        @Test
        void unrankedBooksHaveZeroRank() {
            Ranking unranked = createRanking(1L, "OL1W", "Unranked Book", "A",
                Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            grouped.computeIfAbsent(Bookshelf.UNRANKED, k -> new HashMap<>())
                   .put(BookCategory.UNRANKED, List.of(unranked));

            Map<String, UserBookRank> result = service.buildUserBooksMap(grouped);

            assertThat(result.get("OL1W").rank()).isEqualTo(0);
            assertThat(result.get("OL1W").category()).isEqualTo("unranked");
        }

        @Test
        void skipsBookWithoutWorkOlid() {
            Ranking r = createRanking(1L, "Unverified", "Author",
                Bookshelf.FICTION, BookCategory.LIKED, 0);
            // book has null workOlid
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            grouped.computeIfAbsent(Bookshelf.FICTION, k -> new HashMap<>())
                   .put(BookCategory.LIKED, List.of(r));

            Map<String, UserBookRank> result = service.buildUserBooksMap(grouped);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class CountBooksByBookshelf {

        @Test
        void countsAcrossCategories() {
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();
            Map<BookCategory, List<Ranking>> categories = new HashMap<>();
            categories.put(BookCategory.LIKED, List.of(
                createRanking(1L, "B1", "A", Bookshelf.FICTION, BookCategory.LIKED, 0),
                createRanking(2L, "B2", "A", Bookshelf.FICTION, BookCategory.LIKED, 1)
            ));
            categories.put(BookCategory.OK, List.of(
                createRanking(3L, "B3", "A", Bookshelf.FICTION, BookCategory.OK, 0)
            ));
            grouped.put(Bookshelf.FICTION, categories);

            long count = service.countBooksByBookshelf(grouped, Bookshelf.FICTION);

            assertThat(count).isEqualTo(3);
        }

        @Test
        void returnsZeroForMissingBookshelf() {
            Map<Bookshelf, Map<BookCategory, List<Ranking>>> grouped = new HashMap<>();

            long count = service.countBooksByBookshelf(grouped, Bookshelf.FICTION);

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    class InsertBookAtPosition {

        @Test
        void shiftsExistingRankingsAndInsertsNew() {
            User user = new User("testuser");
            user.setId(1L);
            Book book = new Book("OL1W", null, "New Book", "Author", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), any(), any(), any(), any())).thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);
            when(userRepository.getReferenceById(1L)).thenReturn(user);

            // Existing rankings at positions 0, 1, 2
            Ranking r0 = createRanking(1L, "Book0", "A", Bookshelf.FICTION, BookCategory.LIKED, 0);
            Ranking r1 = createRanking(2L, "Book1", "A", Bookshelf.FICTION, BookCategory.LIKED, 1);
            Ranking r2 = createRanking(3L, "Book2", "A", Bookshelf.FICTION, BookCategory.LIKED, 2);
            when(rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                1L, Bookshelf.FICTION, BookCategory.LIKED))
                .thenReturn(new ArrayList<>(List.of(r0, r1, r2)));

            service.insertBookAtPosition("OL1W", "New Book", "Author", null,
                Bookshelf.FICTION, BookCategory.LIKED, 1, 1L, null);

            // Positions 1 and 2 should shift to 2 and 3
            assertThat(r1.getPosition()).isEqualTo(2);
            assertThat(r2.getPosition()).isEqualTo(3);
            // Position 0 stays the same
            assertThat(r0.getPosition()).isEqualTo(0);
        }

        @Test
        void skipsInsertWhenBookAlreadyRanked() {
            Book book = new Book("OL1W", null, "Dune", "Author", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), any(), any(), any(), any())).thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(true);

            service.insertBookAtPosition("OL1W", "Dune", "Author", null,
                Bookshelf.FICTION, BookCategory.LIKED, 0, 1L, null);

            // Should not create a new ranking
            verify(rankingRepository).existsByUserIdAndBookId(1L, 10L);
        }
    }

    @Nested
    class FindRankingForUser {

        @Test
        void returnsRankingWhenOwned() {
            User user = new User("testuser");
            user.setId(1L);
            Ranking ranking = new Ranking(user, new Book(), Bookshelf.FICTION, BookCategory.LIKED, 0);
            ranking.setId(5L);
            when(rankingRepository.findById(5L)).thenReturn(Optional.of(ranking));

            Ranking result = service.findRankingForUser(5L, 1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(5L);
        }

        @Test
        void returnsNullWhenNotFound() {
            when(rankingRepository.findById(5L)).thenReturn(Optional.empty());

            assertThat(service.findRankingForUser(5L, 1L)).isNull();
        }

        @Test
        void returnsNullWhenOwnedByDifferentUser() {
            User otherUser = new User("other");
            otherUser.setId(2L);
            Ranking ranking = new Ranking(otherUser, new Book(), Bookshelf.FICTION, BookCategory.LIKED, 0);
            ranking.setId(5L);
            when(rankingRepository.findById(5L)).thenReturn(Optional.of(ranking));

            assertThat(service.findRankingForUser(5L, 1L)).isNull();
        }
    }

    @Nested
    class DeleteRankingAndCloseGap {

        @Test
        void deletesRankingAndClosesGap() {
            User user = new User("testuser");
            user.setId(1L);
            Ranking ranking = new Ranking(user, new Book(), Bookshelf.FICTION, BookCategory.LIKED, 3);
            ranking.setId(5L);

            service.deleteRankingAndCloseGap(1L, ranking);

            verify(rankingRepository).delete(ranking);
            verify(rankingRepository).decrementPositionsAbove(1L, Bookshelf.FICTION, BookCategory.LIKED, 3);
        }
    }
}
