package armchair.service;

import armchair.entity.Book;
import armchair.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private SearchService service;
    private OpenLibraryService openLibraryService;
    private BookRepository bookRepository;

    @BeforeEach
    void setUp() {
        service = new SearchService();
        openLibraryService = mock(OpenLibraryService.class);
        bookRepository = mock(BookRepository.class);
        ReflectionTestUtils.setField(service, "openLibraryService", openLibraryService);
        ReflectionTestUtils.setField(service, "bookRepository", bookRepository);
    }

    private OpenLibraryService.BookResult bookResult(String workOlid, String title, Integer editionCount) {
        return new OpenLibraryService.BookResult(workOlid, null, title, "Author", null, null, editionCount);
    }

    @Nested
    class DeduplicateResults {

        @Test
        void removesDuplicatesByWorkOlid() {
            var results = List.of(
                bookResult("OL1W", "Dune", 10),
                bookResult("OL1W", "Dune (duplicate)", 5),
                bookResult("OL2W", "1984", 20)
            );

            var deduped = SearchService.deduplicateResults(results);

            assertThat(deduped).hasSize(2);
            assertThat(deduped.get(0).title()).isEqualTo("Dune");
            assertThat(deduped.get(1).title()).isEqualTo("1984");
        }

        @Test
        void preservesOrderForUniqueResults() {
            var results = List.of(
                bookResult("OL1W", "First", 1),
                bookResult("OL2W", "Second", 2),
                bookResult("OL3W", "Third", 3)
            );

            var deduped = SearchService.deduplicateResults(results);

            assertThat(deduped).hasSize(3);
            assertThat(deduped.get(0).title()).isEqualTo("First");
        }

        @Test
        void handlesEmptyList() {
            assertThat(SearchService.deduplicateResults(List.of())).isEmpty();
        }
    }

    @Nested
    class CombinedSearch {

        @Test
        void combinesStructuredAndGeneralResults() {
            when(openLibraryService.searchByTitleAndAuthor("Dune", "Herbert", 5))
                .thenReturn(List.of(bookResult("OL1W", "Dune", 100)));
            when(openLibraryService.searchBooks("Dune Herbert", 5))
                .thenReturn(List.of(bookResult("OL2W", "Dune Messiah", 50)));

            var results = service.combinedSearch("Dune", "Herbert", 5);

            assertThat(results).hasSize(2);
        }

        @Test
        void deduplicatesAcrossSources() {
            when(openLibraryService.searchByTitleAndAuthor("Dune", "Herbert", 5))
                .thenReturn(List.of(bookResult("OL1W", "Dune", 100)));
            when(openLibraryService.searchBooks("Dune Herbert", 5))
                .thenReturn(List.of(bookResult("OL1W", "Dune (same)", 50)));

            var results = service.combinedSearch("Dune", "Herbert", 5);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Dune");
        }

        @Test
        void sortsByEditionCountDescending() {
            when(openLibraryService.searchByTitleAndAuthor(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(bookResult("OL1W", "Less Popular", 10)));
            when(openLibraryService.searchBooks(anyString(), anyInt()))
                .thenReturn(List.of(bookResult("OL2W", "Most Popular", 100)));

            var results = service.combinedSearch("test", "author", 5);

            assertThat(results.get(0).title()).isEqualTo("Most Popular");
            assertThat(results.get(1).title()).isEqualTo("Less Popular");
        }

        @Test
        void limitsToMaxResults() {
            when(openLibraryService.searchByTitleAndAuthor(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                    bookResult("OL1W", "A", 10),
                    bookResult("OL2W", "B", 20)
                ));
            when(openLibraryService.searchBooks(anyString(), anyInt()))
                .thenReturn(List.of(
                    bookResult("OL3W", "C", 30),
                    bookResult("OL4W", "D", 40)
                ));

            var results = service.combinedSearch("test", "author", 2);

            assertThat(results).hasSize(2);
        }

        @Test
        void handlesNullEditionCount() {
            when(openLibraryService.searchByTitleAndAuthor(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(bookResult("OL1W", "No Count", null)));
            when(openLibraryService.searchBooks(anyString(), anyInt()))
                .thenReturn(List.of(bookResult("OL2W", "Has Count", 5)));

            var results = service.combinedSearch("test", "author", 5);

            assertThat(results.get(0).title()).isEqualTo("Has Count");
            assertThat(results.get(1).title()).isEqualTo("No Count");
        }
    }

    @Nested
    class GetRandomBooksExcluding {

        @Test
        void excludesBooksAlreadyInUserLibrary() {
            Book b1 = new Book("OL1W", "OL1M", "Dune", "Herbert", 1965, 789);
            Book b2 = new Book("OL2W", "OL2M", "1984", "Orwell", 1949, 456);
            when(bookRepository.findRandomBooks()).thenReturn(List.of(b1, b2));

            var results = service.getRandomBooksExcluding(Map.of("OL1W", true));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("1984");
        }

        @Test
        void returnsAllWhenNoExclusions() {
            Book b1 = new Book("OL1W", null, "Book1", "Author1", null, null);
            when(bookRepository.findRandomBooks()).thenReturn(List.of(b1));

            var results = service.getRandomBooksExcluding(Map.of());

            assertThat(results).hasSize(1);
        }
    }
}
