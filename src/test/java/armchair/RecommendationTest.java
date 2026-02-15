package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.User;
import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationTest extends BaseIntegrationTest {

    @Test
    void recsPageLoads() throws Exception {
        createOAuthUser("rec1", "oauth-rec-1");

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-1")))
                .andExpect(status().isOk())
                .andExpect(view().name("recs"));
    }

    @Test
    void recsShowsBooksFromFollowedUsers() throws Exception {
        User me = createOAuthUser("rec2", "oauth-rec-2");
        User friend = createOAuthUser("rec3", "oauth-rec-3");

        followRepository.save(new Follow(me.getId(), friend.getId()));

        Book friendBook = createVerifiedBook("OL200W", "Great Book", "Good Author");
        addRanking(friend.getId(), friendBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-2")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("recs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Great Book")));
    }

    @Test
    void recsExcludesBookUserAlreadyHas() throws Exception {
        User me = createOAuthUser("rec4", "oauth-rec-4");
        User friend = createOAuthUser("rec5", "oauth-rec-5");

        followRepository.save(new Follow(me.getId(), friend.getId()));

        Book sharedBook = createVerifiedBook("OL300W", "Already Read", "Some Author");
        addRanking(friend.getId(), sharedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(me.getId(), sharedBook, Bookshelf.FICTION, BookCategory.OK, 0);

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-4")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Already Read"))));
    }
}
