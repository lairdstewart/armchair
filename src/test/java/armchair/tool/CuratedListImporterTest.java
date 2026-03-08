package armchair.tool;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import armchair.service.BookService;
import armchair.service.OpenLibraryService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CuratedListImporterTest {

    @TempDir
    Path tempDir;

    // --- JSON parsing: valid input ---

    @Test
    void parseValidJsonWithRankedFictionBooks() throws IOException {
        String json = """
                {
                  "username": "Test List",
                  "books": [
                    {"title": "Dune", "author": "Frank Herbert", "rank": "1", "category": "fiction", "review": "Great book"},
                    {"title": "1984", "author": "George Orwell", "rank": "2", "category": "fiction", "review": ""}
                  ]
                }
                """;
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
    }

    @Test
    void parseUnrankedBookHasNullRankAndUnrankedCategory() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Dune", "author": "Frank Herbert", "rank": "", "category": "fiction", "review": ""}
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
                    {"title": "Sapiens", "author": "Yuval Harari", "rank": "1", "category": "non-fiction", "review": ""}
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
                    {"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": ""}
                  ]
                }
                """;
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
                    {"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": null}
                  ]
                }
                """;
        Path file = writeJson(json);

        ParsedJsonList result = CuratedListImporter.parseJsonFile(file.toString());

        assertThat(result.books().get(0).review()).isNull();
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
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": ""}]
                }
                """;
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
                  "books": [{"author": "Frank Herbert", "rank": "1", "review": ""}]
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
                  "books": [{"title": "", "author": "Frank Herbert", "rank": "1", "review": ""}]
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
                  "books": [{"title": "Dune", "rank": "1", "review": ""}]
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
                  "books": [{"title": "Dune", "author": "  ", "rank": "1", "review": ""}]
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
                  "books": [{"title": "Dune", "author": "Frank Herbert", "review": ""}]
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
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "abc", "review": ""}]
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
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Missing 'review' field on book #1 (Dune)");
    }

    @Test
    void parseInvalidCategoryThrows() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [{"title": "Dune", "author": "Frank Herbert", "rank": "1", "review": "", "category": "mystery"}]
                }
                """;
        Path file = writeJson(json);

        assertThatThrownBy(() -> CuratedListImporter.parseJsonFile(file.toString()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Invalid 'category' \"mystery\"");
    }

    @Test
    void parseErrorMessageIncludesBookIndex() throws IOException {
        String json = """
                {
                  "username": "Test",
                  "books": [
                    {"title": "Good Book", "author": "Author", "rank": "1", "review": ""},
                    {"title": "Bad Book", "author": "", "rank": "2", "review": ""}
                  ]
                }
                """;
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
                Map.of("title", "Dune", "author", "Frank Herbert", "rank", "1", "review", "Great", "category", "fiction")
        ));

        ParsedJsonList result = CuratedListImporter.parseJsonData(data, "test");

        assertThat(result.username()).isEqualTo("Test");
        assertThat(result.books()).hasSize(1);
    }

    // --- importParsedList (integration with repositories) ---

    @Test
    void importCreatesNewCuratedUser() {
        UserRepository userRepo = mock(UserRepository.class);
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        when(userRepo.findByUsername("Test List")).thenReturn(Optional.empty());
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepo.save(userCaptor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new Book(null, null, "Dune", "Frank Herbert", null, null));

        ParsedJsonList parsed = new ParsedJsonList("Test List", List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1)
        ));

        CuratedListImporter.importParsedList(parsed, userRepo, bookService, rankingRepo, openLibraryService);

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo("Test List");
        assertThat(savedUser.isCurated()).isTrue();
    }

    @Test
    void importReimportClearsExistingRankings() {
        UserRepository userRepo = mock(UserRepository.class);
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        User existingUser = new User("Test List");
        existingUser.setId(42L);
        when(userRepo.findByUsername("Test List")).thenReturn(Optional.of(existingUser));
        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new Book(null, null, "Dune", "Frank Herbert", null, null));

        ParsedJsonList parsed = new ParsedJsonList("Test List", List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1)
        ));

        CuratedListImporter.importParsedList(parsed, userRepo, bookService, rankingRepo, openLibraryService);

        verify(rankingRepo).deleteByUserId(42L);
        verify(userRepo, never()).save(any());
    }

    @Test
    void importSavesRankingsWithCorrectAttributes() {
        UserRepository userRepo = mock(UserRepository.class);
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        when(userRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        Book dune = new Book(null, null, "Dune", "Frank Herbert", null, null);
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any())).thenReturn(dune);

        ParsedJsonList parsed = new ParsedJsonList("Test", List.of(
                new JsonBook("Dune", "Frank Herbert", "A review", Bookshelf.FICTION, BookCategory.LIKED, 1)
        ));

        CuratedListImporter.importParsedList(parsed, userRepo, bookService, rankingRepo, openLibraryService);

        ArgumentCaptor<Ranking> rankingCaptor = ArgumentCaptor.forClass(Ranking.class);
        verify(rankingRepo).save(rankingCaptor.capture());

        Ranking saved = rankingCaptor.getValue();
        assertThat(saved.getUser().getId()).isEqualTo(1L);
        assertThat(saved.getBookshelf()).isEqualTo(Bookshelf.FICTION);
        assertThat(saved.getCategory()).isEqualTo(BookCategory.LIKED);
        assertThat(saved.getPosition()).isEqualTo(0);
        assertThat(saved.getReview()).isEqualTo("A review");
    }

    @Test
    void importEmptyReviewDoesNotSetReview() {
        UserRepository userRepo = mock(UserRepository.class);
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        when(userRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new Book(null, null, "Dune", "Frank Herbert", null, null));

        ParsedJsonList parsed = new ParsedJsonList("Test", List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1)
        ));

        CuratedListImporter.importParsedList(parsed, userRepo, bookService, rankingRepo, openLibraryService);

        ArgumentCaptor<Ranking> rankingCaptor = ArgumentCaptor.forClass(Ranking.class);
        verify(rankingRepo).save(rankingCaptor.capture());
        assertThat(rankingCaptor.getValue().getReview()).isNull();
    }

    @Test
    void importSortsRankedBooksByRank() {
        UserRepository userRepo = mock(UserRepository.class);
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        when(userRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new Book(null, null, inv.getArgument(2), inv.getArgument(3), null, null));

        // Provide books out of rank order
        ParsedJsonList parsed = new ParsedJsonList("Test", List.of(
                new JsonBook("Second", "Author B", "", Bookshelf.FICTION, BookCategory.LIKED, 2),
                new JsonBook("First", "Author A", "", Bookshelf.FICTION, BookCategory.LIKED, 1),
                new JsonBook("Third", "Author C", "", Bookshelf.FICTION, BookCategory.LIKED, 3)
        ));

        CuratedListImporter.importParsedList(parsed, userRepo, bookService, rankingRepo, openLibraryService);

        ArgumentCaptor<Ranking> rankingCaptor = ArgumentCaptor.forClass(Ranking.class);
        verify(rankingRepo, org.mockito.Mockito.times(3)).save(rankingCaptor.capture());

        List<Ranking> saved = rankingCaptor.getAllValues();
        assertThat(saved.get(0).getPosition()).isEqualTo(0);
        assertThat(saved.get(0).getBook().getTitle()).isEqualTo("First");
        assertThat(saved.get(1).getPosition()).isEqualTo(1);
        assertThat(saved.get(1).getBook().getTitle()).isEqualTo("Second");
        assertThat(saved.get(2).getPosition()).isEqualTo(2);
        assertThat(saved.get(2).getBook().getTitle()).isEqualTo("Third");
    }

    @Test
    void importGroupsBooksCorrectlyByShelfAndCategory() {
        UserRepository userRepo = mock(UserRepository.class);
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        when(userRepo.findByUsername("Test")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        when(bookService.findOrCreateBook(any(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new Book(null, null, inv.getArgument(2), inv.getArgument(3), null, null));

        // Mix of fiction/nonfiction, ranked/unranked
        ParsedJsonList parsed = new ParsedJsonList("Test", List.of(
                new JsonBook("Fiction Ranked", "A", "", Bookshelf.FICTION, BookCategory.LIKED, 1),
                new JsonBook("Nonfiction Unranked", "B", "", Bookshelf.NONFICTION, BookCategory.UNRANKED, null),
                new JsonBook("Fiction Unranked", "C", "", Bookshelf.FICTION, BookCategory.UNRANKED, null),
                new JsonBook("Nonfiction Ranked", "D", "", Bookshelf.NONFICTION, BookCategory.LIKED, 1)
        ));

        CuratedListImporter.importParsedList(parsed, userRepo, bookService, rankingRepo, openLibraryService);

        ArgumentCaptor<Ranking> rankingCaptor = ArgumentCaptor.forClass(Ranking.class);
        verify(rankingRepo, org.mockito.Mockito.times(4)).save(rankingCaptor.capture());

        List<Ranking> saved = rankingCaptor.getAllValues();
        // Order: fiction ranked, fiction unranked, nonfiction ranked, nonfiction unranked
        assertThat(saved.get(0).getBook().getTitle()).isEqualTo("Fiction Ranked");
        assertThat(saved.get(1).getBook().getTitle()).isEqualTo("Fiction Unranked");
        assertThat(saved.get(2).getBook().getTitle()).isEqualTo("Nonfiction Ranked");
        assertThat(saved.get(3).getBook().getTitle()).isEqualTo("Nonfiction Unranked");
    }

    // --- importJsonBooks: OpenLibrary integration ---

    @Test
    void importUsesOpenLibraryResultWhenAvailable() {
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        OpenLibraryService.BookResult olResult = new OpenLibraryService.BookResult(
                "OL123W", "OL456M", "Dune (OL)", "Frank Herbert (OL)", 1965, 12345, 10);
        when(openLibraryService.searchBooks("Dune, Frank Herbert")).thenReturn(List.of(olResult));
        Book book = new Book("OL123W", "OL456M", "Dune (OL)", "Frank Herbert (OL)", 1965, 12345);
        when(bookService.findOrCreateBook("OL123W", "OL456M", "Dune (OL)", "Frank Herbert (OL)", 1965, 12345))
                .thenReturn(book);

        List<JsonBook> books = List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1)
        );

        User user = new User();
        user.setId(1L);
        CuratedListImporter.importJsonBooks(user, books, bookService, rankingRepo, openLibraryService);

        verify(bookService).findOrCreateBook("OL123W", "OL456M", "Dune (OL)", "Frank Herbert (OL)", 1965, 12345);
    }

    @Test
    void importFallsBackToJsonDataWhenNoOpenLibraryResult() {
        BookService bookService = mock(BookService.class);
        RankingRepository rankingRepo = mock(RankingRepository.class);
        OpenLibraryService openLibraryService = mock(OpenLibraryService.class);

        when(openLibraryService.searchBooks(anyString())).thenReturn(List.of());
        Book book = new Book(null, null, "Dune", "Frank Herbert", null, null);
        when(bookService.findOrCreateBook(null, null, "Dune", "Frank Herbert", null, null)).thenReturn(book);

        List<JsonBook> books = List.of(
                new JsonBook("Dune", "Frank Herbert", "", Bookshelf.FICTION, BookCategory.LIKED, 1)
        );

        User user = new User();
        user.setId(1L);
        CuratedListImporter.importJsonBooks(user, books, bookService, rankingRepo, openLibraryService);

        verify(bookService).findOrCreateBook(null, null, "Dune", "Frank Herbert", null, null);
    }

    // --- Helper ---

    private Path writeJson(String content) throws IOException {
        Path file = tempDir.resolve("test.json");
        Files.writeString(file, content);
        return file;
    }
}
