package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.User;
import armchair.repository.FollowRepository;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SocialFeaturesTest extends BaseSeleniumTest {

    @Autowired
    private FollowRepository followRepository;

    /**
     * Create a second user directly in the database (not the dev mock user).
     */
    private User createSecondUser(String username) {
        User user = new User(username, "other-oauth-subject", "google");
        return userRepository.save(user);
    }

    /**
     * Click a button that appears in the same container div as the given username link.
     */
    private void clickUserAction(String username, String buttonText) {
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//div[./a[normalize-space(text())='" + username + "']]"
                + "//button[normalize-space(text())='" + buttonText + "']"
        ))).click();
    }

    @Test
    void followAndUnfollowUser() {
        User me = createUserAndLogin("tester");
        User other = createSecondUser("bookworm");

        // Navigate to profiles search to find the other user
        navigateTo("/search?type=profiles");
        assertTextPresent("bookworm");

        // Click follow for bookworm specifically (other users like dev-github may also appear)
        clickUserAction("bookworm", "follow");

        // Wait for page to reload and show unfollow button for bookworm
        webWait().until(ExpectedConditions.presenceOfElementLocated(By.xpath(
                "//div[./a[normalize-space(text())='bookworm']]//button[normalize-space(text())='unfollow']"
        )));

        // Verify in DB
        assertThat(followRepository.existsByFollowerIdAndFollowedId(me.getId(), other.getId())).isTrue();

        // Check the following tab shows the user
        navigateTo("/search?type=following");
        assertTextPresent("bookworm");

        // Unfollow — the unfollow button has a confirm dialog, so handle the alert
        clickUserAction("bookworm", "unfollow");
        webWait().until(ExpectedConditions.alertIsPresent());
        driver.switchTo().alert().accept();

        // Verify unfollowed
        webWait().until(d -> d.findElement(By.tagName("body")).getText()
                .contains("You're not following anyone yet."));
        assertThat(followRepository.existsByFollowerIdAndFollowedId(me.getId(), other.getId())).isFalse();
    }

    @Test
    void viewOtherUserProfile() {
        User me = createUserAndLogin("viewer");
        User other = createSecondUser("ranker");

        // Set up books on the other user's profile
        Book bookA = createVerifiedBook("OL10W", "The Great Gatsby", "F. Scott Fitzgerald");
        Book bookB = createVerifiedBook("OL11W", "1984", "George Orwell");
        addRanking(other.getId(), bookA, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(other.getId(), bookB, Bookshelf.FICTION, BookCategory.OK, 1);

        // Navigate to the other user's profile
        navigateTo("/user/ranker");

        assertTextPresent("ranker");
        assertTextPresent("The Great Gatsby");
        assertTextPresent("1984");
    }

    @Test
    void publicProfileShowsNoBooksActionButtons() {
        User me = createUserAndLogin("reader");
        User other = createSecondUser("curator");

        Book book = createVerifiedBook("OL20W", "Dune", "Frank Herbert");
        addRanking(other.getId(), book, Bookshelf.FICTION, BookCategory.LIKED, 0);

        // View the other user's profile
        navigateTo("/user/curator");
        assertTextPresent("Dune");

        // Verify no action buttons are shown on public profiles
        assertThat(driver.findElements(By.xpath("//button[normalize-space(text())='want to read']"))).isEmpty();
        assertThat(driver.findElements(By.xpath("//a[normalize-space(text())='view in library']"))).isEmpty();
    }
}
