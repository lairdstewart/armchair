package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.User;
import armchair.service.OpenLibraryService;
import armchair.service.RankingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DuplicatePreventionTest extends BaseIntegrationTest {

    private static int userCounter = 0;

    private User nextUser() {
        userCounter++;
        return createOAuthUser("dupprevent" + userCounter, "oauth-dp-" + userCounter);
    }

    @Test
    void editionsPageShowsViewInLibraryWhenUserHasBook() throws Exception {
        User user = nextUser();
        Book book = createVerifiedBook("OL123W", "Dune", "Frank Herbert");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);
        flushAndClear();

        MockHttpSession session = new MockHttpSession();
        List<OpenLibraryService.EditionResult> editions = List.of(
            new OpenLibraryService.EditionResult("OL456M", "Dune (1965)", null, null, "Chilton Books", "1965")
        );
        session.setAttribute("browseEditions_OL123W", editions);

        mockMvc.perform(get("/editions/OL123W")
                .param("title", "Dune")
                .param("author", "Frank Herbert")
                .session(session)
                .with(oauthUser("oauth-dp-" + userCounter)))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("view in library")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">rank<"))));
    }

    @Test
    void editionsPageShowsRankButtonsWhenUserDoesNotHaveBook() throws Exception {
        User user = nextUser();

        MockHttpSession session = new MockHttpSession();
        List<OpenLibraryService.EditionResult> editions = List.of(
            new OpenLibraryService.EditionResult("OL456M", "Dune (1965)", null, null, "Chilton Books", "1965")
        );
        session.setAttribute("browseEditions_OL123W", editions);

        mockMvc.perform(get("/editions/OL123W")
                .param("title", "Dune")
                .param("author", "Frank Herbert")
                .session(session)
                .with(oauthUser("oauth-dp-" + userCounter)))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(">rank<")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(">want to read<")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("view in library"))));
    }

    @Test
    void selectBookRedirectsWhenUserAlreadyHasBook() throws Exception {
        User user = nextUser();
        Book book = createVerifiedBook("OL789W", "1984", "George Orwell");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book")
                .param("workOlid", "OL789W")
                .param("bookName", "1984")
                .param("author", "George Orwell")
                .session(session)
                .with(oauthUser("oauth-dp-" + userCounter))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void selectBookEditionRedirectsWhenUserAlreadyHasBook() throws Exception {
        User user = nextUser();
        Book book = createVerifiedBook("OL101W", "Brave New World", "Aldous Huxley");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.OK, 0);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/select-book-edition")
                .param("workOlid", "OL101W")
                .param("bookName", "Brave New World")
                .param("author", "Aldous Huxley")
                .session(session)
                .with(oauthUser("oauth-dp-" + userCounter))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-books"));
    }

    @Test
    void insertBookAtPositionSkipsDuplicate() throws Exception {
        User user = nextUser();
        Book book = createVerifiedBook("OL202W", "Fahrenheit 451", "Ray Bradbury");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        long countBefore = rankingRepository.findByUserId(user.getId()).size();

        rankingService.insertBookAtPosition("OL202W", "Fahrenheit 451", "Ray Bradbury", null,
            Bookshelf.FICTION, BookCategory.LIKED, 0, user.getId(), null);

        long countAfter = rankingRepository.findByUserId(user.getId()).size();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Autowired
    private RankingService rankingService;

    @Autowired
    private EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
