package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Alert;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookRemovalTest extends BaseSeleniumTest {

    @Test
    void removeRankedBook() {
        User user = createUserAndLogin("removal1");

        Book bookA = createVerifiedBook("OL1W", "Alpha Book", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Beta Book", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Gamma Book", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        navigateTo("/my-books");

        List<String> titlesBefore = getDisplayedBookTitles();
        assertThat(titlesBefore).containsExactly("Alpha Book", "Beta Book", "Gamma Book");

        // Click edit then remove on the middle book and accept the confirm dialog
        clickEditLink("Beta Book");
        clickButton("Remove");
        Alert alert = driver.switchTo().alert();
        alert.accept();

        assertOnPath("/my-books");
        assertTextNotPresent("Beta Book");

        List<String> titlesAfter = getDisplayedBookTitles();
        assertThat(titlesAfter).containsExactly("Alpha Book", "Gamma Book");

        // Verify positions are re-ordered in the database
        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Alpha Book");
        assertThat(liked.get(0).getPosition()).isEqualTo(0);
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Gamma Book");
        assertThat(liked.get(1).getPosition()).isEqualTo(1);
    }

    @Test
    void removeFromWantToRead() {
        User user = createUserAndLogin("removal2");

        Book bookA = createVerifiedBook("OL1W", "WTR Alpha", "Author A");
        Book bookB = createVerifiedBook("OL2W", "WTR Beta", "Author B");
        Book bookC = createVerifiedBook("OL3W", "WTR Gamma", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 2);

        navigateTo("/my-books?selectedBookshelf=WANT_TO_READ");
        selectRadio("typeSelector", "WANT_TO_READ");

        assertTextPresent("WTR Alpha");
        assertTextPresent("WTR Beta");
        assertTextPresent("WTR Gamma");

        // Remove first book via edit page
        clickEditLink("WTR Alpha");
        clickButton("Remove");
        Alert alert1 = driver.switchTo().alert();
        alert1.accept();

        assertOnPath("/my-books");
        assertTextNotPresent("WTR Alpha");
        assertTextPresent("WTR Beta");
        assertTextPresent("WTR Gamma");

        // Remove second book via edit page
        clickEditLink("WTR Beta");
        clickButton("Remove");
        Alert alert2 = driver.switchTo().alert();
        alert2.accept();

        assertOnPath("/my-books");
        assertTextNotPresent("WTR Alpha");
        assertTextNotPresent("WTR Beta");
        assertTextPresent("WTR Gamma");

        // Verify only one book remains
        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getTitle()).isEqualTo("WTR Gamma");
        assertThat(wtr.get(0).getPosition()).isEqualTo(0);
    }
}
