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

import java.util.Map;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

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
     * Create an OAuth user in the DB and return it (defaults to Google provider).
     * Use with oauthUser(subject) or oauthUser(subject, provider).
     */
    protected User createOAuthUser(String username, String oauthSubject) {
        return createOAuthUser(username, oauthSubject, "google");
    }

    protected User createOAuthUser(String username, String oauthSubject, String provider) {
        User user = new User(username, oauthSubject, provider);
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
        return bookRepository.save(new Book(workOlid, null, title, author, null, null));
    }

    /**
     * Create an unverified book (null workOlid).
     */
    protected Book createUnverifiedBook(String title, String author) {
        return bookRepository.save(new Book(null, null, title, author, null, null));
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
     * Create filler ranked books so a user meets the MIN_RANKED_BOOKS_FOR_RECS threshold.
     */
    protected void addFillerRankings(Long userId, Bookshelf bookshelf, int count) {
        for (int i = 0; i < count; i++) {
            Book book = createVerifiedBook("OL_FILLER_" + userId + "_" + bookshelf + "_" + i + "W",
                    "Filler Book " + i, "Filler Author " + i);
            addRanking(userId, book, bookshelf, BookCategory.LIKED, i);
        }
    }

    /**
     * Helper to get oauth2Login post-processor for a given OAuth subject (defaults to Google).
     */
    protected static org.springframework.test.web.servlet.request.RequestPostProcessor oauthUser(String oauthSubject) {
        return oauthUser(oauthSubject, "google");
    }

    protected static org.springframework.test.web.servlet.request.RequestPostProcessor oauthUser(String oauthSubject, String provider) {
        Map<String, Object> attributes;
        String nameAttributeKey;
        if ("github".equals(provider)) {
            attributes = Map.of("id", Integer.parseInt(oauthSubject), "login", "testuser");
            nameAttributeKey = "login";
        } else {
            attributes = Map.of("sub", oauthSubject);
            nameAttributeKey = "sub";
        }
        OAuth2User oauth2User = new DefaultOAuth2User(
            java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")),
            attributes,
            nameAttributeKey
        );
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
            oauth2User, oauth2User.getAuthorities(), provider
        );
        return authentication(token);
    }
}
