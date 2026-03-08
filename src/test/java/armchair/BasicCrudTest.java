package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.service.OpenLibraryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BasicCrudTest extends BaseIntegrationTest {

    @Test
    void addBookFromSearch() throws Exception {
        User user = createOAuthUser("crud1", "oauth-crud-1");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session)
                        .with(oauthUser("oauth-crud-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "nonfiction")
                        .param("category", "ok")
                        .session(session)
                        .with(oauthUser("oauth-crud-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBookshelf()).isEqualTo(Bookshelf.NONFICTION);
        assertThat(rankings.get(0).getCategory()).isEqualTo(BookCategory.OK);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void removeBook() throws Exception {
        User user = createOAuthUser("crud2", "oauth-crud-2");

        Book dune = createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/direct-remove")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .with(oauthUser("oauth-crud-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void exportCsv() throws Exception {
        User user = createOAuthUser("crud3", "oauth-crud-3");

        Book dune = createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        Book orwell = createVerifiedBook("OL200W", "1984", "George Orwell");
        addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), orwell, Bookshelf.FICTION, BookCategory.OK, 0);

        mockMvc.perform(get("/export-csv")
                        .with(oauthUser("oauth-crud-3")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dune")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1984")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rank,Title,Author,Category,Bookshelf,Review")));
    }

    @Test
    void exportCsvOAuthUser() throws Exception {
        User user = createOAuthUser("crud-export", "oauth-crud-export");

        Book dune = createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(get("/export-csv")
                        .with(oauthUser("oauth-crud-export")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dune")));
    }

    @Test
    void searchLocalFindsVerifiedBooks() throws Exception {
        User user = createOAuthUser("crud4", "oauth-crud-4");

        createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        createVerifiedBook("OL200W", "Dune Messiah", "Frank Herbert");

        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/search")
                        .param("type", "books")
                        .param("query", "Dune")
                        .with(oauthUser("oauth-crud-4")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("bookResults"));
    }

    @Test
    void addToReadingList() throws Exception {
        User user = createOAuthUser("crud5", "oauth-crud-5");

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-crud-5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void addToReadingListSkipsDuplicate() throws Exception {
        User user = createOAuthUser("crud6", "oauth-crud-6");

        Book dune = createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-crud-6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserId(user.getId())).hasSize(1);
    }

    @Test
    void cancelAdd() throws Exception {
        User user = createOAuthUser("crud7", "oauth-crud-7");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session)
                        .with(oauthUser("oauth-crud-7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(getRankingState(session)).isNotNull();

        mockMvc.perform(post("/cancel-add")
                        .session(session)
                        .with(oauthUser("oauth-crud-7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void welcomePageLoads() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void myBooksPageLoads() throws Exception {
        createOAuthUser("crud-mybooks", "oauth-crud-mybooks");
        mockMvc.perform(get("/my-books")
                        .with(oauthUser("oauth-crud-mybooks")))
                .andExpect(status().isOk());
    }

    @Test
    void recsPageLoads() throws Exception {
        createOAuthUser("crud-recs", "oauth-crud-recs");
        mockMvc.perform(get("/recs")
                        .with(oauthUser("oauth-crud-recs")))
                .andExpect(status().isOk());
    }
}
