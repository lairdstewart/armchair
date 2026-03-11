package armchair;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import armchair.service.OpenLibraryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SearchAndEditionsTest extends BaseIntegrationTest {

    // ========== GET /search-books ==========

    @Test
    void searchBooksPageLoads() throws Exception {
        createOAuthUser("search1", "oauth-search-1");

        mockMvc.perform(get("/search-books").with(oauthUser("oauth-search-1")))
                .andExpect(status().isOk())
                .andExpect(view().name("search-books"))
                .andExpect(model().attributeExists("searchResults"));
    }

    @Test
    void searchBooksShowsPreviousResults() throws Exception {
        createOAuthUser("search2", "oauth-search-2");

        List<OpenLibraryService.BookResult> results = List.of(
                new OpenLibraryService.BookResult("OL1W", "OL1M", "Dune", "Frank Herbert", 1965, 12345, 10));
        when(openLibraryService.searchBooks("Dune")).thenReturn(results);

        MockHttpSession session = new MockHttpSession();

        // POST search first
        mockMvc.perform(post("/search-books")
                        .param("query", "Dune")
                        .session(session)
                        .with(oauthUser("oauth-search-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // GET should show results from session
        mockMvc.perform(get("/search-books")
                        .session(session)
                        .with(oauthUser("oauth-search-2")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("query", "Dune"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dune")));
    }

    // ========== POST /search-books ==========

    @Test
    void searchBooksPostRedirectsToSearchPage() throws Exception {
        createOAuthUser("search3", "oauth-search-3");

        when(openLibraryService.searchBooks("Foundation")).thenReturn(List.of(
                new OpenLibraryService.BookResult("OL2W", "OL2M", "Foundation", "Isaac Asimov", 1951, null, 5)));

        mockMvc.perform(post("/search-books")
                        .param("query", "Foundation")
                        .with(oauthUser("oauth-search-3")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search-books"));
    }

    @Test
    void searchBooksEmptyResults() throws Exception {
        createOAuthUser("search4", "oauth-search-4");

        when(openLibraryService.searchBooks("xyznonexistent")).thenReturn(List.of());

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/search-books")
                        .param("query", "xyznonexistent")
                        .session(session)
                        .with(oauthUser("oauth-search-4")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/search-books")
                        .session(session)
                        .with(oauthUser("oauth-search-4")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("searchResults", List.of()));
    }

    // ========== GET /editions/{workOlid} ==========

    @Test
    void editionsPageLoads() throws Exception {
        createOAuthUser("ed1", "oauth-ed-1");

        List<OpenLibraryService.EditionResult> editions = List.of(
                new OpenLibraryService.EditionResult("OL1M", "Dune (1965)", "9780441172719", 12345, "Ace", "1965"),
                new OpenLibraryService.EditionResult("OL2M", "Dune (2005)", "9780441013593", 67890, "Ace", "2005"));
        when(openLibraryService.getEditionsForWork(eq("OL100W"), anyInt(), anyInt())).thenReturn(editions);

        mockMvc.perform(get("/editions/OL100W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-ed-1")))
                .andExpect(status().isOk())
                .andExpect(view().name("editions"))
                .andExpect(model().attribute("workOlid", "OL100W"))
                .andExpect(model().attribute("workTitle", "Dune"))
                .andExpect(model().attribute("workAuthor", "Frank Herbert"))
                .andExpect(model().attribute("editionTotalCount", 2));
    }

    @Test
    void editionsEmptyList() throws Exception {
        createOAuthUser("ed2", "oauth-ed-2");

        when(openLibraryService.getEditionsForWork(eq("OL999W"), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/editions/OL999W")
                        .param("title", "Unknown Book")
                        .with(oauthUser("oauth-ed-2")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("editionTotalCount", 0))
                .andExpect(model().attribute("editionResults", List.of()));
    }

    @Test
    void editionsPagination() throws Exception {
        createOAuthUser("ed3", "oauth-ed-3");

        // 7 editions -> 2 pages (page size 5)
        List<OpenLibraryService.EditionResult> editions = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            editions.add(new OpenLibraryService.EditionResult(
                    "OL" + i + "M", "Edition " + i, null, null, null, null));
        }
        when(openLibraryService.getEditionsForWork(eq("OL200W"), anyInt(), anyInt())).thenReturn(editions);

        MockHttpSession session = new MockHttpSession();

        // Page 0 should have 5 editions
        mockMvc.perform(get("/editions/OL200W")
                        .param("page", "0")
                        .session(session)
                        .with(oauthUser("oauth-ed-3")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("editionPage", 0))
                .andExpect(model().attribute("editionTotalPages", 2));

        // Page 1 should have 2 editions
        mockMvc.perform(get("/editions/OL200W")
                        .param("page", "1")
                        .session(session)
                        .with(oauthUser("oauth-ed-3")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("editionPage", 1));
    }

    // ========== POST /select-book-edition ==========

    @Test
    void selectBookEditionCreatesBookAndRankingState() throws Exception {
        User user = createOAuthUser("sbe1", "oauth-sbe-1");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book-edition")
                        .param("workOlid", "OL400W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL400M")
                        .param("isbn13", "9780441172719")
                        .param("coverId", "12345")
                        .session(session)
                        .with(oauthUser("oauth-sbe-1")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rank/categorize"));

        RankingState rs = getRankingState(session);
        assertThat(rs.getWorkOlidBeingRanked()).isEqualTo("OL400W");
        assertThat(rs.getTitleBeingRanked()).isEqualTo("Dune");
        assertThat(rs.getAuthorBeingRanked()).isEqualTo("Frank Herbert");
        assertThat(rs.getEditionOlidBeingRanked()).isEqualTo("OL400M");
        assertThat(rs.getIsbn13BeingRanked()).isEqualTo("9780441172719");
        assertThat(rs.isEditionSelected()).isTrue();
        assertThat(rs.getMode()).isEqualTo(RankingMode.CATEGORIZE);
    }

    @Test
    void selectBookEditionUsesEditionTitle() throws Exception {
        User user = createOAuthUser("sbe2", "oauth-sbe-2");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book-edition")
                        .param("workOlid", "OL500W")
                        .param("bookName", "Original Title")
                        .param("author", "Author")
                        .param("editionTitle", "Special Edition Title")
                        .session(session)
                        .with(oauthUser("oauth-sbe-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState rs = getRankingState(session);
        assertThat(rs.getTitleBeingRanked()).isEqualTo("Special Edition Title");

        // Book should also have the edition title
        assertThat(bookRepository.findByWorkOlid("OL500W").orElseThrow().getTitle())
                .isEqualTo("Special Edition Title");
    }

    @Test
    void selectBookEditionFullFlowToCategorize() throws Exception {
        User user = createOAuthUser("sbe3", "oauth-sbe-3");
        MockHttpSession session = new MockHttpSession();

        // Select edition from browse page
        mockMvc.perform(post("/select-book-edition")
                        .param("workOlid", "OL600W")
                        .param("bookName", "Test Book")
                        .param("author", "Test Author")
                        .param("editionOlid", "OL600M")
                        .session(session)
                        .with(oauthUser("oauth-sbe-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Now categorize it
        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-sbe-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        var liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Test Book");
        assertThat(liked.get(0).getBook().getEditionOlid()).isEqualTo("OL600M");
    }

    // ========== GET /rank/categorize ==========

    @Test
    void categorizeRedirectsWithoutRankingState() throws Exception {
        createOAuthUser("cat1", "oauth-cat-1");

        mockMvc.perform(get("/rank/categorize").with(oauthUser("oauth-cat-1")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void categorizeRedirectsWhenWrongMode() throws Exception {
        User user = createOAuthUser("cat2", "oauth-cat-2");
        MockHttpSession session = new MockHttpSession();

        RankingState rs = new RankingState("OL1W", "Book", "Author", null, null);
        rs.setMode(RankingMode.RANK);
        setRankingState(session, rs);

        mockMvc.perform(get("/rank/categorize").session(session).with(oauthUser("oauth-cat-2")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void categorizeShowsPageInCorrectMode() throws Exception {
        User user = createOAuthUser("cat3", "oauth-cat-3");
        MockHttpSession session = new MockHttpSession();

        RankingState rs = new RankingState("OL1W", "Dune", "Frank Herbert", null, null);
        rs.setMode(RankingMode.CATEGORIZE);
        setRankingState(session, rs);

        mockMvc.perform(get("/rank/categorize").session(session).with(oauthUser("oauth-cat-3")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("rankingState"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dune")));
    }

    // ========== GET /rank/compare ==========

    @Test
    void compareRedirectsWithoutRankingState() throws Exception {
        createOAuthUser("cmp1", "oauth-cmp-1");

        mockMvc.perform(get("/rank/compare").with(oauthUser("oauth-cmp-1")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void compareRedirectsWhenWrongMode() throws Exception {
        User user = createOAuthUser("cmp2", "oauth-cmp-2");
        MockHttpSession session = new MockHttpSession();

        RankingState rs = new RankingState("OL1W", "Book", "Author", null, null);
        rs.setMode(RankingMode.CATEGORIZE);
        setRankingState(session, rs);

        mockMvc.perform(get("/rank/compare").session(session).with(oauthUser("oauth-cmp-2")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void compareShowsComparisonBooks() throws Exception {
        User user = createOAuthUser("cmp3", "oauth-cmp-3");
        MockHttpSession session = new MockHttpSession();

        var existingBook = createVerifiedBook("OL10W", "Existing Book", "Author A");
        addRanking(user.getId(), existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        RankingState rs = new RankingState("OL11W", "New Book", "Author B",
                Bookshelf.FICTION, BookCategory.LIKED, 0, 0, 0);
        rs.setMode(RankingMode.RANK);
        setRankingState(session, rs);

        mockMvc.perform(get("/rank/compare").session(session).with(oauthUser("oauth-cmp-3")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("comparisonBookTitle", "Existing Book"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("New Book")));
    }

    @Test
    void compareRedirectsWhenIndexOutOfBounds() throws Exception {
        User user = createOAuthUser("cmp4", "oauth-cmp-4");
        MockHttpSession session = new MockHttpSession();

        // No books in the list but compareToIndex = 0
        RankingState rs = new RankingState("OL1W", "Book", "Author",
                Bookshelf.FICTION, BookCategory.LIKED, 0, 0, 0);
        rs.setMode(RankingMode.RANK);
        setRankingState(session, rs);

        mockMvc.perform(get("/rank/compare").session(session).with(oauthUser("oauth-cmp-4")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }
}
