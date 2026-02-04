package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import armchair.repository.RankingStateRepository;
import armchair.repository.UserRepository;
import armchair.service.OpenLibraryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @MockBean
    protected OpenLibraryService openLibraryService;

    @Autowired
    protected BookRepository bookRepository;

    @Autowired
    protected RankingRepository rankingRepository;

    @Autowired
    protected RankingStateRepository rankingStateRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected FollowRepository followRepository;

    /**
     * Create an OAuth user in the DB and return it.
     * Use with oauth2Login().attributes(a -> a.put("sub", user.getOauthSubject()))
     */
    protected User createOAuthUser(String username, String oauthSubject) {
        User user = new User(username, oauthSubject);
        user.setGuest(false);
        return userRepository.save(user);
    }

    /**
     * Create a guest session. The first request using this session will
     * trigger guest user creation via getCurrentUserId().
     */
    protected MockHttpSession guestSession() {
        return new MockHttpSession();
    }

    /**
     * Create a verified book (has workOlid).
     */
    protected Book createVerifiedBook(String workOlid, String title, String author) {
        return bookRepository.save(new Book(workOlid, null, title, author, null));
    }

    /**
     * Create an unverified book (null workOlid).
     */
    protected Book createUnverifiedBook(String title, String author) {
        return bookRepository.save(new Book(null, null, title, author, null));
    }

    /**
     * Add a ranking for a user+book at the given bookshelf/category/position.
     */
    protected Ranking addRanking(Long userId, Book book, Bookshelf bookshelf, BookCategory category, int position) {
        return rankingRepository.save(new Ranking(userId, book, bookshelf, category, position));
    }

    /**
     * Add a ranking with a review.
     */
    protected Ranking addRankingWithReview(Long userId, Book book, Bookshelf bookshelf, BookCategory category, int position, String review) {
        Ranking ranking = new Ranking(userId, book, bookshelf, category, position);
        ranking.setReview(review);
        return rankingRepository.save(ranking);
    }

    /**
     * Helper to get oauth2Login post-processor for a given OAuth subject.
     */
    protected static org.springframework.test.web.servlet.request.RequestPostProcessor oauthUser(String oauthSubject) {
        return oauth2Login().attributes(attrs -> attrs.put("sub", oauthSubject));
    }
}
