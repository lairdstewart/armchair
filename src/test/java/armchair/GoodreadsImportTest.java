package armchair;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
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

    @Test
    void basicImport() throws Exception {
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
    void allBooksGoToUncategorized() throws Exception {
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
    void dedupOnReImport() throws Exception {
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
    void truncatedRowSkipped() throws Exception {
        User user = createOAuthUser("truncate-user", "oauth-truncate");
        String csv = "Title,Author\nDune,Frank Herbert\nIncomplete\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-truncate")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void emptyCsvImportsNothing() throws Exception {
        User user = createOAuthUser("empty-user", "oauth-empty");
        String csv = "";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-empty")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void headerOnlyCsvImportsNothing() throws Exception {
        User user = createOAuthUser("header-user", "oauth-header");
        String csv = "Title,Author\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-header")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void extraColumnsIgnored() throws Exception {
        User user = createOAuthUser("extra-user", "oauth-extra");
        String csv = "Title,Author,ISBN,Rating,Extra Column\nDune,Frank Herbert,123,5,whatever\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-extra")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void largeImport() throws Exception {
        User user = createOAuthUser("large-user", "oauth-large");
        StringBuilder csv = new StringBuilder("Title,Author\n");
        for (int i = 0; i < 100; i++) {
            csv.append("Book ").append(i).append(",Author ").append(i).append("\n");
        }

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv.toString()))
                        .with(oauthUser("oauth-large")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(100);
        rankings.sort((a, b) -> a.getPosition() - b.getPosition());
        for (int i = 0; i < 100; i++) {
            assertThat(rankings.get(i).getPosition()).isEqualTo(i);
        }
    }

    @Test
    void realGoodreadsFormat() throws Exception {
        User user = createOAuthUser("real-user", "oauth-real");
        String csv = "Book Id,Title,Author,Author l-f,Additional Authors,ISBN,ISBN13,My Rating,Average Rating," +
                "Publisher,Binding,Number of Pages,Year Published,Original Publication Year,Date Read,Date Added," +
                "Bookshelves,Bookshelves with positions,Exclusive Shelf,My Review,Spoiler,Private Notes,Read Count,Owned Copies\n" +
                "149105520,Going Infinite,Michael Lewis,\"Lewis, Michael\",,\"=\"\"1324074337\"\"\",\"=\"\"9781324074335\"\"\",4,3.83," +
                "W. W. Norton & Company,Hardcover,272,2023,2023,,2026/01/30,,,read,,,,0,0\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-real")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getBook().getTitle()).isEqualTo("Going Infinite");
        assertThat(rankings.get(0).getBook().getAuthor()).isEqualTo("Michael Lewis");
    }

    @Test
    void missingAuthorColumn() throws Exception {
        User user = createOAuthUser("missing-author-user", "oauth-missing-author");
        String csv = "Title,ISBN\nDune,12345\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-missing-author")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(rankingRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void positionsAreSequential() throws Exception {
        User user = createOAuthUser("positions-user", "oauth-positions");
        String csv = "Title,Author\nBook A,Author A\nBook B,Author B\nBook C,Author C\n";

        mockMvc.perform(multipart("/import-goodreads").file(csvFile(csv))
                        .with(oauthUser("oauth-positions")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Ranking> rankings = rankingRepository.findByUserId(user.getId());
        rankings.sort((a, b) -> a.getPosition() - b.getPosition());
        assertThat(rankings).hasSize(3);
        assertThat(rankings.get(0).getPosition()).isEqualTo(0);
        assertThat(rankings.get(1).getPosition()).isEqualTo(1);
        assertThat(rankings.get(2).getPosition()).isEqualTo(2);
    }
}
