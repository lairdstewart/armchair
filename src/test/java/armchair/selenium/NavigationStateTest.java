package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationStateTest extends BaseSeleniumTest {

    @Test
    void rankingStateCleared_whenNavigatingToLibrary() {
        User user = createUserAndLogin("nav1");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/my-books");

        clickEditLink("Book A");
        clickButton("Re-rank");
        // Re-rank now skips categorize and goes straight to comparison
        assertTextPresent("Which was better?");

        // Click Library in the navbar to clear state
        clickLibrary();

        assertOnPath("/my-books");
        assertTextPresent("Book A");
        assertTextPresent("Book B");
        assertTextNotPresent("Which was better?");
    }

    @Test
    void rankingStateSurvives_withinWorkflow() {
        User user = createUserAndLogin("nav2");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/my-books");

        clickEditLink("Book B");
        clickButton("Re-rank");

        // Goes straight to pairwise comparison
        assertTextPresent("Which was better?");
        chooseInComparison("new");

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(2);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book B");
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book A");
    }

    @Test
    void abandonedRerank_restoresBook() {
        User user = createUserAndLogin("nav3");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        Book bookC = createVerifiedBook("OL3W", "Book C", "Author C");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);
        addRanking(user.getId(), bookC, Bookshelf.FICTION, BookCategory.LIKED, 2);

        navigateTo("/my-books");
        clickEditLink("Book B");
        clickButton("Re-rank");
        // Now on comparison page — click cancel to abandon
        clickButton("cancel");

        assertOnPath("/my-books");

        List<Ranking> liked = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.FICTION, BookCategory.LIKED);
        assertThat(liked).hasSize(3);
        assertThat(liked.get(0).getBook().getTitle()).isEqualTo("Book A");
        assertThat(liked.get(1).getBook().getTitle()).isEqualTo("Book B");
        assertThat(liked.get(2).getBook().getTitle()).isEqualTo("Book C");
    }

    @Test
    void unauthenticatedAccess_redirectsToLogin() {
        navigateTo("/my-books");
        assertOnPath("/login");
        assertTextPresent("Sign in with Google");

        User user = login("nav4");

        navigateTo("/my-books");
        assertOnPath("/my-books");
    }

    @Test
    void multipleFlowsInSequence_noStateLeak() {
        User user = createUserAndLogin("nav5");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);

        navigateTo("/my-books");
        clickEditLink("Book A");
        clickButton("Re-rank");

        // No other books in fiction/liked — inserts directly
        assertOnPath("/my-books");

        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/my-books");
        clickEditLink("Book B");
        // Change to nonfiction before re-ranking
        selectRadio("bookshelf", "nonfiction");
        selectRadio("category", "ok");
        clickButton("Re-rank");

        // No other books in nonfiction/ok — inserts directly
        assertOnPath("/my-books");

        List<Ranking> nfOk = rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                user.getId(), Bookshelf.NONFICTION, BookCategory.OK);
        assertThat(nfOk).hasSize(1);
        assertThat(nfOk.get(0).getBook().getTitle()).isEqualTo("Book B");
    }

    @Test
    void cancelFromRanking_returnsToLibrary() {
        User user = createUserAndLogin("nav6");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/my-books");
        clickEditLink("Book A");
        clickButton("Re-rank");
        // Now on comparison page
        assertTextPresent("Which was better?");

        clickButton("cancel");

        assertOnPath("/my-books");
        assertTextNotPresent("Which was better?");
    }

    @Test
    void navigateToSearchDuringRanking_thenLibrary() {
        User user = createUserAndLogin("nav7");

        Book bookA = createVerifiedBook("OL1W", "Book A", "Author A");
        Book bookB = createVerifiedBook("OL2W", "Book B", "Author B");
        addRanking(user.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), bookB, Bookshelf.FICTION, BookCategory.LIKED, 1);

        navigateTo("/my-books");
        clickEditLink("Book A");
        clickButton("Re-rank");
        // Now on comparison page
        assertTextPresent("Which was better?");

        clickNavLink("Search");
        assertOnPath("/search");

        clickLibrary();

        assertOnPath("/my-books");
        assertTextNotPresent("Which was better?");
    }
}
