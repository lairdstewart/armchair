package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.service.OpenLibraryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResolveFlowTest extends BaseIntegrationTest {

    private void setupUnverifiedBookForRanking(MockHttpSession session, Long userId, String title, String author) {
        Book unverified = createUnverifiedBook(title, author);
        addRanking(userId, unverified, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        RankingState state = new RankingState(null, title, author, null, null, 0, 0, 0);
        state.setMode(RankingMode.RESOLVE);
        setRankingState(session, state);
    }

    @Test
    void autoResolveSuccess() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve1", "oauth-resolve1");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null),
                        new OpenLibraryService.BookResult("OL456W", "OL456M", "Dune Messiah", "Frank Herbert", 1969, 12346, null)
                ));

        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve1")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(oauthUser("oauth-resolve1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = getRankingState(session);
        assertThat(state).isNotNull();
        assertThat(state.getBookIdentity().getWorkOlid()).isEqualTo("OL123W");
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Dune");
    }

    @Test
    void expandTo10Results() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve2", "oauth-resolve2");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL111W", null, "Wrong Dune", "Someone", 2000, null, null),
                        new OpenLibraryService.BookResult("OL222W", null, "Also Wrong", "Another", 2001, null, null),
                        new OpenLibraryService.BookResult("OL333W", null, "Still Wrong", "Third", 2002, null, null)
                ));

        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve2")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        mockMvc.perform(post("/skip-resolve").session(session).with(oauthUser("oauth-resolve2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve2")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));
    }

    @Test
    void manualSearchFallback() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve3", "oauth-resolve3");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL111W", null, "Wrong", "Wrong", 2000, null, null)
                ));
        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL222W", null, "Still Wrong", "Wrong", 2001, null, null)
                ));

        // First visit — show initial results (which are < 3, so skipResolve goes to "expanded")
        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve3")))
                .andExpect(status().isOk());

        // Skip expanded results → goes to manual
        mockMvc.perform(post("/skip-resolve").session(session).with(oauthUser("oauth-resolve3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Now should be in MANUAL_RESOLVE mode
        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve3")))
                .andExpect(status().isOk());
    }

    @Test
    void noResultsBothStagesGoesToManual() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve4", "oauth-resolve4");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Obscure Book", "Unknown Author");

        when(openLibraryService.searchByTitleAndAuthor(eq("Obscure Book"), eq("Unknown Author"), anyInt()))
                .thenReturn(List.of());

        // First attempt empty, tries expanded, also empty → redirect to manual
        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve4")))
                .andExpect(status().is3xxRedirection());

        // Should redirect to manual resolve mode
        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve4")))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateDuringResolve() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve5", "oauth-resolve5");
        Long userId = user.getId();

        Book existingBook = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(userId, existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        setupUnverifiedBookForRanking(session, userId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve5")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(oauthUser("oauth-resolve5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(session.getAttribute("duplicateResolveTitle")).isEqualTo("Dune");
    }

    @Test
    void abandonResolveShowsWarning() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve6", "oauth-resolve6");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Unknown Book", "Unknown Author");

        mockMvc.perform(post("/abandon-resolve").session(session).with(oauthUser("oauth-resolve6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(getRankingState(session)).isNull();
        assertThat(session.getAttribute("resolveWarning")).isEqualTo("Unknown Book");
    }

    @Test
    void resolveBookSavesFirstPublishYear() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve7", "oauth-resolve7");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve7")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .param("firstPublishYear", "1965")
                        .session(session).with(oauthUser("oauth-resolve7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Book book = bookRepository.findByWorkOlid("OL123W").orElseThrow();
        assertThat(book.getFirstPublishYear()).isEqualTo(1965);
    }

    @Test
    void resolveBookSavesCoverId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve8", "oauth-resolve8");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve8")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .param("firstPublishYear", "1965")
                        .param("coverId", "12345")
                        .session(session).with(oauthUser("oauth-resolve8")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Book book = bookRepository.findByWorkOlid("OL123W").orElseThrow();
        assertThat(book.getCoverId()).isEqualTo(12345);
    }

    @Test
    void resolveFlowsContinuesToCategorize() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = createOAuthUser("resolve9", "oauth-resolve9");
        Long userId = user.getId();

        setupUnverifiedBookForRanking(session, userId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        // First visit shows RESOLVE mode
        mockMvc.perform(get("/my-books").session(session).with(oauthUser("oauth-resolve9")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        // Resolve the book
        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .param("firstPublishYear", "1965")
                        .session(session).with(oauthUser("oauth-resolve9")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify RankingState has workOlid and mode is CATEGORIZE
        RankingState state = getRankingState(session);
        assertThat(state.getBookIdentity().getWorkOlid()).isEqualTo("OL123W");
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
    }
}
