package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EditBookTest extends BaseSeleniumTest {

    @Test
    void editPage_displaysBookDetails() {
        User user = createUserAndLogin("edit1");

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickEditLink("Dune");

        assertTextPresent("Edit Dune");
        assertRadioSelected("bookshelf", "fiction");
        assertRadioSelected("category", "liked");
    }

    @Test
    void editPage_saveReview_returnsToLibrary() {
        User user = createUserAndLogin("edit2");

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickEditLink("Dune");

        driver.findElement(By.id("review")).sendKeys("Great book");
        clickButton("Save");

        assertOnPath("/my-books");

        Ranking ranking = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED).get(0);
        assertThat(ranking.getReview()).isEqualTo("Great book");
    }

    @Test
    void editPage_changeBookshelf_triggersRanking() {
        User user = createUserAndLogin("edit3");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.NONFICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickEditLink("Book A");

        selectRadio("bookshelf", "nonfiction");
        clickButton("Save");

        // Should go to pairwise comparison since nonfiction/liked already has a book
        assertTextPresent("Which was better?");
        chooseInComparison("new");

        assertOnPath("/my-books");

        List<Ranking> nfLiked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, BookCategory.LIKED);
        assertThat(nfLiked).hasSize(2);
        assertThat(nfLiked.get(0).getBook().getTitle()).isEqualTo("Book A");

        List<Ranking> ficLiked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(ficLiked).isEmpty();
    }

    @Test
    void editPage_changeCategory_triggersRanking() {
        User user = createUserAndLogin("edit4");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.OK, 0);

        navigateTo("/my-books");
        clickEditLink("Book A");

        selectRadio("category", "ok");
        clickButton("Save");

        // Should go to pairwise comparison since fiction/ok already has a book
        assertTextPresent("Which was better?");
        chooseInComparison("new");

        assertOnPath("/my-books");

        List<Ranking> ficOk = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.OK);
        assertThat(ficOk).hasSize(2);
        assertThat(ficOk.get(0).getBook().getTitle()).isEqualTo("Book A");
    }

    @Test
    void editPage_moveToWantToRead() {
        User user = createUserAndLogin("edit5");

        Book book = createVerifiedBook("OL1W", "Book A", "Author A");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickEditLink("Book A");

        selectRadio("bookshelf", "want-to-read");
        clickButton("Save");

        assertOnPath("/my-books");

        List<Ranking> ficLiked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(ficLiked).isEmpty();

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).hasSize(1);
        assertThat(wtr.get(0).getBook().getTitle()).isEqualTo("Book A");
    }

    @Test
    void editPage_wantToReadBook_saveReview() {
        User user = createUserAndLogin("edit6");

        Book book = createVerifiedBook("OL1W", "Book A", "Author A");
        addRanking(user.getId(), book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);

        navigateTo("/my-books");
        selectRadio("typeSelector", "WANT_TO_READ");
        clickEditLink("Book A");

        assertTextPresent("Edit Book A");
        assertRadioSelected("bookshelf", "want-to-read");

        driver.findElement(By.id("review")).sendKeys("Can't wait to read this");
        clickButton("Save");

        assertOnPath("/my-books");

        Ranking ranking = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED).get(0);
        assertThat(ranking.getReview()).isEqualTo("Can't wait to read this");
    }

    @Test
    void editPage_rerank_skipsCategorizePage() {
        User user = createUserAndLogin("edit7");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/my-books");
        clickEditLink("Book A");
        clickButton("Re-rank");

        // Should go directly to pairwise comparison, not categorize
        assertTextPresent("Which was better?");
        assertTextNotPresent("Categorize");

        chooseInComparison("existing");

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book A");
    }

    @Test
    void editPage_changeToEmptyList_insertsDirectly() {
        User user = createUserAndLogin("edit8");

        Book book = createVerifiedBook("OL1W", "Book A", "Author A");
        addRanking(user.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickEditLink("Book A");

        selectRadio("bookshelf", "nonfiction");
        clickButton("Save");

        // No books in nonfiction/liked — should insert directly, no comparison needed
        assertOnPath("/my-books");

        List<Ranking> nfLiked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, BookCategory.LIKED);
        assertThat(nfLiked).hasSize(1);
        assertThat(nfLiked.get(0).getBook().getTitle()).isEqualTo("Book A");
    }
}
