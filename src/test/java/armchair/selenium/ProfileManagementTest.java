package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

class ProfileManagementTest extends BaseSeleniumTest {

    @Test
    void newUserMustSetUpUsername() {
        // Delete the dev user so the OAuth identity has no associated User record
        User devUser = userRepository.findByOauthSubjectAndOauthProvider("dev-user-subject", "google")
                .orElseThrow();
        userRepository.delete(devUser);
        userRepository.flush();

        // Trigger mock OAuth login — redirects to /my-profile, which redirects to /setup-username
        navigateTo("/oauth2/authorization/google");
        assertOnPath("/setup-username");
        assertTextPresent("Choose Your Username");

        // Fill in and submit the username form
        driver.findElement(By.name("username")).sendKeys("freshuser");
        clickButton("Continue");

        // Should redirect to /my-profile after successful setup
        assertOnPath("/my-profile");
        assertTextPresent("freshuser");
    }

    @Test
    void changeUsername() {
        createUserAndLogin("oldname");

        // Navigate to change username page
        navigateTo("/change-username");
        assertTextPresent("Change Username");

        // Clear existing value and enter new username
        var usernameInput = driver.findElement(By.name("username"));
        usernameInput.clear();
        usernameInput.sendKeys("newname");
        clickButton("Save");

        // Should redirect to profile showing the new username
        assertOnPath("/my-profile");
        assertTextPresent("newname");
    }

    @Test
    void viewOwnProfile() {
        User user = createUserAndLogin("profileuser");

        // Add some books to verify counts
        Book fictionBook = createVerifiedBook("OL1W", "Fiction Book", "Author A");
        Book nonfictionBook = createVerifiedBook("OL2W", "Nonfiction Book", "Author B");
        addRanking(user.getId(), fictionBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
        addRanking(user.getId(), nonfictionBook, Bookshelf.NONFICTION, BookCategory.LIKED, 0);

        // Refresh the profile page to see updated counts
        navigateTo("/my-profile");

        assertTextPresent("profileuser");
        assertTextPresent("User since:");
        assertTextPresent("User number:");
        assertTextPresent("Fiction books:");
        assertTextPresent("Non-Fiction books:");
    }

    @Test
    void nonExistentUserRedirectsToSearch() {
        createUserAndLogin("searcher");

        navigateTo("/user/bogus-nonexistent-user");

        // Should redirect to search page with the username as query
        assertOnPath("/search");
        webWait().until(ExpectedConditions.urlContains("type=profiles"));
    }
}
