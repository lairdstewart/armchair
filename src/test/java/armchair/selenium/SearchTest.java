package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.User;
import armchair.service.OpenLibraryService.BookResult;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class SearchTest extends BaseSeleniumTest {

    @Test
    void searchBooksFindsResults() {
        when(openLibraryService.searchBooks(anyString(), anyInt())).thenReturn(List.of(
                new BookResult("OL1W", "OL1M", "Dune", "Frank Herbert", 1965, null, 42),
                new BookResult("OL2W", "OL2M", "Dune Messiah", "Frank Herbert", 1969, null, 15)
        ));

        login("searcher1");
        navigateTo("/search?type=books&query=Dune");

        assertTextPresent("Dune");
        assertTextPresent("Frank Herbert");
        assertTextPresent("Dune Messiah");
    }

    @Test
    void searchProfilesFindsUser() {
        User other = new User("bookworm42", "other-subject", "google");
        userRepository.save(other);

        login("searcher2");
        navigateTo("/search?type=profiles&query=bookworm");

        assertTextPresent("bookworm42");
    }

    @Test
    void searchTabsSwitchCorrectly() {
        when(openLibraryService.searchBooks(anyString(), anyInt())).thenReturn(List.of(
                new BookResult("OL1W", "OL1M", "Test Book", "Test Author", 2020, null, 5)
        ));

        User other = new User("tabuser", "tab-subject", "google");
        userRepository.save(other);

        login("searcher3");

        // Books tab — search for something
        navigateTo("/search?type=books&query=Test");
        assertTextPresent("Test Book");

        // Profiles tab
        navigateTo("/search?type=profiles&query=tabuser");
        WebElement profilesTab = driver.findElement(By.id("stab-profiles"));
        assertThat(profilesTab.getCssValue("display")).isNotEqualTo("none");
        assertTextPresent("tabuser");

        // Books tab should be hidden
        WebElement booksTab = driver.findElement(By.id("stab-books"));
        assertThat(booksTab.getCssValue("display")).isEqualTo("none");

        // Curated lists tab
        navigateTo("/search?type=curated");
        WebElement curatedTab = driver.findElement(By.id("stab-curated"));
        assertThat(curatedTab.getCssValue("display")).isNotEqualTo("none");

        WebElement booksTabAgain = driver.findElement(By.id("stab-books"));
        assertThat(booksTabAgain.getCssValue("display")).isEqualTo("none");
    }

    @Test
    void bookSearchPagination() {
        // Create 12 results — should span 3 pages at 5 per page
        List<BookResult> manyResults = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            manyResults.add(new BookResult(
                    "OL" + i + "W", "OL" + i + "M",
                    "Book " + i, "Author " + i,
                    2000 + i, null, i));
        }
        when(openLibraryService.searchBooks(anyString(), anyInt())).thenReturn(manyResults);

        login("searcher4");
        navigateTo("/search?type=books&query=Book");

        // First page shows books 1-5
        assertTextPresent("Book 1");
        assertTextPresent("Book 5");
        List<String> page1Titles = getDisplayedBookTitles();
        assertThat(page1Titles).hasSize(5);
        assertThat(page1Titles).doesNotContain("Book 6", "Book 7");

        // Pagination links should be present — page 2 link "6-10"
        WebElement page2Link = driver.findElement(By.xpath(
                "//a[contains(@href, 'page=1') and contains(text(), '6-10')]"));
        assertThat(page2Link).isNotNull();

        // Click page 2
        page2Link.click();
        webWait().until(ExpectedConditions.urlContains("page=1"));

        assertTextPresent("Book 6");
        assertTextPresent("Book 10");

        // Page 2 should not show page-1-only books (use exact title match)
        List<String> page2Titles = getDisplayedBookTitles();
        assertThat(page2Titles).doesNotContain("Book 1", "Book 2", "Book 3", "Book 4", "Book 5");

        // Click page 3 link "11-12"
        WebElement page3Link = driver.findElement(By.xpath(
                "//a[contains(@href, 'page=2') and contains(text(), '11-12')]"));
        page3Link.click();
        webWait().until(ExpectedConditions.urlContains("page=2"));

        assertTextPresent("Book 11");
        assertTextPresent("Book 12");

        List<String> page3Titles = getDisplayedBookTitles();
        assertThat(page3Titles).doesNotContain("Book 5", "Book 6");
    }
}
