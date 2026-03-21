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

class RankingFlowTest extends BaseSeleniumTest {

    @Test
    void addBookViaDb_categorizeAndRank_fullFlow() {
        User user = createUserAndLogin("flow1");

        Book bookA = createVerifiedBook("OL1W", "Alpha Book", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Beta Book", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        Book bookC = createVerifiedBook("OL3W", "Gamma Book", "Author C");
        addRanking(user.getId(), bookC, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        navigateTo("/my-books");
        selectRadio("typeSelector", "UNRANKED");

        clickBookAction("Gamma Book", "categorize");

        assertTextPresent("Categorize Gamma Book");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");
        clickButton("Continue");

        // Pairwise comparison — choose "new" until done
        chooseNewUntilDone();

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(3);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Gamma Book");

        List<Ranking> unranked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.UNRANKED, BookCategory.UNRANKED);
        assertThat(unranked).isEmpty();
    }

    @Test
    void wantToRead_thenMarkAsRead() {
        User user = createUserAndLogin("flow2");

        Book book = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        addRanking(user.getId(), book, Bookshelf.WANT_TO_READ, BookCategory.UNRANKED, 0);

        navigateTo("/my-books");
        selectRadio("typeSelector", "WANT_TO_READ");

        driver.findElement(By.xpath(
                "//div[@id='tab-WANT_TO_READ']//button[normalize-space(text())='rank']"
        )).click();

        assertTextPresent("Categorize Dune");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");
        clickButton("Continue");

        assertOnPath("/my-books");

        List<Ranking> wtr = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.WANT_TO_READ, BookCategory.UNRANKED);
        assertThat(wtr).isEmpty();

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Dune");
    }

    @Test
    void rerank_changesPosition() {
        User user = createUserAndLogin("flow3");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        navigateTo("/my-books");

        List<String> titles = getDisplayedBookTitles();
        assertThat(titles).containsExactly("Book A", "Book B", "Book C");

        clickBookAction("Book C", "re-rank");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");
        clickButton("Continue");

        // Choose "new" until done to put Book C at top
        chooseNewUntilDone();

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(3);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book C");
    }

    @Test
    void review_saveAndDisplay() {
        User user = createUserAndLogin("flow4");

        Book dune = createVerifiedBook("OL1W", "Dune", "Frank Herbert");
        addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickBookAction("Dune", "write review");

        assertTextPresent("Dune");

        driver.findElement(By.id("review")).sendKeys("A science fiction masterpiece");
        clickButton("Done");

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getReview()).isEqualTo("A science fiction masterpiece");

        // Review text is inside a collapsed <details> element — check page source
        assertThat(driver.getPageSource()).contains("A science fiction masterpiece");
    }

    @Test
    void unrankedBook_rankAsLiked() {
        User user = createUserAndLogin("flow5");

        Book book = createVerifiedBook("OL1W", "Future Read", "Author X");
        addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        navigateTo("/my-books");
        selectRadio("typeSelector", "UNRANKED");

        clickBookAction("Future Read", "categorize");

        assertTextPresent("Categorize Future Read");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");
        clickButton("Continue");

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Future Read");
    }

    @Test
    void rankAll_processesMultipleBooks() {
        User user = createUserAndLogin("flow6");

        Book bookA = createVerifiedBook("OL1W", "Unranked A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Unranked B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.UNRANKED, BookCategory.UNRANKED, 1);

        navigateTo("/my-books");
        selectRadio("typeSelector", "UNRANKED");
        clickButton("categorize all");

        assertTextPresent("Categorize Unranked A");
        assertTextPresent("uncategorized book");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");
        clickButton("Continue");

        assertTextPresent("Categorize Unranked B");

        selectRadio("bookshelf", "nonfiction");
        selectRadio("category", "ok");
        clickButton("Continue");

        assertOnPath("/my-books");

        List<Ranking> ficLiked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(ficLiked).hasSize(1);
        assertThat(ficLiked.get(0).getBook().getTitle()).isEqualTo("Unranked A");

        List<Ranking> nfOk = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, BookCategory.OK);
        assertThat(nfOk).hasSize(1);
        assertThat(nfOk.get(0).getBook().getTitle()).isEqualTo("Unranked B");
    }

    @Test
    void categorizeWithReview() {
        User user = createUserAndLogin("flow7");

        Book book = createVerifiedBook("OL1W", "Reviewed Book", "Author R");
        addRanking(user.getId(), book, Bookshelf.UNRANKED, BookCategory.UNRANKED, 0);

        navigateTo("/my-books");
        selectRadio("typeSelector", "UNRANKED");

        clickBookAction("Reviewed Book", "categorize");

        selectRadio("bookshelf", "fiction");
        selectRadio("category", "liked");

        // Wait for the textarea to become enabled
        webWait().until(d -> d.findElement(By.id("review")).isEnabled());

        driver.findElement(By.id("review")).sendKeys("Excellent read");
        clickButton("Continue");

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(1);
        assertThat(liked.get(0).getReview()).isEqualTo("Excellent read");
    }
}
