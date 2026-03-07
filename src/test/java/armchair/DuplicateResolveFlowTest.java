package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
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

    private Long setupGuestUser(MockHttpSession session) throws Exception {
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());
        return (Long) session.getAttribute("guestUserId");
    }

    private void setupUnverifiedBookForRanking(Long userId, String title, String author) {
        Book unverified = createUnverifiedBook(title, author);
        addRanking(userId, unverified, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        RankingState state = new RankingState(userId, null, title, author, null, null, 0, 0, 0);
        state.setMode(RankingMode.RESOLVE);
        rankingStateRepository.save(state);
    }

    private void triggerDuplicateDetection(MockHttpSession session, Long userId, String existingWorkOlid,
                                           String unverifiedTitle, String unverifiedAuthor) throws Exception {
        Book existingBook = createVerifiedBook(existingWorkOlid, "Dune", "Frank Herbert");
        addRanking(userId, existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        setupUnverifiedBookForRanking(userId, unverifiedTitle, unverifiedAuthor);

        when(openLibraryService.searchByTitleAndAuthor(eq(unverifiedTitle), eq(unverifiedAuthor), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult(existingWorkOlid, "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", existingWorkOlid)
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void skipDuplicateResolveCleansUpUnverifiedBook() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        triggerDuplicateDetection(session, userId, "OL123W", "Dune Import", "F. Herbert");

        // Verify duplicate session state is set
        assertThat(session.getAttribute("duplicateResolveTitle")).isEqualTo("Dune");
        assertThat(session.getAttribute("duplicateResolveBookId")).isNotNull();

        Long unverifiedBookId = (Long) session.getAttribute("duplicateResolveBookId");

        mockMvc.perform(post("/skip-duplicate-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        // Ranking state should be deleted
        assertThat(rankingStateRepository.findById(userId)).isEmpty();

        // Duplicate session attributes should be cleared
        assertThat(session.getAttribute("duplicateResolveTitle")).isNull();
        assertThat(session.getAttribute("duplicateResolveWorkOlid")).isNull();
        assertThat(session.getAttribute("duplicateResolveBookId")).isNull();

        // Unverified book's ranking should be deleted
        List<Ranking> rankings = rankingRepository.findByUserId(userId);
        assertThat(rankings).noneMatch(r -> r.getBook().getId().equals(unverifiedBookId));

        // The orphaned unverified book should be deleted
        assertThat(bookRepository.findById(unverifiedBookId)).isEmpty();

        // The existing verified book ranking should still exist
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getWorkOlid()).isEqualTo("OL123W");
    }

    @Test
    void rerankDuplicateResolveStartsCategorize() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        triggerDuplicateDetection(session, userId, "OL123W", "Dune Import", "F. Herbert");

        Long unverifiedBookId = (Long) session.getAttribute("duplicateResolveBookId");

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rank/categorize"));

        // RankingState should be set to CATEGORIZE for the existing book
        RankingState state = rankingStateRepository.findById(userId).orElse(null);
        assertThat(state).isNotNull();
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(state.getWorkOlidBeingRanked()).isEqualTo("OL123W");
        assertThat(state.getTitleBeingRanked()).isEqualTo("Dune");

        // Unverified book should be cleaned up
        assertThat(bookRepository.findById(unverifiedBookId)).isEmpty();

        // The existing ranking should be removed (to be re-ranked)
        assertThat(rankingRepository.findByUserId(userId)).isEmpty();

        // Duplicate session attributes should be cleared
        assertThat(session.getAttribute("duplicateResolveTitle")).isNull();
    }

    @Test
    void rerankDuplicateResolvePreservesReview() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        // Create existing book with a review
        Book existingBook = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRankingWithReview(userId, existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0, "A masterpiece of science fiction");

        setupUnverifiedBookForRanking(userId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Review should be preserved in the new ranking state
        RankingState state = rankingStateRepository.findById(userId).orElse(null);
        assertThat(state).isNotNull();
        assertThat(state.getReviewBeingRanked()).isEqualTo("A masterpiece of science fiction");
    }

    @Test
    void rerankDuplicateResolveClosesPositionGap() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        // Create three ranked books at positions 0, 1, 2
        Book book0 = createVerifiedBook("OL100W", "Book Zero", "Author A");
        Book book1 = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        Book book2 = createVerifiedBook("OL200W", "Book Two", "Author B");
        addRanking(userId, book0, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(userId, book1, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(userId, book2, Bookshelf.FICTION, BookCategory.LIKED, 2);

        setupUnverifiedBookForRanking(userId, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Position gap should be closed: book2 should move from position 2 to 1
        List<Ranking> rankings = rankingRepository
                .findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(userId, Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(rankings).hasSize(2);
        assertThat(rankings.get(0).getBook().getWorkOlid()).isEqualTo("OL100W");
        assertThat(rankings.get(0).getPosition()).isEqualTo(0);
        assertThat(rankings.get(1).getBook().getWorkOlid()).isEqualTo("OL200W");
        assertThat(rankings.get(1).getPosition()).isEqualTo(1);
    }

    @Test
    void rerankDuplicateResolveWithMissingWorkOlidRedirects() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        setupUnverifiedBookForRanking(userId, "Some Book", "Some Author");

        // Manually set session attributes with a nonexistent workOlid
        session.setAttribute("duplicateResolveBookId", -999L);
        session.setAttribute("duplicateResolveWorkOlid", "OL_NONEXISTENT_W");
        session.setAttribute("duplicateResolveTitle", "Ghost Book");

        mockMvc.perform(post("/rerank-duplicate-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        // Should clean up ranking state since the existing ranking wasn't found
        assertThat(rankingStateRepository.findById(userId)).isEmpty();
    }

    @Test
    void skipDuplicateResolveWithNullBookIdStillCleansUp() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        setupUnverifiedBookForRanking(userId, "Some Book", "Some Author");

        // Set duplicate session attributes but with null bookId
        session.setAttribute("duplicateResolveTitle", "Some Title");
        session.setAttribute("duplicateResolveWorkOlid", "OL999W");
        // duplicateResolveBookId deliberately not set (null)

        mockMvc.perform(post("/skip-duplicate-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        // Ranking state should still be deleted
        assertThat(rankingStateRepository.findById(userId)).isEmpty();
        // Session attributes should be cleared
        assertThat(session.getAttribute("duplicateResolveTitle")).isNull();
    }

    @Test
    void resolveBookWithNoRankingStateRedirects() throws Exception {
        MockHttpSession session = guestSession();
        setupGuestUser(session);

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void abandonResolveThenResumeWithNewBook() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        setupUnverifiedBookForRanking(userId, "First Book", "First Author");

        mockMvc.perform(post("/abandon-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingStateRepository.findById(userId)).isEmpty();

        // Start a new resolve flow with a different book
        setupUnverifiedBookForRanking(userId, "Second Book", "Second Author");

        when(openLibraryService.searchByTitleAndAuthor(eq("Second Book"), eq("Second Author"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL999W", "OL999M", "Second Book", "Second Author", 2020, null, null)
                ));

        mockMvc.perform(get("/my-books").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("resolveResults"));

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL999W")
                        .param("title", "Second Book")
                        .param("author", "Second Author")
                        .param("editionOlid", "OL999M")
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = rankingStateRepository.findById(userId).orElse(null);
        assertThat(state).isNotNull();
        assertThat(state.getWorkOlidBeingRanked()).isEqualTo("OL999W");
        assertThat(state.getMode()).isEqualTo(RankingMode.SELECT_EDITION);
    }

    @Test
    void skipResolveStateTransitions() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        setupUnverifiedBookForRanking(userId, "Dune", "Frank Herbert");

        // Initial state: skipResolve is null
        assertThat(session.getAttribute("skipResolve")).isNull();

        // First skip: null → "expanded"
        mockMvc.perform(post("/skip-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(session.getAttribute("skipResolve")).isEqualTo("expanded");

        // Second skip: "expanded" → "manual"
        mockMvc.perform(post("/skip-resolve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(session.getAttribute("skipResolve")).isEqualTo("manual");
    }

    @Test
    void duplicateDetectionSetsDuplicateSessionAttributes() throws Exception {
        MockHttpSession session = guestSession();
        Long userId = setupGuestUser(session);

        triggerDuplicateDetection(session, userId, "OL123W", "Dune Import", "F. Herbert");

        assertThat(session.getAttribute("duplicateResolveTitle")).isEqualTo("Dune");
        assertThat(session.getAttribute("duplicateResolveWorkOlid")).isEqualTo("OL123W");
        assertThat(session.getAttribute("duplicateResolveBookId")).isNotNull();

        // RankingState should still exist (not deleted until user chooses skip or rerank)
        assertThat(rankingStateRepository.findById(userId)).isPresent();

        // skipResolve should be cleared
        assertThat(session.getAttribute("skipResolve")).isNull();
    }

    @Test
    void skipDuplicateResolveKeepsOtherUsersRankings() throws Exception {
        MockHttpSession session1 = guestSession();
        Long userId1 = setupGuestUser(session1);

        MockHttpSession session2 = guestSession();
        Long userId2 = setupGuestUser(session2);

        // Both users have the same verified book ranked
        Book sharedBook = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(userId1, sharedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(userId2, sharedBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // User 1 has an unverified duplicate
        setupUnverifiedBookForRanking(userId1, "Dune Import", "F. Herbert");

        when(openLibraryService.searchByTitleAndAuthor(eq("Dune Import"), eq("F. Herbert"), eq(3)))
                .thenReturn(List.of(
                        new OpenLibraryService.BookResult("OL123W", "OL123M", "Dune", "Frank Herbert", 1965, 12345, null)
                ));

        mockMvc.perform(get("/my-books").session(session1)).andExpect(status().isOk());

        mockMvc.perform(post("/resolve-book")
                        .param("workOlid", "OL123W")
                        .param("title", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL123M")
                        .session(session1).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/skip-duplicate-resolve").session(session1).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // User 2's ranking should be unaffected
        List<Ranking> user2Rankings = rankingRepository.findByUserId(userId2);
        assertThat(user2Rankings).hasSize(1);
        assertThat(user2Rankings.get(0).getBook().getWorkOlid()).isEqualTo("OL123W");
    }
}
