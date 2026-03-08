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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GuestMigrationTest extends BaseIntegrationTest {

    @Test
    void basicMigration() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        Long guestUserId = (Long) session.getAttribute("guestUserId");
        assertThat(guestUserId).isNotNull();

        Book dune = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        Book orwell = createVerifiedBook("OL456W", "1984", "George Orwell");
        addRanking(guestUserId, dune, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(guestUserId, orwell, Bookshelf.FICTION, BookCategory.LIKED, 1);

        assertThat(rankingRepository.findByUserId(guestUserId)).hasSize(2);

        User oauthUser = createOAuthUser("migrator", "oauth-migrate-1");

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser("oauth-migrate-1")))
                .andExpect(status().isOk());

        assertThat(rankingRepository.findByUserId(oauthUser.getId())).hasSize(2);
        assertThat(rankingRepository.findByUserId(guestUserId)).isEmpty();
        assertThat(userRepository.findById(guestUserId)).isEmpty();

        // Verify session cleanup: guestUserId removed from session
        assertThat(session.getAttribute("guestUserId")).isNull();
    }

    @Test
    void migrationWithRankingState() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        Long guestUserId = (Long) session.getAttribute("guestUserId");

        Book dune = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(guestUserId, dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        RankingState state = new RankingState("OL789W", "Neuromancer", "William Gibson",
                Bookshelf.FICTION, BookCategory.LIKED, 0, 0, 0);
        state.setReviewBeingRanked("Great book");
        state.setMode(RankingMode.CATEGORIZE);
        state.setRankAll(true);
        setRankingState(session, state);

        User oauthUser = createOAuthUser("migrator2", "oauth-migrate-2");

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser("oauth-migrate-2")))
                .andExpect(status().isOk());

        // RankingState is in the session, so it survives login (session persists)
        RankingState migratedState = getRankingState(session);
        assertThat(migratedState).isNotNull();
        assertThat(migratedState.getTitleBeingRanked()).isEqualTo("Neuromancer");
        assertThat(migratedState.getAuthorBeingRanked()).isEqualTo("William Gibson");
        assertThat(migratedState.getReviewBeingRanked()).isEqualTo("Great book");
        assertThat(migratedState.getMode()).isEqualTo(RankingMode.CATEGORIZE);
        assertThat(migratedState.isRankAll()).isTrue();
    }

    @Test
    void migrationWithReviews() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        Long guestUserId = (Long) session.getAttribute("guestUserId");

        Book dune = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRankingWithReview(guestUserId, dune, Bookshelf.FICTION, BookCategory.LIKED, 0, "My favorite book");

        User oauthUser = createOAuthUser("migrator3", "oauth-migrate-3");

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser("oauth-migrate-3")))
                .andExpect(status().isOk());

        List<Ranking> rankings = rankingRepository.findByUserId(oauthUser.getId());
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getReview()).isEqualTo("My favorite book");
    }

    @Test
    void sessionCleanupAfterMigration() throws Exception {
        MockHttpSession session = guestSession();
        mockMvc.perform(get("/my-books").session(session)).andExpect(status().isOk());

        Long guestUserId = (Long) session.getAttribute("guestUserId");
        assertThat(guestUserId).isNotNull();

        Book dune = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(guestUserId, dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        User oauthUser = createOAuthUser("migrator4", "oauth-migrate-4");

        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser("oauth-migrate-4")))
                .andExpect(status().isOk());

        // Verify session no longer holds guest user ID
        assertThat(session.getAttribute("guestUserId")).isNull();

        // Verify guest user record is deleted from DB
        assertThat(userRepository.findById(guestUserId)).isEmpty();

        // Subsequent requests should use OAuth user, not create a new guest
        mockMvc.perform(get("/my-books").session(session)
                        .with(oauthUser("oauth-migrate-4")))
                .andExpect(status().isOk());
        assertThat(rankingRepository.findByUserId(oauthUser.getId())).hasSize(1);
    }

    @Test
    void noDoubleMigration() throws Exception {
        User oauthUser = createOAuthUser("nodouble", "oauth-no-double");
        Book dune = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(oauthUser.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(get("/my-books")
                        .with(oauthUser("oauth-no-double")))
                .andExpect(status().isOk());

        assertThat(rankingRepository.findByUserId(oauthUser.getId())).hasSize(1);
    }
}
