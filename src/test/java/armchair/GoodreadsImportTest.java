package armchair;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoodreadsImportTest extends BaseIntegrationTest {

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "goodreads.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // --- Guest tests ---

    @Test
    void guestBasicImport() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\nDune,Frank Herbert\n1984,George Orwell\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(2);
        assertThat(rankings).allMatch(r -> r.getBookshelf() == Bookshelf.UNRANKED);
        assertThat(rankings).allMatch(r -> r.getCategory() == BookCategory.UNRANKED);

        assertThat(rankings.stream().map(r -> r.getBook().getTitle()).toList())
                .containsExactlyInAnyOrder("Dune", "1984");
    }

    @Test
    void guestAllBooksGoToUncategorized() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author,Exclusive Shelf\n" +
                "Dune,Frank Herbert,read\n" +
                "Neuromancer,William Gibson,to-read\n" +
                "Snow Crash,Neal Stephenson,currently-reading\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> all = rankingRepository.findAll();
        assertThat(all).hasSize(3);
        assertThat(all).allMatch(r -> r.getBookshelf() == Bookshelf.UNRANKED);
    }

    @Test
    void guestColonStripping() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\n\"Dune: The Machine Crusade\",Brian Herbert\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void guestSeriesStripping() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\n\"Dune Messiah (Dune #2)\",Frank Herbert\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Dune Messiah");
    }

    @Test
    void guestReviewImport() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author,My Review\nDune,Frank Herbert,A masterpiece of science fiction\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getReview()).isEqualTo("A masterpiece of science fiction");
    }

    @Test
    void guestReviewTruncation() throws Exception {
        MockHttpSession session = guestSession();
        String longReview = "x".repeat(6000);
        String csv = "Title,Author,My Review\nDune,Frank Herbert," + longReview + "\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getReview()).hasSize(5000);
    }

    @Test
    void guestEmptyTitleOrAuthorSkipped() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\n,Frank Herbert\nDune,\n\"\",\"\"\nDune,Frank Herbert\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void guestDedupOnReImport() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\nDune,Frank Herbert\n1984,George Orwell\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(rankingRepository.findAll()).hasSize(2);

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(rankingRepository.findAll()).hasSize(2);
    }

    @Test
    void guestMissingTitleColumn() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Book,Author\nDune,Frank Herbert\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findAll()).isEmpty();
    }

    @Test
    void guestQuotedFieldsWithCommas() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\n\"Guns, Germs, and Steel\",\"Diamond, Jared\"\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Guns, Germs, and Steel");
        assertThat(rankings.get(0).getBook().getAuthor()).isEqualTo("Diamond, Jared");
    }

    // --- OAuth tests ---

    @Test
    void oauthBasicImport() throws Exception {
        User user = createOAuthUser("testuser", "oauth-import-1");
        String csv = "Title,Author\nDune,Frank Herbert\n1984,George Orwell\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-import-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(2);
        assertThat(rankings).allMatch(r -> r.getBookshelf() == Bookshelf.UNRANKED);
    }

    @Test
    void oauthAllBooksGoToUncategorized() throws Exception {
        User user = createOAuthUser("testuser2", "oauth-import-2");
        String csv = "Title,Author,Exclusive Shelf\n" +
                "Dune,Frank Herbert,read\n" +
                "Neuromancer,William Gibson,to-read\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-import-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(2);
        assertThat(rankings).allMatch(r -> r.getBookshelf() == Bookshelf.UNRANKED);
    }

    @Test
    void oauthDedupOnReImport() throws Exception {
        User user = createOAuthUser("testuser3", "oauth-import-3");
        String csv = "Title,Author\nDune,Frank Herbert\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-import-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(rankingRepository.findByUserId(user.getId())).hasSize(1);

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-import-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(rankingRepository.findByUserId(user.getId())).hasSize(1);
    }

    @Test
    void positionsAreSequential() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\nBook A,Author A\nBook B,Author B\nBook C,Author C\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findAll();
        rankings.sort((a, b) -> a.getPosition() - b.getPosition());
        assertThat(rankings).hasSize(3);
        assertThat(rankings.get(0).getPosition()).isEqualTo(0);
        assertThat(rankings.get(1).getPosition()).isEqualTo(1);
        assertThat(rankings.get(2).getPosition()).isEqualTo(2);
    }
}
