package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.User;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationTest extends BaseIntegrationTest {

    @Test
    void recsPageLoads() throws Exception {
        createOAuthUser("rec1", "oauth-rec-1");

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-1")))
                .andExpect(status().isOk())
                .andExpect(view().name("recs"))
                .andExpect(model().attributeExists("fictionRecs"))
                .andExpect(model().attributeExists("nonfictionRecs"));
    }

    @Test
    void recsShowsFictionBooksFromAnyUser() throws Exception {
        createOAuthUser("rec2", "oauth-rec-2");
        User other = createOAuthUser("rec3", "oauth-rec-3");

        Book otherBook = createVerifiedBook("OL200W", "Great Book", "Good Author");
        addRanking(other.getId(), otherBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-2")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Great Book")));
    }

    @Test
    void recsExcludesBookUserAlreadyHas() throws Exception {
        User me = createOAuthUser("rec4", "oauth-rec-4");
        User other = createOAuthUser("rec5", "oauth-rec-5");

        Book sharedBook = createVerifiedBook("OL300W", "Already Read", "Some Author");
        addRanking(other.getId(), sharedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(me.getId(), sharedBook, Bookshelf.FICTION, BookCategory.OK, 0);

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-4")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Already Read"))));
    }

    @Test
    void recsKeepsFictionAndNonfictionSeparate() throws Exception {
        createOAuthUser("rec6", "oauth-rec-6");
        User other = createOAuthUser("rec7", "oauth-rec-7");

        Book fictionBook = createVerifiedBook("OL400W", "Fiction Only", "Author A");
        Book nonfictionBook = createVerifiedBook("OL500W", "Nonfiction Only", "Author B");
        addRanking(other.getId(), fictionBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(other.getId(), nonfictionBook, Bookshelf.NONFICTION, BookCategory.LIKED, 0);

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-6")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fictionRecs",
                        org.hamcrest.Matchers.hasSize(1)))
                .andExpect(model().attribute("nonfictionRecs",
                        org.hamcrest.Matchers.hasSize(1)));
    }
}
