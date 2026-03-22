package armchair.tool;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.CuratedList;
import armchair.entity.CuratedRanking;
import armchair.repository.CuratedListRepository;
import armchair.repository.CuratedRankingRepository;
import armchair.service.BookService;
import armchair.tool.CuratedListImporter.ImportException;
import armchair.tool.CuratedListImporter.JsonBook;
import armchair.tool.CuratedListImporter.ParsedJsonList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CuratedListImporterTest {

    @TempDir
    Path tempDir;

    private static final String BOOK_JSON = """
            {"title": "Dune", "author": "Frank Herbert", "rank": "1", "category": "fiction", "review": "Great book", "work_olid": "OL893415W", "cover_id": "12345", "first_publish_year": "1965"}""";

    private static final String BOOK_JSON_MINIMAL = """
            {"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W"}""";

    // --- JSON parsing: valid input ---

    @Test
    void parseValidJsonWithRankedFictionBooks() throws IOException {
        String json = """
                {
                  "username": "Test List",
                  "books": [
                    %s,
                    {"title": "1984", "author": "George Orwell", "rank": "2", "category": "fiction", "review": "", "work_olid": "OL1168083W", "cover_id": "9330515", "first_publish_year": "1949"}
                  ]
                }
                """.formatted(BOOK_JSON);
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.username()).isEqualTo("Test List");
        assertThat(result.books()).hasSize(2);

        JsonBook first = result.books().get(0);
        assertThat(first.title()).isEqualTo("Dune");
        assertThat(first.author()).isEqualTo("Frank Herbert");
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.bookshelf()).isEqualTo(Bookshelf.FICTION);
        assertThat(first.category()).isEqualTo(BookCategory.LIKED);
        assertThat(first.review()).isEqualTo("Great book");
        assertThat(first.workOlid()).isEqualTo("OL893415W");
        assertThat(first.coverId()).isEqualTo(12345);
        assertThat(first.firstPublishYear()).isEqualTo(1965);
    }

    @Test
    void parseUnrankedBookHasNullRankAndUnrankedCategory() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Dune", "author": "Frank Herbert", "rank": "", "category": "fiction", "review": "", "work_olid": "OL893415W"}
                  ]
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        JsonBook book = result.books().get(0);
        assertThat(book.rank()).isNull();
        assertThat(book.category()).isEqualTo(BookCategory.UNRANKED);
    }

    @Test
    void parseNonfictionCategory() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Sapiens", "author": "Yuval Harari", "rank": "1", "category": "non-fiction", "review": "", "work_olid": "OL17075811W"}
                  ]
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.books().get(0).bookshelf()).isEqualTo(Bookshelf.NONFICTION);
    }

    @Test
    void parseCategoryDefaultsToFiction() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    %s
                  ]
                }
                """.formatted(BOOK_JSON_MINIMAL);
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.books().get(0).bookshelf()).isEqualTo(Bookshelf.FICTION);
    }

    @Test
    void parseEmptyBooksArray() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": []
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.books()).isEmpty();
    }

    @Test
    void parseNullReviewIsPreserved() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": null, "work_olid": "OL893415W"}
                  ]
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.books().get(0).review()).isNull();
    }

    @Test
    void parseNullCoverIdAndFirstPublishYearAllowed() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W", "cover_id": null, "first_publish_year": null}
                  ]
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        JsonBook book = result.books().get(0);
        assertThat(book.coverId()).isNull();
        assertThat(book.firstPublishYear()).isNull();
    }

    @Test
    void parseMissingCoverIdAndFirstPublishYearAllowed() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W"}
                  ]
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        JsonBook book = result.books().get(0);
        assertThat(book.coverId()).isNull();
        assertThat(book.firstPublishYear()).isNull();
    }

    // --- JSON parsing: invalid input ---

    @Test
    void parseNonExistentFileThrows() {
        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile("/nonexistent/path.json"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Error reading");
    }

    @Test
    void parseMalformedJsonThrows() throws IOException {
        Path file = writeJson("{ not valid json }");

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Error reading");
    }

    @Test
    void parseMissingUsernameThrows() throws IOException {
        String json = """
                {
                  "books": [%s]
                }
                """.formatted(BOOK_JSON_MINIMAL);
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("No 'username' found");
    }

    @Test
    void parseBlankUsernameThrows() throws IOException {
        String json = """
                {
                  "username": "   ",
                  "books": []
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("No 'username' found");
    }

    @Test
    void parseMissingBooksArrayThrows() throws IOException {
        String json = """
                {
                  "username": "Test"
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("No 'books' array found");
    }

    @Test
    void parseMissingTitleFieldThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Missing 'title' field on book #1");
    }

    @Test
    void parseEmptyTitleThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Empty 'title' on book #1");
    }

    @Test
    void parseMissingAuthorFieldThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "rank": "1", "review": "", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Missing 'author' field on book #1 (Dune)");
    }

    @Test
    void parseEmptyAuthorThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "  ", "rank": "1", "review": "", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Empty 'author' on book #1 (Dune)");
    }

    @Test
    void parseMissingRankFieldThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "review": "", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Missing 'rank' field on book #1 (Dune)");
    }

    @Test
    void parseNonNumericRankThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "abc", "review": "", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Non-numeric 'rank' \"abc\"");
    }

    @Test
    void parseMissingReviewFieldThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "work_olid": "OL893415W"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Missing 'review' field on book #1 (Dune)");
    }

    @Test
    void parseMissingWorkOlidThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": ""}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Missing 'work_olid' field on book #1 (Dune)");
    }

    @Test
    void parseEmptyWorkOlidThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": ""}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Empty 'work_olid' on book #1 (Dune)");
    }

    @Test
    void parseInvalidCategoryThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W", "category": "mystery"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Invalid 'category' \"mystery\"");
    }

    @Test
    void parseNonNumericCoverIdThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W", "cover_id": "abc"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Non-numeric 'cover_id' \"abc\"");
    }

    @Test
    void parseNonNumericFirstPublishYearThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "work_olid": "OL893415W", "first_publish_year": "abc"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Non-numeric 'first_publish_year' \"abc\"");
    }

    @Test
    void parseErrorMessageIncludesBookIndex() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    %s,
                    {"title": "Bad Book", "author": "", "rank": "2", "review": "", "work_olid": "OL123W"}
                  ]
                }
                """.formatted(BOOK_JSON_MINIMAL);
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("book #2");
    }

    // --- parseJsonData (in-memory parsing) ---

    @Test
    void parseJsonDataValidInput() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", "Test");
        data.put("books", List.of(
                Map.of("title", "Dune", "author", "Frank Herbert", "rank", "1", "review", "Great", "category", "fiction", "work_olid", "OL893415W")
        ));

        ParsedJsonList result = CuratedListImporter.parseJsonData(data, "test");

        assertThat(result.username()).isEqualTo("Test");
        assertThat(result.books()).hasSize(1);
        assertThat(result.books().get(0).workOlid()).isEqualTo("OL893415W");
    }

    @Test
    void parseDescriptionFromJson() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "description": "Curated from <a href=\\"https://example.com\\">example.com</a>.",
                  "books": []
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.description()).isEqualTo("Curated from <a href=\"https://example.com\">example.com</a>.");
    }

    @Test
    void parseMissingDescriptionReturnsNull() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": []
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.description()).isNull();
    }

    // --- importParsedList (integration with repositories) ---

    @Test
    void importCreatesNewCuratedList() {
        CuratedListRepository curatedListRepo = mock(CuratedListRepository.class);
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        when(curatedListRepo.findByUsername("Test List")).thenReturn(Optional.empty());
        ArgumentCaptor<CuratedList> listCaptor = ArgumentCaptor.forClass(CuratedList.class);
        when(curatedListRepo.save(listCaptor.capture())).thenAnswer(inv -> {
            CuratedList cl = inv.getArgument(0);
            cl.setId(1L);
            return cl;
        });
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new Book("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345));

        ParsedJsonList parsed = new ParsedJsonList("Test List", null, List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL893415W", 12345, 1965)
        ));

        CuratedListImporter.importParsedList(parsed, curatedListRepo, bookService, curatedRankingRepo);

        CuratedList savedList = listCaptor.getValue();
        assertThat(savedList.getUsername()).isEqualTo("Test List");
    }

    @Test
    void importReimportClearsExistingRankings() {
        CuratedListRepository curatedListRepo = mock(CuratedListRepository.class);
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        CuratedList existingList = new CuratedList("Test List");
        existingList.setId(42L);
        when(curatedListRepo.findByUsername("Test List")).thenReturn(Optional.of(existingList));
        when(curatedListRepo.save(any(CuratedList.class))).thenReturn(existingList);
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new Book("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345));

        ParsedJsonList parsed = new ParsedJsonList("Test List", null, List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL893415W", 12345, 1965)
        ));

        CuratedListImporter.importParsedList(parsed, curatedListRepo, bookService, curatedRankingRepo);

        verify(curatedRankingRepo).deleteByCuratedListId(42L);
        verify(curatedListRepo).save(existingList);
    }

    @Test
    void importSavesRankingsWithCorrectAttributes() {
        CuratedListRepository curatedListRepo = mock(CuratedListRepository.class);
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        when(curatedListRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(curatedListRepo.save(any(CuratedList.class))).thenAnswer(inv -> {
            CuratedList cl = inv.getArgument(0);
            cl.setId(1L);
            return cl;
        });
        Book dune = new Book("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345);
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any())).thenReturn(dune);

        ParsedJsonList parsed = new ParsedJsonList("Test", null, List.of(
                new JsonBook("Dune", "Frank Herbert", "A review", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL893415W", 12345, 1965)
        ));

        CuratedListImporter.importParsedList(parsed, curatedListRepo, bookService, curatedRankingRepo);

        ArgumentCaptor<CuratedRanking> rankingCaptor = ArgumentCaptor.forClass(CuratedRanking.class);
        verify(curatedRankingRepo).save(rankingCaptor.capture());

        CuratedRanking saved = rankingCaptor.getValue();
        assertThat(saved.getCuratedList().getId()).isEqualTo(1L);
        assertThat(saved.getBookshelf()).isEqualTo(Bookshelf.FICTION);
        assertThat(saved.getCategory()).isEqualTo(BookCategory.LIKED);
        assertThat(saved.getPosition()).isEqualTo(0);
        assertThat(saved.getReview()).isEqualTo("A review");
    }

    @Test
    void importEmptyReviewDoesNotSetReview() {
        CuratedListRepository curatedListRepo = mock(CuratedListRepository.class);
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        when(curatedListRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(curatedListRepo.save(any(CuratedList.class))).thenAnswer(inv -> {
            CuratedList cl = inv.getArgument(0);
            cl.setId(1L);
            return cl;
        });
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new Book("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345));

        ParsedJsonList parsed = new ParsedJsonList("Test", null, List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL893415W", 12345, 1965)
        ));

        CuratedListImporter.importParsedList(parsed, curatedListRepo, bookService, curatedRankingRepo);

        ArgumentCaptor<CuratedRanking> rankingCaptor = ArgumentCaptor.forClass(CuratedRanking.class);
        verify(curatedRankingRepo).save(rankingCaptor.capture());
        assertThat(rankingCaptor.getValue().getReview()).isNull();
    }

    @Test
    void importSortsRankedBooksByRank() {
        CuratedListRepository curatedListRepo = mock(CuratedListRepository.class);
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        when(curatedListRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(curatedListRepo.save(any(CuratedList.class))).thenAnswer(inv -> {
            CuratedList cl = inv.getArgument(0);
            cl.setId(1L);
            return cl;
        });
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new Book(inv.getArgument(0), null, inv.getArgument(2), inv.getArgument(3), null, null));

        ParsedJsonList parsed = new ParsedJsonList("Test", null, List.of(
                new JsonBook("Second", "Author B", "", Bookshelf.FICTION, BookCategory.LIKED, 2, "OL2W", null, null),
                new JsonBook("First", "Author A", "", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL1W", null, null),
                new JsonBook("Third", "Author C", "", Bookshelf.FICTION, BookCategory.LIKED, 3, "OL3W", null, null)
        ));

        CuratedListImporter.importParsedList(parsed, curatedListRepo, bookService, curatedRankingRepo);

        ArgumentCaptor<CuratedRanking> rankingCaptor = ArgumentCaptor.forClass(CuratedRanking.class);
        verify(curatedRankingRepo, org.mockito.Mockito.times(3)).save(rankingCaptor.capture());

        List<CuratedRanking> saved = rankingCaptor.getAllValues();
        assertThat(saved.get(0).getPosition()).isEqualTo(0);
        assertThat(saved.get(0).getBook().getTitle()).isEqualTo("First");
        assertThat(saved.get(1).getPosition()).isEqualTo(1);
        assertThat(saved.get(1).getBook().getTitle()).isEqualTo("Second");
        assertThat(saved.get(2).getPosition()).isEqualTo(2);
        assertThat(saved.get(2).getBook().getTitle()).isEqualTo("Third");
    }

    @Test
    void importGroupsBooksCorrectlyByShelfAndCategory() {
        CuratedListRepository curatedListRepo = mock(CuratedListRepository.class);
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        when(curatedListRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(curatedListRepo.save(any(CuratedList.class))).thenAnswer(inv -> {
            CuratedList cl = inv.getArgument(0);
            cl.setId(1L);
            return cl;
        });
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new Book(inv.getArgument(0), null, inv.getArgument(2), inv.getArgument(3), null, null));

        ParsedJsonList parsed = new ParsedJsonList("Test", null, List.of(
                new JsonBook("Fiction Ranked", "A", "", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL1W", null, null),
                new JsonBook("Nonfiction Unranked", "B", "", Bookshelf.NONFICTION, BookCategory.UNRANKED, null, "OL2W", null, null),
                new JsonBook("Fiction Unranked", "C", "", Bookshelf.FICTION, BookCategory.UNRANKED, null, "OL3W", null, null),
                new JsonBook("Nonfiction Ranked", "D", "", Bookshelf.NONFICTION, BookCategory.LIKED, 1, "OL4W", null, null)
        ));

        CuratedListImporter.importParsedList(parsed, curatedListRepo, bookService, curatedRankingRepo);

        ArgumentCaptor<CuratedRanking> rankingCaptor = ArgumentCaptor.forClass(CuratedRanking.class);
        verify(curatedRankingRepo, org.mockito.Mockito.times(4)).save(rankingCaptor.capture());

        List<CuratedRanking> saved = rankingCaptor.getAllValues();
        // Order: fiction ranked, fiction unranked, nonfiction ranked, nonfiction unranked
        assertThat(saved.get(0).getBook().getTitle()).isEqualTo("Fiction Ranked");
        assertThat(saved.get(1).getBook().getTitle()).isEqualTo("Fiction Unranked");
        assertThat(saved.get(2).getBook().getTitle()).isEqualTo("Nonfiction Ranked");
        assertThat(saved.get(3).getBook().getTitle()).isEqualTo("Nonfiction Unranked");
    }

    // --- importJsonBooks: passes JSON fields directly to BookService ---

    @Test
    void importPassesJsonFieldsDirectlyToBookService() {
        BookService bookService = mock(BookService.class);
        CuratedRankingRepository curatedRankingRepo = mock(CuratedRankingRepository.class);

        Book book = new Book("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345);
        when(bookService.findOrCreateBook("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345))
                .thenReturn(book);

        List<JsonBook> books = List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1, "OL893415W", 12345, 1965)
        );

        CuratedList curatedList = new CuratedList("Test");
        curatedList.setId(1L);
        CuratedListImporter.importJsonBooks(curatedList, books, bookService, curatedRankingRepo);

        verify(bookService).findOrCreateBook("OL893415W", null, "Dune", "Frank Herbert", 1965, 12345);
    }

    // --- Helper ---

    private Path writeJson(String content) throws IOException {
        Path file = tempDir.resolve("test.json");
        Files.writeString(file, content);
        return file;
    }
}
