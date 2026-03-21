package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.RankingMode;
import armchair.entity.RankingState;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowFlowTest extends BaseIntegrationTest {

    // ── Re-rank workflow ──

    @Test
    void rerankFlow() throws Exception {
        User user = createOAuthUser("wf1", "oauth-wf-1");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        // Step 1: start-rerank creates RE_RANK state
        mockMvc.perform(post("/start-rerank")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-1")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.RE_RANK);
        assertThat(state.getBookshelf()).isEqualTo(Bookshelf.FICTION);

        // Step 2: select-rerank-book removes book and stores info
        mockMvc.perform(post("/select-rerank-book")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Book B removed, gap closed
        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(0).getPosition()).isEqualTo(0);
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book C");
        assertThat(liked.get(1).getPosition()).isEqualTo(1);

        // State has book info for re-ranking
        state = getRankingState(session);
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Book B");
        assertThat(state.getBookIdentity().getWorkOlid()).isEqualTo("OL2W");
    }

    @Test
    void rerankThenCategorizeAndRank() throws Exception {
        User user = createOAuthUser("wf2", "oauth-wf-2");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        // Start rerank flow
        mockMvc.perform(post("/start-rerank")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/select-rerank-book")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Now categorize Book B into nonfiction/ok (different shelf)
        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "nonfiction")
                        .param("category", "ok")
                        .session(session)
                        .with(oauthUser("oauth-wf-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Book B is now in nonfiction/ok
        List<Ranking> nfOk = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, BookCategory.OK);
        assertThat(nfOk).hasSize(1);
        assertThat(nfOk.get(0).getBook().getTitle()).isEqualTo("Book B");

        // Fiction/liked should have A and C
        List<Ranking> ficLiked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(ficLiked).hasSize(2);

        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void selectRerankBookWithoutStateRedirects() throws Exception {
        User user = createOAuthUser("wf3", "oauth-wf-3");

        Book book = createVerifiedBook("OL1W", "Book A", "Author A");
        Ranking r = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // No ranking state → should redirect without removing
        mockMvc.perform(post("/select-rerank-book")
                        .param("bookId", String.valueOf(r.getId()))
                        .with(oauthUser("oauth-wf-3")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        // Book should still be there
        assertThat(rankingRepository.findById(r.getId())).isPresent();
    }

    // ── Remove workflow ──

    @Test
    void removeFlow() throws Exception {
        User user = createOAuthUser("wf4", "oauth-wf-4");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        // Step 1: start-remove
        mockMvc.perform(post("/start-remove")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-4")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.REMOVE);
        assertThat(state.getBookshelf()).isEqualTo(Bookshelf.FICTION);

        // Step 2: select-remove-book
        mockMvc.perform(post("/select-remove-book")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-4")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        // Book B removed, gap closed
        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(0).getPosition()).isEqualTo(0);
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book C");
        assertThat(liked.get(1).getPosition()).isEqualTo(1);

        // State cleared
        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void selectRemoveBookWithoutStateRedirects() throws Exception {
        User user = createOAuthUser("wf5", "oauth-wf-5");

        Book book = createVerifiedBook("OL1W", "Book A", "Author A");
        Ranking r = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // No REMOVE state → should redirect without removing
        mockMvc.perform(post("/select-remove-book")
                        .param("bookId", String.valueOf(r.getId()))
                        .with(oauthUser("oauth-wf-5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findById(r.getId())).isPresent();
    }

    // ── Remove want-to-read workflow ──

    @Test
    void removeWantToReadFlow() throws Exception {
        User user = createOAuthUser("wf6", "oauth-wf-6");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Ranking rA = addRanking(user.getId(), bookA, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 1);

        // Step 1: start-remove-wtr
        mockMvc.perform(post("/start-remove-wtr")
                        .session(session)
                        .with(oauthUser("oauth-wf-6")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books?selectedBookshelf=WANT_TO_READ"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.REMOVE);
        assertThat(state.getBookshelf()).isEqualTo(Bookshelf.WANT_TO_READ);

        // Step 2: select-remove-wtr-book
        mockMvc.perform(post("/select-remove-wtr-book")
                        .param("bookId", String.valueOf(rA.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-6")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books?selectedBookshelf=WANT_TO_READ"));

        // Book A removed, Book B shifted
        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getTitle()).isEqualTo("Book B");
        assertThat(wtr.get(0).getPosition()).isEqualTo(0);

        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void selectRemoveWtrBookRejectsNonWtrBook() throws Exception {
        User user = createOAuthUser("wf7", "oauth-wf-7");
        MockHttpSession session = new MockHttpSession();

        // Book is in fiction, not want-to-read
        Book book = createVerifiedBook("OL1W", "Book A", "Author A");
        Ranking r = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // Set up REMOVE state for want-to-read
        RankingState rs = new RankingState(null, null, null, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        rs.setMode(RankingMode.REMOVE);
        setRankingState(session, rs);

        // Try to remove a fiction book via WTR endpoint → should reject
        mockMvc.perform(post("/select-remove-wtr-book")
                        .param("bookId", String.valueOf(r.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Book should still exist
        assertThat(rankingRepository.findById(r.getId())).isPresent();
    }

    // ── Review workflow ──

    @Test
    void reviewFlow() throws Exception {
        User user = createOAuthUser("wf8", "oauth-wf-8");
        MockHttpSession session = new MockHttpSession();

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // Step 1: start-review
        mockMvc.perform(post("/start-review")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-8")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.REVIEW);
        assertThat(state.getBookshelf()).isEqualTo(Bookshelf.FICTION);

        // Step 2: select-review-book
        mockMvc.perform(post("/select-review-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-8")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        state = getRankingState(session);
        assertThat(state.getBookIdBeingReviewed()).isEqualTo(ranking.getId());

        // Step 3: save-review
        mockMvc.perform(post("/save-review")
                        .param("review", "An epic masterpiece")
                        .session(session)
                        .with(oauthUser("oauth-wf-8")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books?selectedBookshelf=FICTION"));

        Ranking updated = rankingRepository.findById(ranking.getId()).orElseThrow();
        assertThat(updated.getReview()).isEqualTo("An epic masterpiece");
        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void reviewFlowClearExistingReview() throws Exception {
        User user = createOAuthUser("wf9", "oauth-wf-9");
        MockHttpSession session = new MockHttpSession();

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRankingWithReview(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0, "Old review");

        // Start review via start-review + select
        mockMvc.perform(post("/start-review")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-9")).with(csrf()));

        mockMvc.perform(post("/select-review-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-9")).with(csrf()));

        // Save with empty review to clear it
        mockMvc.perform(post("/save-review")
                        .param("review", "")
                        .session(session)
                        .with(oauthUser("oauth-wf-9")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Ranking updated = rankingRepository.findById(ranking.getId()).orElseThrow();
        assertThat(updated.getReview()).isNull();
    }

    @Test
    void selectReviewBookWithoutStateRedirects() throws Exception {
        User user = createOAuthUser("wf10", "oauth-wf-10");

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // No REVIEW state → should just redirect
        mockMvc.perform(post("/select-review-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .with(oauthUser("oauth-wf-10")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    // ── Unranked book workflows ──

    @Test
    void rankUnrankedVerifiedBook() throws Exception {
        User user = createOAuthUser("wf11", "oauth-wf-11");
        MockHttpSession session = new MockHttpSession();

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        mockMvc.perform(post("/rank-unranked-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-11")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rank/categorize"));

        // Book still in unranked (deferred delete until ranking completes)
        List<Ranking> unranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.UNRANKED, BookCategory.UNRANKED);
        assertThat(unranked).hasSize(1);

        // State set up for categorize with unrankedRankingId for deferred delete
        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Dune");
        assertThat(state.getBookshelf()).isEqualTo(Bookshelf.UNRANKED);
        assertThat(state.getUnrankedRankingId()).isEqualTo(ranking.getId());
    }

    @Test
    void rankUnrankedUnverifiedBook() throws Exception {
        User user = createOAuthUser("wf12", "oauth-wf-12");
        MockHttpSession session = new MockHttpSession();

        Book book = createUnverifiedBook("Mystery Book", "Unknown Author");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        mockMvc.perform(post("/rank-unranked-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-12")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/resolve"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.RESOLVE);
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Mystery Book");
    }

    @Test
    void rankUnrankedBookPreservesReview() throws Exception {
        User user = createOAuthUser("wf13", "oauth-wf-13");
        MockHttpSession session = new MockHttpSession();

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRankingWithReview(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0, "Imported review");

        mockMvc.perform(post("/rank-unranked-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-13")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = getRankingState(session);
        assertThat(state.getReviewBeingRanked()).isEqualTo("Imported review");
    }

    @Test
    void rankUnrankedBookRejectsNonUnranked() throws Exception {
        User user = createOAuthUser("wf14", "oauth-wf-14");

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/rank-unranked-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .with(oauthUser("oauth-wf-14")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books?selectedBookshelf=UNRANKED"));

        // Book should still be in fiction
        assertThat(rankingRepository.findById(ranking.getId())).isPresent();
    }

    @Test
    void wantToReadUnrankedVerifiedBook() throws Exception {
        User user = createOAuthUser("wf15", "oauth-wf-15");
        MockHttpSession session = new MockHttpSession();

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        mockMvc.perform(post("/want-to-read-unranked-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-15")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rank/categorize"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(state.isWantToRead()).isTrue();
        assertThat(state.getBookshelf()).isEqualTo(Bookshelf.UNRANKED);
        assertThat(state.getUnrankedRankingId()).isEqualTo(ranking.getId());

        // Book still in unranked (deferred delete until ranking completes)
        assertThat(rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.UNRANKED, BookCategory.UNRANKED)).hasSize(1);
    }

    @Test
    void wantToReadUnrankedUnverifiedBook() throws Exception {
        User user = createOAuthUser("wf16", "oauth-wf-16");
        MockHttpSession session = new MockHttpSession();

        Book book = createUnverifiedBook("Mystery Book", "Unknown Author");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        mockMvc.perform(post("/want-to-read-unranked-book")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-16")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/resolve"));

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.RESOLVE);
        assertThat(state.isWantToRead()).isTrue();
    }

    // ── Rank all workflow ──

    @Test
    void rankAllStartsFirstUnrankedBook() throws Exception {
        User user = createOAuthUser("wf17", "oauth-wf-17");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.UNRANKED, BookCategory.UNRANKED, 1);

        mockMvc.perform(post("/rank-all")
                        .session(session)
                        .with(oauthUser("oauth-wf-17")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rank/categorize"));

        // Both books still in unranked (deferred delete until ranking completes)
        List<Ranking> unranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.UNRANKED, BookCategory.UNRANKED);
        assertThat(unranked).hasSize(2);

        // State has rankAll flag
        RankingState state = getRankingState(session);
        assertThat(state.isRankAll()).isTrue();
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Book A");
    }

    @Test
    void rankAllWithNoUnrankedBooksRedirects() throws Exception {
        User user = createOAuthUser("wf18", "oauth-wf-18");

        // No unranked books
        mockMvc.perform(post("/rank-all")
                        .with(oauthUser("oauth-wf-18")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books?selectedBookshelf=FICTION"));
    }

    @Test
    void rankAllWithUnverifiedBookGoesToResolve() throws Exception {
        User user = createOAuthUser("wf19", "oauth-wf-19");
        MockHttpSession session = new MockHttpSession();

        Book book = createUnverifiedBook("Mystery Book", "Unknown");
        addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        mockMvc.perform(post("/rank-all")
                        .session(session)
                        .with(oauthUser("oauth-wf-19")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/resolve"));

        RankingState state = getRankingState(session);
        assertThat(state.isRankAll()).isTrue();
        assertThat(state.getMode()).isEqualTo(RankingMode.RESOLVE);
    }

    // ── Navigation-back endpoints ──

    @Test
    void backToResolveChangesMode() throws Exception {
        User user = createOAuthUser("wf24", "oauth-wf-24");

        RankingState rs = new RankingState("OL1W", "Dune", "Frank Herbert", null, null);
        rs.setMode(RankingMode.CATEGORIZE);

        MockHttpSession session = new MockHttpSession();
        setRankingState(session, rs);
        session.setAttribute("cachedEditions", List.of());

        mockMvc.perform(post("/back-to-resolve")
                        .session(session)
                        .with(oauthUser("oauth-wf-24")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/resolve"));

        RankingState updated = getRankingState(session);
        assertThat(updated.getMode()).isEqualTo(RankingMode.RESOLVE);

        // Session attributes cleared
    }

    @Test
    void backToResolveWithoutStateRedirects() throws Exception {
        User user = createOAuthUser("wf25", "oauth-wf-25");

        mockMvc.perform(post("/back-to-resolve")
                        .with(oauthUser("oauth-wf-25")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));
    }

    // ── Edge cases ──

    @Test
    void startRerankRestoresAbandonedBook() throws Exception {
        User user = createOAuthUser("wf26", "oauth-wf-26");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        // Start a rerank and select a book (removes it)
        mockMvc.perform(post("/start-rerank")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-26")).with(csrf()));
        mockMvc.perform(post("/select-rerank-book")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-wf-26")).with(csrf()));

        // Verify B was removed
        assertThat(rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED)).hasSize(1);

        // Start a new rerank — should restore the abandoned book first
        mockMvc.perform(post("/start-rerank")
                        .param("bookshelf", "fiction")
                        .session(session)
                        .with(oauthUser("oauth-wf-26")).with(csrf()));

        // The state should now be fresh RE_RANK, no book info
        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.RE_RANK);
        assertThat(state.getBookIdentity().getTitle()).isNull();
    }

    @Test
    void startRemoveInvalidBookshelfRedirects() throws Exception {
        User user = createOAuthUser("wf27", "oauth-wf-27");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/start-remove")
                        .param("bookshelf", "invalid_shelf")
                        .session(session)
                        .with(oauthUser("oauth-wf-27")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void startRerankInvalidBookshelfRedirects() throws Exception {
        User user = createOAuthUser("wf28", "oauth-wf-28");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/start-rerank")
                        .param("bookshelf", "invalid_shelf")
                        .session(session)
                        .with(oauthUser("oauth-wf-28")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-books"));

        assertThat(getRankingState(session)).isNull();
    }
}
