package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.service.OpenLibraryService.EditionResult;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class EditionBrowsingTest extends BaseSeleniumTest {

    @Test
    void browseEditionsOfWork() {
        when(openLibraryService.getEditionsForWork(eq("OL100W"), anyInt(), anyInt())).thenReturn(List.of(
                new EditionResult("OL1M", "Dune (1965 Ace)", "9780441172719", 12345, "Ace", "1965"),
                new EditionResult("OL2M", "Dune (2005 Ace)", "9780441013593", 67890, "Ace Books", "2005"),
                new EditionResult("OL3M", "Dune (1984 Berkley)", "9780425074985", null, "Berkley", "1984")
        ));

        login("edition1");
        navigateTo("/editions/OL100W?title=Dune&author=Frank+Herbert");

        assertTextPresent("Editions of Dune");
        assertTextPresent("Dune (1965 Ace)");
        assertTextPresent("Dune (2005 Ace)");
        assertTextPresent("Dune (1984 Berkley)");
        assertTextPresent("Ace");
        assertTextPresent("1965");
    }

    @Test
    void changeEditionOfExistingBook() {
        when(openLibraryService.getEditionsForWork(eq("OL200W"), anyInt(), anyInt())).thenReturn(List.of(
                new EditionResult("OL10M", "Original Edition", "9781111111111", 100, "Publisher A", "2000"),
                new EditionResult("OL20M", "Updated Edition", "9782222222222", 200, "Publisher B", "2020")
        ));

        User user = login("edition2");
        Book book = createVerifiedBook("OL200W", "Original Edition", "Test Author");
        Ranking ranking = addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/editions/OL200W?title=Original+Edition&author=Test+Author&changeBookId=" + ranking.getId());

        assertTextPresent("Editions of Original Edition");
        assertTextPresent("Original Edition");
        assertTextPresent("Updated Edition");

        // Click "select" on the Updated Edition
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//div[contains(@class,'book-entry') and .//a[contains(text(),'Updated Edition')]]//button[normalize-space(text())='select']"
        ))).click();

        // Should redirect to my-books
        assertOnPath("/my-books");

        // Verify the book metadata was updated
        Book updatedBook = bookRepository.findById(book.getId()).orElseThrow();
        assertThat(updatedBook.getEditionOlid()).isEqualTo("OL20M");
        assertThat(updatedBook.getTitle()).isEqualTo("Updated Edition");
        assertThat(updatedBook.getCoverId()).isEqualTo(200);
    }
}
