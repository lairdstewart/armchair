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

class DuplicateResolveFlowTest extends BaseIntegrationTest {

    private static int userCounter = 0;

    private User nextUser() {
        userCounter++;
        return createOAuthUser("duptest" + userCounter, "oauth-dup-" + userCounter);
    }

    private void setupUnverifiedBookForRanking(MockHttpSession session, Long userId, String title, String author) {
        Book unverified = createUnverifiedBook(title, author);
        addRanking(userId, unverified, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        RankingState state = new RankingState(null, title, author, null, null, 0, 0, 0);
        state.setMode(RankingMode.RESOLVE);
        setRankingState(session, state);
    }

    private void triggerDuplicateDetection(MockHttpSession session, User user, String existingWorkOlid,
                                           String unverifiedTitle, String unverifiedAuthor) throws Exception {
        Book existingBook = createVerifiedBook(existingWorkOlid, "Dune", "Frank Herbert");
        addRanking(user.getId(), existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        setupUnverifiedBookForRanking(session, user.getId(), unverifiedTitle, unverifiedAuthor);

        when(openLibraryService.searchByTitleAndAuthor(eq(unverifiedTitle), eq(unverifiedAuthor), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult(existingWorkOlid, "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser(user.getOauthSubject())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", existingWorkOlid)
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void skipDuplicateResolveCleansUpUnverifiedBook() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        triggerDuplicateDetection(session, user, "OL123W", "Dune Import", "F. Herbert");

        assertThat(session.getAttribute("duplicateResolveTitle")).isEqualTo("Dune");
        assertThat(session.getAttribute("duplicateResolveBookId")).isNotNull();

        Long unverifiedBookId = (Long) session.getAttribute("duplicateResolveBookId");

        mockMvc.perform(post("/skip-duplicate-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        assertThat(getRankingState(session)).isNull();

        assertThat(session.getAttribute("duplicateResolveTitle")).isNull();
        assertThat(session.getAttribute("duplicateResolveWorkOlid")).isNull();
        assertThat(session.getAttribute("duplicateResolveBookId")).isNull();

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).noneMatch(r -> r.getBook().getId().equals(unverifiedBookId));

        assertThat(bookRepository.findById(unverifiedBookId)).isEmpty();

        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getWorkOlid()).isEqualTo("OL123W");
    }

    @Test
    void rerankDuplicateResolveStartsCategorize() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        triggerDuplicateDetection(session, user, "OL123W", "Dune Import", "F. Herbert");

        Long unverifiedBookId = (Long) session.getAttribute("duplicateResolveBookId");

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rank/categorize"));

        RankingState state = getRankingState(session);
        assertThat(state).isNotNull();
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(state.getBookIdentity().getWorkOlid()).isEqualTo("OL123W");
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Dune");

        assertThat(bookRepository.findById(unverifiedBookId)).isEmpty();

        assertThat(rankingRepository.findByUserId(user.getId())).isEmpty();

        assertThat(session.getAttribute("duplicateResolveTitle")).isNull();
    }

    @Test
    void rerankDuplicateResolvePreservesReview() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        Book existingBook = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRankingWithReview(user.getId(), existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0, "A masterpiece of science fiction");

        setupUnverifiedBookForRanking(session, user.getId(), "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser(user.getOauthSubject())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = getRankingState(session);
        assertThat(state).isNotNull();
        assertThat(state.getReviewBeingRanked()).isEqualTo("A masterpiece of science fiction");
    }

    @Test
    void rerankDuplicateResolveClosesPositionGap() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        Book book0 = createVerifiedBook("OL100W", "Book Zero", "Author A");
        Book book1 = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        Book book2 = createVerifiedBook("OL200W", "Book Two", "Author B");
        addRanking(user.getId(), book0, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), book1, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), book2, Bookshelf.FICTION, BookCategory.LIKED, 2);

        setupUnverifiedBookForRanking(session, user.getId(), "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser(user.getOauthSubject())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository
                .findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(rankings).hasSize(2);
        assertThat(rankings.get(0).getBook().getWorkOlid()).isEqualTo("OL100W");
        assertThat(rankings.get(0).getPosition()).isEqualTo(0);
        assertThat(rankings.get(1).getBook().getWorkOlid()).isEqualTo("OL200W");
        assertThat(rankings.get(1).getPosition()).isEqualTo(1);
    }

    @Test
    void rerankDuplicateResolveWithMissingWorkOlidRedirects() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        setupUnverifiedBookForRanking(session, user.getId(), "Some Book", "Some Author");

        session.setAttribute("duplicateResolveBookId", -999L);
        session.setAttribute("duplicateResolveWorkOlid", "OL_NONEXISTENT_W");
        session.setAttribute("duplicateResolveTitle", "Ghost Book");

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void skipDuplicateResolveWithNullBookIdStillCleansUp() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        setupUnverifiedBookForRanking(session, user.getId(), "Some Book", "Some Author");

        session.setAttribute("duplicateResolveTitle", "Some Title");
        session.setAttribute("duplicateResolveWorkOlid", "OL999W");

        mockMvc.perform(post("/skip-duplicate-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        assertThat(getRankingState(session)).isNull();
        assertThat(session.getAttribute("duplicateResolveTitle")).isNull();
    }

    @Test
    void resolveBookWithNoRankingStateRedirects() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session).with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void abandonResolveThenResumeWithNewBook() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        setupUnverifiedBookForRanking(session, user.getId(), "First Book", "First Author");

        mockMvc.perform(post("/abandon-resolve").session(session)
                        .with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(getRankingState(session)).isNull();

        setupUnverifiedBookForRanking(session, user.getId(), "Second Book", "Second Author");

        when(openLibraryService.searchByTitleAndAuthor(eq("Second Book"), eq("Second Author"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL999W", "OL999M", "Second Book", "Second Author", 2020, null, null)
                ));

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser(user.getOauthSubject())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL999W")
                        .param("title", "Second Book")
                        .param("author", "Second Author")
                        .param("editionOlid", "OL999M")
                        .session(session).with(oauthUser(user.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = getRankingState(session);
        assertThat(state).isNotNull();
        assertThat(state.getBookIdentity().getWorkOlid()).isEqualTo("OL999W");
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
    }

    @Test
    void duplicateDetectionSetsDuplicateSessionAttributes() throws Exception {
        User user = nextUser();
        MockHttpSession session = new MockHttpSession();

        triggerDuplicateDetection(session, user, "OL123W", "Dune Import", "F. Herbert");

        assertThat(session.getAttribute("duplicateResolveTitle")).isEqualTo("Dune");
        assertThat(session.getAttribute("duplicateResolveWorkOlid")).isEqualTo("OL123W");
        assertThat(session.getAttribute("duplicateResolveBookId")).isNotNull();

        assertThat(getRankingState(session)).isNotNull();
    }

    @Test
    void skipDuplicateResolveKeepsOtherUsersRankings() throws Exception {
        User user1 = nextUser();
        User user2 = nextUser();
        MockHttpSession session1 = new MockHttpSession();

        Book sharedBook = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(user1.getId(), sharedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user2.getId(), sharedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        setupUnverifiedBookForRanking(session1, user1.getId(), "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(10)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session1)
                        .with(oauthUser(user1.getOauthSubject())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session1).with(oauthUser(user1.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/skip-duplicate-resolve").session(session1)
                        .with(oauthUser(user1.getOauthSubject())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> user2Rankings = rankingRepository.findByUserId(user2.getId());
        assertThat(user2Rankings).hasSize(1);
        assertThat(user2Rankings.get(0).getBook().getWorkOlid()).isEqualTo("OL123W");
    }
}
