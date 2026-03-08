package armchair.recommendation;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowersLikedAlgorithmTest {

    @Mock
    private RankingRepository rankingRepository;
    @Mock
    private FollowRepository followRepository;
    @Mock
    private BookRepository bookRepository;

    private FollowersLikedAlgorithm algorithm;

    private static final Long USER_ID = 1L;
    private static final Long FOLLOWED_ID_1 = 2L;
    private static final Long FOLLOWED_ID_2 = 3L;

    @BeforeEach
    void setUp() {
        algorithm = new FollowersLikedAlgorithm(rankingRepository, followRepository, bookRepository);
    }

    @Test
    void recommendsBooksFromFollowedUsers() {
        Book book = makeBook(10L, "Good Book");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(makeRanking(book, Bookshelf.FICTION, BookCategory.LIKED)));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(book);
    }

    @Test
    void excludesBooksUserAlreadyRanked() {
        Book alreadyRanked = makeBook(10L, "Already Read");
        Book notRanked = makeBook(11L, "New Book");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of(10L));
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(
                        makeRanking(alreadyRanked, Bookshelf.FICTION, BookCategory.LIKED),
                        makeRanking(notRanked, Bookshelf.FICTION, BookCategory.LIKED)
                ));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(notRanked);
    }

    @Test
    void fallsBackToRandomBooksWhenNoFollows() {
        Book randomBook = makeBook(10L, "Random Book");

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of());
        when(bookRepository.findRandomBooks()).thenReturn(List.of(randomBook));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(randomBook);
    }

    @Test
    void fallsBackToRandomBooksWhenFollowedUsersHaveNoLikedBooks() {
        Book randomBook = makeBook(10L, "Random Book");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of());
        when(bookRepository.findRandomBooks()).thenReturn(List.of(randomBook));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(randomBook);
    }

    @Test
    void deduplicatesAcrossMultipleFollowedUsers() {
        Book sharedBook = makeBook(10L, "Shared Book");
        Follow follow1 = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));
        Follow follow2 = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_2));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow1, follow2));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1, FOLLOWED_ID_2), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(
                        makeRanking(sharedBook, Bookshelf.FICTION, BookCategory.LIKED),
                        makeRanking(sharedBook, Bookshelf.FICTION, BookCategory.LIKED)
                ));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).hasSize(1);
    }

    @Test
    void onlyRecommendsLikedBooks() {
        Book likedBook = makeBook(10L, "Liked");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        // The batch query only fetches LIKED books, so OK books won't appear
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(makeRanking(likedBook, Bookshelf.FICTION, BookCategory.LIKED)));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(likedBook);
    }

    @Test
    void filtersByBookshelf() {
        Book fictionBook = makeBook(10L, "Fiction");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        // The batch query filters by bookshelf, so only fiction books appear
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(makeRanking(fictionBook, Bookshelf.FICTION, BookCategory.LIKED)));

        List<Book> fictionRecs = algorithm.getFictionRecommendations(USER_ID, 10);
        assertThat(fictionRecs).containsExactly(fictionBook);
    }

    @Test
    void nonfictionRecommendationsFilterCorrectly() {
        Book nonfictionBook = makeBook(10L, "Nonfiction");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.NONFICTION))
                .thenReturn(List.of(makeRanking(nonfictionBook, Bookshelf.NONFICTION, BookCategory.LIKED)));

        List<Book> recs = algorithm.getNonfictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(nonfictionBook);
    }

    @Test
    void respectsLimit() {
        Book book1 = makeBook(10L, "Book 1");
        Book book2 = makeBook(11L, "Book 2");
        Book book3 = makeBook(12L, "Book 3");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of());
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(
                        makeRanking(book1, Bookshelf.FICTION, BookCategory.LIKED),
                        makeRanking(book2, Bookshelf.FICTION, BookCategory.LIKED),
                        makeRanking(book3, Bookshelf.FICTION, BookCategory.LIKED)
                ));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 2);

        assertThat(recs).hasSize(2);
    }

    @Test
    void fallsBackWhenAllFollowedBookAlreadyRanked() {
        Book book = makeBook(10L, "Already Have");
        Book randomBook = makeBook(11L, "Random");
        Follow follow = new Follow(userWithId(USER_ID), userWithId(FOLLOWED_ID_1));

        when(followRepository.findByFollowerId(USER_ID)).thenReturn(List.of(follow));
        when(rankingRepository.findBookIdsByUserId(USER_ID)).thenReturn(List.of(10L));
        when(rankingRepository.findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(
                List.of(FOLLOWED_ID_1), BookCategory.LIKED, Bookshelf.FICTION))
                .thenReturn(List.of(makeRanking(book, Bookshelf.FICTION, BookCategory.LIKED)));
        when(bookRepository.findRandomBooks()).thenReturn(List.of(randomBook));

        List<Book> recs = algorithm.getFictionRecommendations(USER_ID, 10);

        assertThat(recs).containsExactly(randomBook);
    }

    private static User userWithId(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Book makeBook(Long id, String title) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthor("Author");
        return book;
    }

    private static Ranking makeRanking(Book book, Bookshelf bookshelf, BookCategory category) {
        return new Ranking(null, book, bookshelf, category, 0);
    }
}
