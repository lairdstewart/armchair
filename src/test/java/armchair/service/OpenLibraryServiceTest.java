package armchair.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenLibraryServiceTest {

    private OpenLibraryService service;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        service = new OpenLibraryService(new RestTemplateBuilder());
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    @Nested
    class SearchBooks {

        @Test
        void parsesSearchResults() {
            String json = """
                {
                  "docs": [
                    {
                      "key": "/works/OL123W",
                      "title": "Dune",
                      "author_name": ["Frank Herbert"],
                      "cover_edition_key": "OL456M",
                      "first_publish_year": 1965,
                      "cover_i": 789,
                      "edition_count": 42,
                      "editions": { "docs": [{ "title": "Dune", "key": "/books/OL789M" }] }
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchBooks("dune", 3);

            assertThat(results).hasSize(1);
            OpenLibraryService.BookResult book = results.get(0);
            assertThat(book.workOlid()).isEqualTo("OL123W");
            assertThat(book.editionOlid()).isEqualTo("OL789M");
            assertThat(book.title()).isEqualTo("Dune");
            assertThat(book.author()).isEqualTo("Frank Herbert");
            assertThat(book.firstPublishYear()).isEqualTo(1965);
            assertThat(book.coverId()).isEqualTo(789);
            assertThat(book.editionCount()).isEqualTo(42);
        }

        @Test
        void parsesMultipleResults() {
            String json = """
                {
                  "docs": [
                    { "key": "/works/OL1W", "title": "Book One", "author_name": ["Author A"] },
                    { "key": "/works/OL2W", "title": "Book Two", "author_name": ["Author B"] }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchBooks("books", 5);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).title()).isEqualTo("Book One");
            assertThat(results.get(1).title()).isEqualTo("Book Two");
        }

        @Test
        void prefersEditionTitleOverWorkTitle() {
            String json = """
                {
                  "docs": [
                    {
                      "key": "/works/OL1W",
                      "title": "Original Language Title",
                      "author_name": ["Author"],
                      "editions": { "docs": [{ "title": "English Title", "key": "/books/OL1M" }] }
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchBooks("test", 3);

            assertThat(results.get(0).title()).isEqualTo("English Title");
        }

        @Test
        void handlesDocWithoutKey() {
            String json = """
                {
                  "docs": [
                    { "title": "No Key Book", "author_name": ["Author"] }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchBooks("test", 3);

            assertThat(results).isEmpty();
        }

        @Test
        void handlesEmptyDocs() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("{\"docs\": []}");

            assertThat(service.searchBooks("test", 3)).isEmpty();
        }

        @Test
        void handlesMissingDocsField() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("{}");

            assertThat(service.searchBooks("test", 3)).isEmpty();
        }

        @Test
        void handlesNullQuery() {
            assertThat(service.searchBooks(null, 3)).isEmpty();
        }

        @Test
        void handlesBlankQuery() {
            assertThat(service.searchBooks("  ", 3)).isEmpty();
        }

        @Test
        void handlesApiError() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection timeout"));

            assertThat(service.searchBooks("test", 3)).isEmpty();
        }

        @Test
        void rethrowsResourceAccessException() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

            assertThatThrownBy(() -> service.searchBooks("test", 3))
                .isInstanceOf(ResourceAccessException.class);
        }

        @Test
        void handlesMalformedJson() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("not json at all");

            assertThat(service.searchBooks("test", 3)).isEmpty();
        }

        @Test
        void handlesMissingOptionalFields() {
            String json = """
                {
                  "docs": [
                    { "key": "/works/OL1W" }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchBooks("test", 3);

            assertThat(results).hasSize(1);
            OpenLibraryService.BookResult book = results.get(0);
            assertThat(book.title()).isEqualTo("Unknown Title");
            assertThat(book.author()).isEqualTo("Unknown Author");
            assertThat(book.firstPublishYear()).isNull();
            assertThat(book.coverId()).isNull();
            assertThat(book.editionCount()).isNull();
            assertThat(book.editionOlid()).isNull();
        }

        @Test
        void defaultMaxResultsIsThree() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("{\"docs\": []}");

            service.searchBooks("test");
            // No exception = success; verifying the overload works
        }
    }

    @Nested
    class SearchByTitleAndAuthor {

        @Test
        void searchesWithTitleAndAuthor() {
            String json = """
                {
                  "docs": [
                    { "key": "/works/OL1W", "title": "Dune", "author_name": ["Frank Herbert"] }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchByTitleAndAuthor("Dune", "Frank Herbert", 3);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Dune");
        }

        @Test
        void searchesWithTitleOnly() {
            String json = """
                { "docs": [{ "key": "/works/OL1W", "title": "Dune" }] }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.BookResult> results = service.searchByTitleAndAuthor("Dune", null, 3);

            assertThat(results).hasSize(1);
        }

        @Test
        void returnsEmptyForNullTitle() {
            assertThat(service.searchByTitleAndAuthor(null, "Author", 3)).isEmpty();
        }

        @Test
        void returnsEmptyForBlankTitle() {
            assertThat(service.searchByTitleAndAuthor("", "Author", 3)).isEmpty();
        }
    }

    @Nested
    class FetchEnglishAuthorName {

        @Test
        void fetchesEnglishNameForNonAsciiAuthor() {
            // Search returns non-ASCII author name, triggering author entity fetch
            String searchJson = """
                {
                  "docs": [
                    {
                      "key": "/works/OL1W",
                      "title": "Test Book",
                      "author_name": ["\u6751\u4e0a\u6625\u6a39"],
                      "author_key": ["OL123A"]
                    }
                  ]
                }
                """;
            String authorJson = """
                {
                  "personal_name": "Murakami, Haruki",
                  "alternate_names": ["Haruki Murakami"]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn(searchJson)
                .thenReturn(authorJson);

            List<OpenLibraryService.BookResult> results = service.searchBooks("murakami", 3);

            assertThat(results).hasSize(1);
            // personal_name "Murakami, Haruki" gets converted to "Haruki Murakami"
            assertThat(results.get(0).author()).isEqualTo("Haruki Murakami");
        }

        @Test
        void fallsBackToAlternateNameIfPersonalNameNonAscii() {
            String searchJson = """
                {
                  "docs": [
                    {
                      "key": "/works/OL1W",
                      "title": "Test",
                      "author_name": ["\u00c9mile Zola"],
                      "author_key": ["OL1A"]
                    }
                  ]
                }
                """;
            String authorJson = """
                {
                  "personal_name": "\u00c9mile Zola",
                  "alternate_names": ["Emile Zola", "E. Zola"]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn(searchJson)
                .thenReturn(authorJson);

            List<OpenLibraryService.BookResult> results = service.searchBooks("zola", 3);

            assertThat(results.get(0).author()).isEqualTo("Emile Zola");
        }

        @Test
        void keepsNonAsciiNameIfAuthorFetchFails() {
            String searchJson = """
                {
                  "docs": [
                    {
                      "key": "/works/OL1W",
                      "title": "Test",
                      "author_name": ["\u6751\u4e0a\u6625\u6a39"],
                      "author_key": ["OL1A"]
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn(searchJson)
                .thenThrow(new RestClientException("Not found"));

            List<OpenLibraryService.BookResult> results = service.searchBooks("test", 3);

            assertThat(results.get(0).author()).isEqualTo("\u6751\u4e0a\u6625\u6a39");
        }
    }

    @Nested
    class GetEditionsForWork {

        @Test
        void parsesEditionResults() {
            String json = """
                {
                  "entries": [
                    {
                      "key": "/books/OL1M",
                      "title": "Dune",
                      "isbn_13": ["9780441172719"],
                      "covers": [12345],
                      "publishers": ["Ace Books"],
                      "publish_date": "1990",
                      "languages": [{ "key": "/languages/eng" }]
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 10, 0);

            assertThat(results).hasSize(1);
            OpenLibraryService.EditionResult edition = results.get(0);
            assertThat(edition.editionOlid()).isEqualTo("OL1M");
            assertThat(edition.title()).isEqualTo("Dune");
            assertThat(edition.isbn13()).isEqualTo("9780441172719");
            assertThat(edition.coverId()).isEqualTo(12345);
            assertThat(edition.publisher()).isEqualTo("Ace Books");
            assertThat(edition.publishDate()).isEqualTo("1990");
        }

        @Test
        void skipsEditionsWithoutIsbn() {
            String json = """
                {
                  "entries": [
                    { "key": "/books/OL1M", "title": "No ISBN Edition" },
                    { "key": "/books/OL2M", "title": "Has ISBN", "isbn_13": ["9780000000001"] }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 10, 0);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Has ISBN");
        }

        @Test
        void convertsIsbn10ToIsbn13() {
            String json = """
                {
                  "entries": [
                    { "key": "/books/OL1M", "title": "Old Edition", "isbn_10": ["0441172717"] }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 10, 0);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isbn13()).isEqualTo("9780441172719");
        }

        @Test
        void returnsEmptyForNullWorkOlid() {
            assertThat(service.getEditionsForWork(null, 10, 0)).isEmpty();
        }

        @Test
        void returnsEmptyForBlankWorkOlid() {
            assertThat(service.getEditionsForWork("  ", 10, 0)).isEmpty();
        }

        @Test
        void handlesApiError() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));

            assertThat(service.getEditionsForWork("OL1W", 10, 0)).isEmpty();
        }

        @Test
        void rethrowsResourceAccessException() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

            assertThatThrownBy(() -> service.getEditionsForWork("OL1W", 10, 0))
                .isInstanceOf(ResourceAccessException.class);
        }

        @Test
        void handlesEmptyEntries() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("{\"entries\": []}");

            assertThat(service.getEditionsForWork("OL1W", 10, 0)).isEmpty();
        }

        @Test
        void handlesMissingEntriesField() {
            when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("{}");

            assertThat(service.getEditionsForWork("OL1W", 10, 0)).isEmpty();
        }

        @Test
        void appliesOffsetAndLimit() {
            // Create 5 English editions with ISBNs (all same score)
            StringBuilder entries = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                if (i > 0) entries.append(",");
                entries.append(String.format("""
                    {
                      "key": "/books/OL%dM",
                      "title": "Edition %d",
                      "isbn_13": ["978000000000%d"],
                      "languages": [{ "key": "/languages/eng" }]
                    }
                    """, i, i, i));
            }
            String json = "{\"entries\": [" + entries + "]}";
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 2, 1);

            assertThat(results).hasSize(2);
        }
    }

    @Nested
    class EditionScoring {

        @Test
        void prefersEnglishEditionsWithCovers() {
            String json = """
                {
                  "entries": [
                    {
                      "key": "/books/OL1M",
                      "title": "Edition Fran\u00e7aise",
                      "isbn_13": ["9780000000001"],
                      "languages": [{ "key": "/languages/fre" }]
                    },
                    {
                      "key": "/books/OL2M",
                      "title": "English Edition",
                      "isbn_13": ["9780000000002"],
                      "covers": [999],
                      "languages": [{ "key": "/languages/eng" }]
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 10, 0);

            assertThat(results.get(0).title()).isEqualTo("English Edition");
        }

        @Test
        void preferredEditionAppearsFirst() {
            String json = """
                {
                  "entries": [
                    {
                      "key": "/books/OL1M",
                      "title": "English With Cover",
                      "isbn_13": ["9780000000001"],
                      "covers": [111],
                      "languages": [{ "key": "/languages/eng" }]
                    },
                    {
                      "key": "/books/OL2M",
                      "title": "Preferred Edition",
                      "isbn_13": ["9780000000002"]
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 10, 0, "OL2M");

            assertThat(results.get(0).title()).isEqualTo("Preferred Edition");
        }

        @Test
        void asciiTitleGetsScoreBoost() {
            String json = """
                {
                  "entries": [
                    {
                      "key": "/books/OL1M",
                      "title": "\u30c7\u30e5\u30fc\u30f3",
                      "isbn_13": ["9780000000001"]
                    },
                    {
                      "key": "/books/OL2M",
                      "title": "Dune",
                      "isbn_13": ["9780000000002"]
                    }
                  ]
                }
                """;
            when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(json);

            List<OpenLibraryService.EditionResult> results = service.getEditionsForWork("OL1W", 10, 0);

            assertThat(results.get(0).title()).isEqualTo("Dune");
        }
    }

    @Nested
    class ConvertIsbn10ToIsbn13 {

        @Test
        void convertsValidIsbn10() {
            assertThat(OpenLibraryService.convertIsbn10ToIsbn13("0441172717")).isEqualTo("9780441172719");
        }

        @Test
        void convertsAnotherValidIsbn10() {
            // "The Great Gatsby" ISBN-10: 0743273567 -> ISBN-13: 9780743273565
            assertThat(OpenLibraryService.convertIsbn10ToIsbn13("0743273567")).isEqualTo("9780743273565");
        }

        @Test
        void returnsNullForNull() {
            assertThat(OpenLibraryService.convertIsbn10ToIsbn13(null)).isNull();
        }

        @Test
        void returnsNullForWrongLength() {
            assertThat(OpenLibraryService.convertIsbn10ToIsbn13("12345")).isNull();
        }
    }

    @Nested
    class BookResultUrls {

        @Test
        void bookUrlUsesWorkOlid() {
            var result = new OpenLibraryService.BookResult("OL1W", null, "Test", "Author", null, null, null);
            assertThat(result.bookUrl()).isEqualTo("https://openlibrary.org/works/OL1W");
        }

        @Test
        void bookUrlFallsBackToSearch() {
            var result = new OpenLibraryService.BookResult(null, null, "My Book", "Author", null, null, null);
            assertThat(result.bookUrl()).contains("openlibrary.org/search?q=");
            assertThat(result.bookUrl()).contains("My+Book");
        }

        @Test
        void coverUrlUsesCoverId() {
            var result = new OpenLibraryService.BookResult("OL1W", null, "Test", "Author", null, 789, null);
            assertThat(result.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/789-M.jpg");
        }

        @Test
        void coverUrlReturnsNullWhenNoCoverId() {
            var result = new OpenLibraryService.BookResult("OL1W", null, "Test", "Author", null, null, null);
            assertThat(result.coverUrl()).isNull();
        }
    }

    @Nested
    class EditionResultUrls {

        @Test
        void coverUrlUsesCoverId() {
            var result = new OpenLibraryService.EditionResult("OL1M", "Test", "978", 123, null, null);
            assertThat(result.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/123-M.jpg");
        }

        @Test
        void coverUrlReturnsNullWhenNoCoverId() {
            var result = new OpenLibraryService.EditionResult("OL1M", "Test", "978", null, null, null);
            assertThat(result.coverUrl()).isNull();
        }
    }
}
