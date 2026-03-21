package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import armchair.service.OpenLibraryService;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "dev"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public abstract class BaseSeleniumTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    protected int port;

    @MockBean
    protected OpenLibraryService openLibraryService;

    @Autowired
    protected BookRepository bookRepository;

    @Autowired
    protected RankingRepository rankingRepository;

    @Autowired
    protected UserRepository userRepository;

    protected WebDriver driver;

    @BeforeAll
    static void setupDriver() {
        WebDriverManager.chromedriver().setup();
    }

    @BeforeEach
    void createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1280,900");
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
    }

    @AfterEach
    void quitDriver() {
        if (driver != null) {
            driver.quit();
        }
    }

    protected WebDriverWait webWait() {
        return new WebDriverWait(driver, WAIT_TIMEOUT);
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected void navigateTo(String path) {
        driver.get(baseUrl() + path);
    }

    /**
     * Log in using the dev mock OAuth user (created by DevDataInitializer).
     * Optionally renames the dev user to the given username.
     * After this call, the browser has an authenticated session and is on /my-profile.
     */
    protected User login(String username) {
        User user = userRepository.findByOauthSubjectAndOauthProvider("dev-user-subject", "google")
                .orElseThrow(() -> new IllegalStateException("DevDataInitializer should have created the dev user"));
        user.setUsername(username);
        user = userRepository.save(user);

        navigateTo("/oauth2/authorization/google");
        webWait().until(ExpectedConditions.urlContains("/my-profile"));

        return user;
    }

    /**
     * Alias for login() — creates an authenticated browser session.
     */
    protected User createUserAndLogin(String username) {
        return login(username);
    }

    protected Book createVerifiedBook(String workOlid, String title, String author) {
        return bookRepository.save(new Book(workOlid, null, title, author, null, null));
    }

    protected Book createUnverifiedBook(String title, String author) {
        return bookRepository.save(new Book(null, null, title, author, null, null));
    }

    protected Ranking addRanking(Long userId, Book book, Bookshelf bookshelf, BookCategory category, int position) {
        User userRef = userRepository.getReferenceById(userId);
        return rankingRepository.save(new Ranking(userRef, book, bookshelf, category, position));
    }

    protected Ranking addRankingWithReview(Long userId, Book book, Bookshelf bookshelf, BookCategory category,
                                           int position, String review) {
        User userRef = userRepository.getReferenceById(userId);
        Ranking ranking = new Ranking(userRef, book, bookshelf, category, position);
        ranking.setReview(review);
        return rankingRepository.save(ranking);
    }

    /**
     * Assert that the given text appears on the page. Uses explicit wait to handle
     * page transitions and stale element references.
     */
    protected void assertTextPresent(String text) {
        webWait().ignoring(StaleElementReferenceException.class)
                .until(d -> d.findElement(By.tagName("body")).getText().contains(text));
    }

    protected void assertTextNotPresent(String text) {
        // Short wait to let any in-flight navigation settle
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        String bodyText = driver.findElement(By.tagName("body")).getText();
        assertThat(bodyText).doesNotContain(text);
    }

    protected void clickNavLink(String linkText) {
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//div[contains(@class,'navbar')]//a[normalize-space(text())='" + linkText + "']"
        ))).click();
    }

    /**
     * Click the Library link in the navbar. Library is a POST form, not a regular link.
     */
    protected void clickLibrary() {
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//div[contains(@class,'navbar')]//a[normalize-space(text())='Library']"
        ))).click();
    }

    protected WebElement findByText(String text) {
        return driver.findElement(By.xpath("//*[contains(text(),'" + text + "')]"));
    }

    protected void selectRadio(String name, String value) {
        webWait().until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("input[name='" + name + "'][value='" + value + "']")
        )).click();
    }

    protected void clickButton(String text) {
        webWait().until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[normalize-space(text())='" + text + "']")
        )).click();
    }

    protected void clickActionLink(String text) {
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//*[contains(@class,'book-action-link') and normalize-space(text())='" + text + "']"
        ))).click();
    }

    /**
     * Click a book action button (re-rank, remove, write review, rank, etc.) for a specific book.
     */
    protected void clickBookAction(String bookTitle, String actionText) {
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//li[.//div[contains(@class,'book-title') and contains(.,'" + bookTitle + "')]]" +
                "//button[normalize-space(text())='" + actionText + "']"
        ))).click();
    }

    /**
     * In the pairwise comparison screen, click "new" (prefer the new book) or "existing".
     */
    protected void chooseInComparison(String choice) {
        webWait().until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//form[.//input[@name='choice' and @value='" + choice + "']]//button"
        ))).click();
    }

    /**
     * Keep choosing "new" in pairwise comparisons until the ranking is complete.
     */
    protected void chooseNewUntilDone() {
        while (true) {
            try {
                // Brief wait to let any redirect settle
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
            String bodyText = driver.findElement(By.tagName("body")).getText();
            if (!bodyText.contains("Which was better?")) {
                break;
            }
            chooseInComparison("new");
        }
    }

    protected List<String> getDisplayedBookTitles() {
        return driver.findElements(By.className("book-title")).stream()
                .map(WebElement::getText)
                .toList();
    }

    /**
     * Assert the current URL path matches the expected path. Uses explicit wait.
     */
    protected void assertOnPath(String expectedPath) {
        webWait().until(d -> {
            String path = d.getCurrentUrl().replace(baseUrl(), "");
            return path.startsWith(expectedPath);
        });
    }
}
