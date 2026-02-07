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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RankingFlowTest extends BaseIntegrationTest {

    @Test
    void addToEmptyCategory() throws Exception {
        User user = createOAuthUser("ranker1", "oauth-rank-1");

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-rank-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .with(oauthUser("oauth-rank-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Dune");
        assertThat(liked.get(0).getPosition()).isEqualTo(0);

        assertThat(rankingStateRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void binarySearchRanking() throws Exception {
        User user = createOAuthUser("ranker2", "oauth-rank-2");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL3W")
                        .param("bookName", "Book C")
                        .param("author", "Author C")
                        .with(oauthUser("oauth-rank-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .with(oauthUser("oauth-rank-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = rankingStateRepository.findById(user.getId()).orElseThrow();
        assertThat(state.getCategory()).isEqualTo(BookCategory.LIKED);
        assertThat(state.getLowIndex()).isEqualTo(0);
        assertThat(state.getHighIndex()).isEqualTo(1);

        mockMvc.perform(post("/choose")
                        .param("choice", "new")
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

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL3W")
                        .param("bookName", "Book C")
                        .param("author", "Author C")
                        .with(oauthUser("oauth-rank-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .with(oauthUser("oauth-rank-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/choose")
                        .param("choice", "existing")
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

        Book dune = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        Ranking ranking = addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/direct-review")
                        .param("bookId", String.valueOf(ranking.getId()))
                        .with(oauthUser("oauth-rank-5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        RankingState state = rankingStateRepository.findById(user.getId()).orElseThrow();
        assertThat(state.getMode()).isEqualTo(RankingMode.REVIEW);
        assertThat(state.getBookIdBeingReviewed()).isEqualTo(ranking.getId());

        mockMvc.perform(post("/save-review")
                        .param("review", "A masterpiece")
                        .with(oauthUser("oauth-rank-5")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Ranking updated = rankingRepository.findById(ranking.getId()).orElseThrow();
        assertThat(updated.getReview()).isEqualTo("A masterpiece");
        assertThat(rankingStateRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void wantToReadThenRank() throws Exception {
        User user = createOAuthUser("ranker6", "oauth-rank-6");

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-rank-6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);

        mockMvc.perform(post("/mark-as-read")
                        .param("bookId", String.valueOf(wtr.get(0).getId()))
                        .with(oauthUser("oauth-rank-6")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED)).isEmpty();

        RankingState state = rankingStateRepository.findById(user.getId()).orElseThrow();
        assertThat(state.getTitleBeingRanked()).isEqualTo("Dune");

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
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

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-rank-7")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .param("review", "My favorite book ever")
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

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        mockMvc.perform(post("/direct-rerank")
                        .param("bookId", String.valueOf(rB.getId()))
                        .with(oauthUser("oauth-rank-8")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book C");

        RankingState state = rankingStateRepository.findById(user.getId()).orElseThrow();
        assertThat(state.getTitleBeingRanked()).isEqualTo("Book B");
        assertThat(state.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(state.getOriginalCategory()).isEqualTo(BookCategory.LIKED);
        assertThat(state.getOriginalPosition()).isEqualTo(1);
    }

    @Test
    void selectBookSavesEditionOlid() throws Exception {
        User user = createOAuthUser("ranker10", "oauth-rank-10");

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .param("editionOlid", "OL999M")
                        .with(oauthUser("oauth-rank-10")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
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

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .param("firstPublishYear", "1965")
                        .with(oauthUser("oauth-rank-11")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
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
                        .with(oauthUser("oauth-rank-13")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify B was removed temporarily
        List<Ranking> likedDuringRerank = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(likedDuringRerank).hasSize(2);

        // Cancel the re-rank (abandons it)
        mockMvc.perform(post("/cancel-add")
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
        assertThat(rankingStateRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void markAsReadClearsEditionCache() throws Exception {
        User user = createOAuthUser("ranker15", "oauth-rank-15");

        // Add two books to want-to-read
        Book bookA = createVerifiedBook("OL500W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL600W", "Book B", "Author B");
        Ranking rA = addRanking(user.getId(), bookA, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);
        Ranking rB = addRanking(user.getId(), bookB, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 1);

        MockHttpSession session = new MockHttpSession();

        // Mark book A as read
        mockMvc.perform(post("/mark-as-read")
                        .param("bookId", String.valueOf(rA.getId()))
                        .session(session)
                        .with(oauthUser("oauth-rank-15")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Simulate what /rank/edition does: cache editions in session
        session.setAttribute("cachedEditions", List.of(
                new OpenLibraryService.EditionResult("OL500M", "Book A Edition", null, null, null, null)));
        session.setAttribute("editionPage", 0);

        // Now mark book B as read - should clear stale cache
        mockMvc.perform(post("/mark-as-read")
                        .param("bookId", String.valueOf(rB.getId()))
                        .session(session)
                        .with(oauthUser("oauth-rank-15")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify cached editions were cleared
        assertThat(session.getAttribute("cachedEditions")).isNull();
        assertThat(session.getAttribute("editionPage")).isNull();

        // Verify the ranking state points to book B, not book A
        RankingState state = rankingStateRepository.findById(user.getId()).orElseThrow();
        assertThat(state.getTitleBeingRanked()).isEqualTo("Book B");
        assertThat(state.getWorkOlidBeingRanked()).isEqualTo("OL600W");
    }

    @Test
    void selectBookClearsEditionCache() throws Exception {
        User user = createOAuthUser("ranker16", "oauth-rank-16");

        MockHttpSession session = new MockHttpSession();

        // Select book A
        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL700W")
                        .param("bookName", "Book A")
                        .param("author", "Author A")
                        .session(session)
                        .with(oauthUser("oauth-rank-16")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Simulate cached editions from /rank/edition
        session.setAttribute("cachedEditions", List.of(
                new OpenLibraryService.EditionResult("OL700M", "Book A Edition", null, null, null, null)));

        // Select book B - should clear stale cache
        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL800W")
                        .param("bookName", "Book B")
                        .param("author", "Author B")
                        .session(session)
                        .with(oauthUser("oauth-rank-16")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(session.getAttribute("cachedEditions")).isNull();
    }

    @Test
    void selectEditionUpdatesBookEditionOlid() throws Exception {
        User user = createOAuthUser("ranker14", "oauth-rank-14");

        // Create a book with initial editionOlid (simulates what happens from search)
        Book book = bookRepository.save(new Book("OL200W", "OL200M-original", "Test Book", "Test Author", null, null));
        assertThat(book.getEditionOlid()).isEqualTo("OL200M-original");

        // Set up RankingState as if user came from /select-book
        RankingState rankingState = new RankingState(user.getId(), "OL200W", "Test Book", "Test Author", null, null);
        rankingStateRepository.save(rankingState);

        // User selects a different edition
        mockMvc.perform(post("/select-edition")
                        .param("editionOlid", "OL200M-selected")
                        .param("isbn13", "9781234567890")
                        .param("title", "Test Book - Selected Edition")
                        .with(oauthUser("oauth-rank-14")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify the book's editionOlid was updated to the selected one
        Book updatedBook = bookRepository.findByWorkOlid("OL200W").orElseThrow();
        assertThat(updatedBook.getEditionOlid()).isEqualTo("OL200M-selected");
        assertThat(updatedBook.getIsbn13()).isEqualTo("9781234567890");
        assertThat(updatedBook.getTitle()).isEqualTo("Test Book - Selected Edition");
    }
}
