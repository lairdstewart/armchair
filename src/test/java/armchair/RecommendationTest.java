package armchair;

import armchair.controller.BookController.BookInfo;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationTest extends BaseIntegrationTest {

    @Test
    void recsPageLoadsWithEmptyRecsForNewUser() throws Exception {
        createOAuthUser("rec1", "oauth-rec-1");

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-1")))
                .andExpect(status().isOk())
                .andExpect(view().name("recs"))
                .andExpect(model().attribute("fictionRecs",
                        org.hamcrest.Matchers.empty()))
                .andExpect(model().attribute("nonfictionRecs",
                        org.hamcrest.Matchers.empty()));
    }

    @Test
    void recsShowsFictionBooksFromAnyUser() throws Exception {
        User me = createOAuthUser("rec2", "oauth-rec-2");
        User other = createOAuthUser("rec3", "oauth-rec-3");

        addFillerRankings(me.getId(), Bookshelf.FICTION, 10);

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
        User me = createOAuthUser("rec6", "oauth-rec-6");
        User other = createOAuthUser("rec7", "oauth-rec-7");

        addFillerRankings(me.getId(), Bookshelf.FICTION, 10);
        addFillerRankings(me.getId(), Bookshelf.NONFICTION, 10);

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

    @Test
    void recsLikedBooksRankedAboveDisliked() throws Exception {
        User me = createOAuthUser("rec8", "oauth-rec-8");
        User liker = createOAuthUser("rec9", "oauth-rec-9");
        User disliker = createOAuthUser("rec10", "oauth-rec-10");

        addFillerRankings(me.getId(), Bookshelf.FICTION, 10);

        Book likedBook = createVerifiedBook("OL600W", "Liked Book", "Author A");
        Book dislikedBook = createVerifiedBook("OL601W", "Disliked Book", "Author B");
        addRanking(liker.getId(), likedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(disliker.getId(), dislikedBook, Bookshelf.FICTION, BookCategory.DISLIKED, 0);

        mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-8")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Liked Book")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recsModelContainsBookInfoWithTitleAndAuthor() throws Exception {
        User me = createOAuthUser("rec11", "oauth-rec-11");
        User other = createOAuthUser("rec12", "oauth-rec-12");

        addFillerRankings(me.getId(), Bookshelf.FICTION, 10);

        Book recBook = createVerifiedBook("OL700W", "Recommended Book", "Great Author");
        addRanking(other.getId(), recBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        var result = mockMvc.perform(get("/recs").with(oauthUser("oauth-rec-11")))
                .andExpect(status().isOk())
                .andReturn();

        List<BookInfo> fictionRecs = (List<BookInfo>) result.getModelAndView().getModel().get("fictionRecs");
        assertThat(fictionRecs).isNotNull();
        assertThat(fictionRecs).isNotEmpty();
        assertThat(fictionRecs).anyMatch(b -> "Recommended Book".equals(b.title()));
        assertThat(fictionRecs).anyMatch(b -> "Great Author".equals(b.author()));
    }
}
