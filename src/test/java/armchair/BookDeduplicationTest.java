package armchair;

import armchair.entity.Book;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookDeduplicationTest extends BaseIntegrationTest {

    @Test
    void sameWorkOlidReusesBook() throws Exception {
        User user = createOAuthUser("dedup1", "oauth-dedup-1");

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-dedup-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .with(oauthUser("oauth-dedup-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        long bookCountAfterFirst = bookRepository.count();

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL100W")
                        .param("bookName", "Dune")
                        .param("author", "Frank Herbert")
                        .with(oauthUser("oauth-dedup-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(bookRepository.count()).isEqualTo(bookCountAfterFirst);
    }

    @Test
    void enrichCoverEditionOlid() throws Exception {
        Book book = bookRepository.save(new Book("OL200W", null, "1984", "George Orwell", null));
        assertThat(book.getCoverEditionOlid()).isNull();

        User user = createOAuthUser("dedup2", "oauth-dedup-2");

        mockMvc.perform(post("/add-to-reading-list")
                        .param("workOlid", "OL200W")
                        .param("bookName", "1984")
                        .param("author", "George Orwell")
                        .param("coverEditionOlid", "OL200M")
                        .with(oauthUser("oauth-dedup-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Book updated = bookRepository.findByWorkOlid("OL200W").orElseThrow();
        assertThat(updated.getCoverEditionOlid()).isEqualTo("OL200M");
    }

    @Test
    void titleAuthorFallbackForUnverifiedBooks() throws Exception {
        MockHttpSession session = guestSession();
        String csv = "Title,Author\nDune,Frank Herbert\n";
        MockMultipartFile file = new MockMultipartFile("file", "g.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/import-goodreads").file(file).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        long bookCount = bookRepository.count();

        mockMvc.perform(multipart("/import-goodreads").file(file).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(bookRepository.count()).isEqualTo(bookCount);
    }

    @Test
    void titleAuthorMatchIsCaseInsensitive() throws Exception {
        MockHttpSession session = guestSession();
        String csv1 = "Title,Author\nDune,Frank Herbert\n";
        String csv2 = "Title,Author\ndune,frank herbert\n";

        mockMvc.perform(multipart("/import-goodreads")
                        .file(new MockMultipartFile("file", "g.csv", "text/csv", csv1.getBytes(StandardCharsets.UTF_8)))
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        long bookCount = bookRepository.count();

        mockMvc.perform(multipart("/import-goodreads")
                        .file(new MockMultipartFile("file", "g.csv", "text/csv", csv2.getBytes(StandardCharsets.UTF_8)))
                        .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(bookRepository.count()).isEqualTo(bookCount);
    }

    @Test
    void newBookCreation() throws Exception {
        User user = createOAuthUser("dedup3", "oauth-dedup-3");

        long initialBookCount = bookRepository.count();

        mockMvc.perform(post("/select-book")
                        .param("workOlid", "OL999W")
                        .param("bookName", "New Book")
                        .param("author", "New Author")
                        .with(oauthUser("oauth-dedup-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/categorize")
                        .param("bookshelf", "fiction")
                        .param("category", "liked")
                        .with(oauthUser("oauth-dedup-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(bookRepository.count()).isEqualTo(initialBookCount + 1);
        assertThat(bookRepository.findByWorkOlid("OL999W")).isPresent();
    }
}
