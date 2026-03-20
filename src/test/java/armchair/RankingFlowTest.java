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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RankingFlowTest extends BaseIntegrationTest {

    @Test
    void addToEmptyCategory() throws Exception {
        User user = createOAuthUser("ranker1", "oauth-rank-1");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session)
                        .with(oauthUser("oauth-rank-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-rank-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Dune");
        assertThat(liked.get(0).getPosition()).isEqualTo(0);

        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void binarySearchRanking() throws Exception {
        User user = createOAuthUser("ranker2", "oauth-rank-2");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL3W")
                        .param("bookName", "Book C")
                        .param("author", "Author C")
                        .session(session)
                        .with(oauthUser("oauth-rank-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-rank-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = getRankingState(session);
        assertThat(state.getCategory()).isEqualTo(BookCategory.LIKED);
        assertThat(state.getBinarySearch().getLowIndex()).isEqualTo(0);
        assertThat(state.getBinarySearch().getHighIndex()).isEqualTo(1);

        mockMvc.perform(post("/choose")
                        .param("choice", "new")
                        .session(session)
                        .with(oauthUser("oauth-rank-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(3);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book C");
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(2).getBook().getTitle()).isEqualTo("Book B");
    }

    @Test
    void binarySearchExistingBetter() throws Exception {
        User user = createOAuthUser("ranker3", "oauth-rank-3");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL3W")
                        .param("bookName", "Book C")
                        .param("author", "Author C")
                        .session(session)
                        .with(oauthUser("oauth-rank-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-rank-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/choose")
                        .param("choice", "existing")
                        .session(session)
                        .with(oauthUser("oauth-rank-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book C");
    }

    @Test
    void directRemove() throws Exception {
        User user = createOAuthUser("ranker4", "oauth-rank-4");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        mockMvc.perform(post("/direct-remove")
                        .param("bookId", String.valueOf(rB.getId()))
                        .with(oauthUser("oauth-rank-4")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(0).getPosition()).isEqualTo(0);
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book C");
        assertThat(liked.get(1).getPosition()).isEqualTo(1);
    }

    @Test
    void directReview() throws Exception {
        User user = createOAuthUser("ranker5", "oauth-rank-5");
        MockHttpSession session = new MockHttpSession();

        Book dune = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/direct-review")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .session(session)
                        .with(oauthUser("oauth-rank-5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = getRankingState(session);
        assertThat(state.getMode()).isEqualTo(RankingMode.REVIEW);
        assertThat(state.getBookIdBeingReviewed()).isEqualTo(ranking.getId());

        mockMvc.perform(post("/save-review")
                        .param("review", "A masterpiece")
                        .session(session)
                        .with(oauthUser("oauth-rank-5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Ranking updated = rankingRepository.findById(ranking.getId()).orElseThrow();
        assertThat(updated.getReview()).isEqualTo("A masterpiece");
        assertThat(getRankingState(session)).isNull();
    }

    @Test
    void wantToReadThenRank() throws Exception {
        User user = createOAuthUser("ranker6", "oauth-rank-6");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session)
                        .with(oauthUser("oauth-rank-6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);

        mockMvc.perform(post("/mark-as-read")
                        .param("bookId", String.valueOf(wtr.get(0).getId()))
                        .session(session)
                        .with(oauthUser("oauth-rank-6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED)).isEmpty();

        RankingState state = getRankingState(session);
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Dune");

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-rank-6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void categorizeWithReview() throws Exception {
        User user = createOAuthUser("ranker7", "oauth-rank-7");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .session(session)
                        .with(oauthUser("oauth-rank-7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .param("review", "My favorite book ever")
                        .session(session)
                        .with(oauthUser("oauth-rank-7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getReview()).isEqualTo("My favorite book ever");
    }

    @Test
    void directRerank() throws Exception {
        User user = createOAuthUser("ranker8", "oauth-rank-8");
        MockHttpSession session = new MockHttpSession();

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        mockMvc.perform(post("/direct-rerank")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-rank-8")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book C");

        RankingState state = getRankingState(session);
        assertThat(state.getBookIdentity().getTitle()).isEqualTo("Book B");
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(state.getRestoration().getOriginalCategory()).isEqualTo(BookCategory.LIKED);
        assertThat(state.getRestoration().getOriginalPosition()).isEqualTo(1);
    }

    @Test
    void selectBookSavesEditionOlid() throws Exception {
        User user = createOAuthUser("ranker10", "oauth-rank-10");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL999M")
                        .session(session)
                        .with(oauthUser("oauth-rank-10")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-rank-10")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getEditionOlid()).isEqualTo("OL999M");
    }

    @Test
    void removeFromReadingList() throws Exception {
        User user = createOAuthUser("ranker9", "oauth-rank-9");

        Book dune = createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        Book orwell = createVerifiedBook("OL200W", "1984", "George Orwell");
        Ranking rDune = addRanking(user.getId(), dune, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);
        addRanking(user.getId(), orwell, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 1);

        mockMvc.perform(post("/remove-from-reading-list")
                        .param("bookId", String.valueOf(rDune.getId()))
                        .with(oauthUser("oauth-rank-9")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getTitle()).isEqualTo("1984");
        assertThat(wtr.get(0).getPosition()).isEqualTo(0);
    }

    @Test
    void selectBookSavesFirstPublishYear() throws Exception {
        User user = createOAuthUser("ranker11", "oauth-rank-11");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .param("firstPublishYear", "1965")
                        .session(session)
                        .with(oauthUser("oauth-rank-11")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .session(session)
                        .with(oauthUser("oauth-rank-11")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getFirstPublishYear()).isEqualTo(1965);
    }

    @Test
    void addToReadingListSavesFirstPublishYear() throws Exception {
        User user = createOAuthUser("ranker12", "oauth-rank-12");

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .param("firstPublishYear", "1965")
                        .with(oauthUser("oauth-rank-12")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getFirstPublishYear()).isEqualTo(1965);
    }

    @Test
    void abandonedRerankRestoresOriginalPosition() throws Exception {
        User user = createOAuthUser("ranker13", "oauth-rank-13");
        MockHttpSession session = new MockHttpSession();

        // Create 3 ranked books: A at 0, B at 1, C at 2
        Book bookA = createVerifiedBook("OL101W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL102W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL103W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        // Start re-ranking book B
        mockMvc.perform(post("/direct-rerank")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-rank-13")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify B was removed temporarily
        List<Ranking> likedDuringRerank = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(likedDuringRerank).hasSize(2);

        // Cancel the re-rank (abandons it)
        mockMvc.perform(post("/cancel-add")
                        .session(session)
                        .with(oauthUser("oauth-rank-13")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify B is restored at its original position (1)
        List<Ranking> likedAfterCancel = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(likedAfterCancel).hasSize(3);
        assertThat(likedAfterCancel.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(likedAfterCancel.get(0).getPosition()).isEqualTo(0);
        assertThat(likedAfterCancel.get(1).getBook().getTitle()).isEqualTo("Book B");
        assertThat(likedAfterCancel.get(1).getPosition()).isEqualTo(1);
        assertThat(likedAfterCancel.get(2).getBook().getTitle()).isEqualTo("Book C");
        assertThat(likedAfterCancel.get(2).getPosition()).isEqualTo(2);

        // Verify no ranking state left
        assertThat(getRankingState(session)).isNull();
    }

}
