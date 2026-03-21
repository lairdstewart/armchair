package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.service.OpenLibraryService.BookResult;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class WantToReadTest extends BaseSeleniumTest {

    @Test
    void addBookToWantToRead() {
        when(openLibraryService.searchBooks(anyString(), anyInt())).thenReturn(List.of(
                new BookResult("OL1W", "OL1M", "Dune", "Frank Herbert", 1965, null, 42)
        ));

        User user = createUserAndLogin("wtr1");

        // Search for a book using the old search page (has direct "+" button)
        navigateTo("/search-books");
        driver.findElement(By.name("query")).sendKeys("Dune");
        clickButton("Search");

        assertTextPresent("Dune");
        assertTextPresent("Frank Herbert");

        // Click "+" to add book
        driver.findElement(By.cssSelector(".add-book-btn")).click();

        // On categorize page, select "want to read"
        // (bookshelf radio is required by HTML validation, so select one first — server ignores it for want-to-read)
        assertTextPresent("Categorize Dune");
        selectRadio("bookshelf", "fiction");
        selectRadio("category", "want-to-read");
        clickButton("Continue");

        // Should redirect to my-books with Want to Read tab selected
        assertOnPath("/my-books");

        // Verify book appears on Want to Read shelf
        selectRadio("typeSelector", "WANT_TO_READ");
        assertTextPresent("Dune");

        // Verify in database
        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void removeFromWantToRead() {
        User user = createUserAndLogin("wtr2");

        Book book = createVerifiedBook("OL1W", "The Great Gatsby", "F. Scott Fitzgerald");
        addRanking(user.getId(), book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);

        navigateTo("/my-books?selectedBookshelf=WANT_TO_READ");

        assertTextPresent("The Great Gatsby");

        // Click remove — triggers a confirm dialog
        driver.findElement(By.xpath(
                "//div[@id='tab-WANT_TO_READ']//button[normalize-space(text())='remove']"
        )).click();

        // Accept the confirmation dialog
        Alert alert = webWait().until(d -> d.switchTo().alert());
        alert.accept();

        // Should stay on want-to-read tab
        assertOnPath("/my-books");

        // Verify book is removed from database
        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).isEmpty();
    }

    @Test
    void markAsReadFromWantToRead() {
        User user = createUserAndLogin("wtr3");

        Book existingBook = createVerifiedBook("OL1W", "Already Read", "Author A");
        addRanking(user.getId(), existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);

        Book wtrBook = createVerifiedBook("OL2W", "Neuromancer", "William Gibson");
        addRanking(user.getId(), wtrBook, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);

        navigateTo("/my-books?selectedBookshelf=WANT_TO_READ");

        assertTextPresent("Neuromancer");

        // Click "rank" to mark as read
        driver.findElement(By.xpath(
                "//div[@id='tab-WANT_TO_READ']//button[normalize-space(text())='rank']"
        )).click();

        // Should go to categorize page
        assertTextPresent("Categorize Neuromancer");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");
        clickButton("Continue");

        // Pairwise comparison — choose "new" until done
        chooseNewUntilDone();

        assertOnPath("/my-books");

        // Verify book moved from Want to Read to Fiction/Liked
        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).isEmpty();

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Neuromancer");
    }
}
