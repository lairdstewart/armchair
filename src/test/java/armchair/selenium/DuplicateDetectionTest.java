package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.service.OpenLibraryService.BookResult;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DuplicateDetectionTest extends BaseSeleniumTest {

    private static final String EXISTING_WORK_OLID = "OL1W";
    private static final String EXISTING_TITLE = "Dune";
    private static final String EXISTING_AUTHOR = "Frank Herbert";

    /**
     * Pre-populate library with a verified book, add an unverified book,
     * mock the search to return the existing book, then navigate through
     * the resolve flow until the duplicate resolution page appears.
     */
    private User setupDuplicateScenario(String username) {
        User user = login(username);

        Book existingBook = createVerifiedBook(EXISTING_WORK_OLID, EXISTING_TITLE, EXISTING_AUTHOR);
        addRanking(user.getId(), existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        Book unverifiedBook = createUnverifiedBook("Dune Import", "F. Herbert");
        addRanking(user.getId(), unverifiedBook, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        BookResult dupeResult = new BookResult(
                EXISTING_WORK_OLID, "OL1M", EXISTING_TITLE, EXISTING_AUTHOR, 1965, null, 42);

        when(openLibraryService.searchByTitleAndAuthor(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(dupeResult));
        when(openLibraryService.searchBooks(anyString(), anyInt()))
                .thenReturn(List.of(dupeResult));

        navigateTo("/my-books");
        selectRadio("typeSelector", "UNRANKED");
        clickBookAction("Dune Import", "categorize");

        // On the resolve page, click the search result that matches the existing book
        assertTextPresent(EXISTING_TITLE);
        webWait().until(d -> !d.findElements(By.cssSelector("button.book-select-btn")).isEmpty());
        driver.findElement(By.cssSelector("button.book-select-btn")).click();

        // Should now see the duplicate resolution page
        assertTextPresent("already in your library");

        return user;
    }

    @Test
    void addDuplicateBookShowsResolution() {
        setupDuplicateScenario("dup1");

        assertTextPresent("already in your library");
        assertTextPresent(EXISTING_TITLE);

        // Both action buttons should be present
        assertThat(driver.findElements(By.xpath("//button[normalize-space(text())='Skip']"))).isNotEmpty();
        assertThat(driver.findElements(By.xpath("//button[normalize-space(text())='Re-rank']"))).isNotEmpty();
    }

    @Test
    void skipDuplicateReturnsToLibrary() {
        User user = setupDuplicateScenario("dup2");

        clickButton("Skip");

        assertOnPath("/my-books");

        // The existing ranked book should be unchanged
        List<Ranking> ficRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(ficRankings).hasSize(1);
        assertThat(ficRankings.get(0).getBook().getWorkOlid()).isEqualTo(EXISTING_WORK_OLID);
    }

    @Test
    void rerankDuplicateStartsNewRanking() {
        User user = setupDuplicateScenario("dup3");

        clickButton("Re-rank");

        // Should be on the categorize page for the existing book
        assertTextPresent("Categorize");
        assertTextPresent(EXISTING_TITLE);
        assertOnPath("/rank/categorize");

        // The old fiction ranking should have been removed (re-ranking starts fresh)
        List<Ranking> ficRankings = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(ficRankings).isEmpty();
    }
}
