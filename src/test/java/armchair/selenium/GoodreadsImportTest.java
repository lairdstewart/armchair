package armchair.selenium;

import armchair.BaseSeleniumTest;
import armchair.entity.User;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class GoodreadsImportTest extends BaseSeleniumTest {

    private void navigateToImport() {
        navigateTo("/import-goodreads");
    }

    private void uploadCsv(String resourceName) {
        WebElement fileInput = driver.findElement(By.id("file-input"));
        Path csvPath = Paths.get("src/test/resources", resourceName).toAbsolutePath();
        fileInput.sendKeys(csvPath.toString());
        clickButton("Import Books");
    }

    @Test
    void importValidCsv() {
        User user = login("importer1");
        navigateToImport();

        uploadCsv("sample_export.csv");

        // Should redirect to my-books with UNRANKED tab
        webWait().until(ExpectedConditions.urlContains("/my-books"));
        assertTextPresent("Uncategorized");

        // Verify imported books appear (titles are cleaned: subtitle after colon stripped, series identifiers removed)
        assertTextPresent("memoirs of a geisha");
        assertTextPresent("Blink");
        assertTextPresent("Power of One");
        assertTextPresent("Dune");
    }

    @Test
    void importEmptyCsv() {
        User user = login("importer2");
        navigateToImport();

        // Create a temporary empty CSV with just a header
        uploadCsv("empty_export.csv");

        // Should redirect to my-books — no books to show in unranked
        webWait().until(ExpectedConditions.urlContains("/my-books"));

        // The Uncategorized tab should not appear since there are no unranked books
        assertTextNotPresent("Uncategorized");
    }

    @Test
    void importDuplicateBooksSkipped() {
        User user = login("importer3");

        // First import
        navigateToImport();
        uploadCsv("sample_export.csv");
        webWait().until(ExpectedConditions.urlContains("/my-books"));

        // Count books after first import
        int firstImportCount = driver.findElements(By.className("book-title")).size();
        assertThat(firstImportCount).isEqualTo(4);

        // Second import of same file
        navigateToImport();
        uploadCsv("sample_export.csv");
        webWait().until(ExpectedConditions.urlContains("/my-books"));

        // Count should be the same — duplicates were skipped
        int secondImportCount = driver.findElements(By.className("book-title")).size();
        assertThat(secondImportCount).isEqualTo(firstImportCount);
    }
}
