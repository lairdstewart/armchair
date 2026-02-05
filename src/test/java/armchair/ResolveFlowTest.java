package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
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

    private void setupUnverifiedBookForRanking(Long userId, String title, String author) {
        Book unverified = createUnverifiedBook(title, author);
        addRanking(userId, unverified, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        RankingState state = new RankingState(userId, null, title, author, null, null, 0, 0, 0);
        rankingStateRepository.save(state);
    }

    @Test
    void autoResolveSuccess() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        setupUnverifiedBookForRanking(guestUserId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965),
                        new OpenLibraryService.BookResult("OL456W", "OL456M", "Dune Messiah", "Frank Herbert", 1969)
                ));

        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = rankingStateRepository.findById(guestUserId).orElse(null);
        assertThat(state).isNotNull();
        assertThat(state.getWorkOlidBeingRanked()).isEqualTo("OL123W");
        assertThat(state.getTitleBeingRanked()).isEqualTo("Dune");
    }

    @Test
    void expandTo10Results() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        setupUnverifiedBookForRanking(guestUserId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL111W", null, "Wrong Dune", "Someone", 2000),
                        new OpenLibraryService.BookResult("OL222W", null, "Also Wrong", "Another", 2001),
                        new OpenLibraryService.BookResult("OL333W", null, "Still Wrong", "Third", 2002)
                ));

        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        mockMvc.perform(post("/skip-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965)
                ));

        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));
    }

    @Test
    void manualSearchFallback() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        setupUnverifiedBookForRanking(guestUserId, "Dune", "Frank Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL111W", null, "Wrong", "Wrong", 2000)
                ));
        when(openLibraryService.searchByTitleAndAuthor(eq("Dune"), eq("Frank Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL222W", null, "Still Wrong", "Wrong", 2001)
                ));

        // First visit — show initial results (which are < 3, so skipResolve goes to "expanded")
        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk());

        // Skip expanded results → goes to manual
        mockMvc.perform(post("/skip-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Now should be in MANUAL_RESOLVE mode
        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void noResultsBothStagesGoesToManual() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        setupUnverifiedBookForRanking(guestUserId, "Obscure Book", "Unknown Author");

        when(openLibraryService.searchByTitleAndAuthor(eq("Obscure Book"), eq("Unknown Author"), anyInt()))
                .thenReturn(List.of());

        // First attempt empty, tries expanded, also empty → redirect to manual
        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().is3xxRedirection());

        // Should redirect to manual resolve mode
        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateDuringResolve() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        Book existingBook = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(guestUserId, existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        setupUnverifiedBookForRanking(guestUserId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965)
                ));

        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(session.getAttribute("duplicateResolveTitle")).isEqualTo("Dune");
    }

    @Test
    void abandonResolveShowsWarning() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        setupUnverifiedBookForRanking(guestUserId, "Unknown Book", "Unknown Author");

        mockMvc.perform(post("/abandon-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingStateRepository.findById(guestUserId)).isEmpty();
        assertThat(session.getAttribute("resolveWarning")).isEqualTo("Unknown Book");
    }

    @Test
    void resolveBookSavesFirstPublishYear() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        Long guestUserId = (Long) session.getAttribute("guestUserId");

        setupUnverifiedBookForRanking(guestUserId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965)
                ));

        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .param("firstPublishYear", "1965")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Book book = bookRepository.findByWorkOlid("OL123W").orElseThrow();
        assertThat(book.getFirstPublishYear()).isEqualTo(1965);
    }
}
