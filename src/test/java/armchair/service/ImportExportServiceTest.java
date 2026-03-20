package armchair.service;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportExportServiceTest {

    private ImportExportService service;
    private BookService bookService;
    private RankingRepository rankingRepository;
    private BookRepository bookRepository;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        service = new ImportExportService();
        bookService = mock(BookService.class);
        rankingRepository = mock(RankingRepository.class);
        bookRepository = mock(BookRepository.class);
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "bookService", bookService);
        ReflectionTestUtils.setField(service, "rankingRepository", rankingRepository);
        ReflectionTestUtils.setField(service, "bookRepository", bookRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        // rankingService is only needed for generateCsv, mock it too
        RankingService rankingService = mock(RankingService.class);
        ReflectionTestUtils.setField(service, "rankingService", rankingService);
    }

    @Nested
    class ParseCsvLine {

        private List<String> parse(String line) {
            return ReflectionTestUtils.invokeMethod(service, "parseCsvLine", line);
        }

        @Test
        void parsesSimpleFields() {
            List<String> fields = parse("one,two,three");
            assertThat(fields).containsExactly("one", "two", "three");
        }

        @Test
        void parsesQuotedFields() {
            List<String> fields = parse("\"hello\",\"world\"");
            assertThat(fields).containsExactly("hello", "world");
        }

        @Test
        void parsesQuotedFieldWithComma() {
            List<String> fields = parse("\"hello, world\",other");
            assertThat(fields).containsExactly("hello, world", "other");
        }

        @Test
        void parsesEscapedQuotes() {
            List<String> fields = parse("\"She said \"\"hello\"\"\",next");
            assertThat(fields).containsExactly("She said \"hello\"", "next");
        }

        @Test
        void parsesEmptyFields() {
            List<String> fields = parse(",,");
            assertThat(fields).containsExactly("", "", "");
        }

        @Test
        void parsesMixedQuotedAndUnquoted() {
            List<String> fields = parse("plain,\"quoted\",plain2");
            assertThat(fields).containsExactly("plain", "quoted", "plain2");
        }

        @Test
        void parsesSingleField() {
            List<String> fields = parse("only");
            assertThat(fields).containsExactly("only");
        }

        @Test
        void parsesEmptyQuotedField() {
            List<String> fields = parse("\"\",value");
            assertThat(fields).containsExactly("", "value");
        }

        @Test
        void parsesNewlineInsideQuotes() {
            // A single line won't have actual newlines from BufferedReader,
            // but the parser should handle any char inside quotes
            List<String> fields = parse("\"line1\nline2\",next");
            assertThat(fields).containsExactly("line1\nline2", "next");
        }
    }

    @Nested
    class EscapeCsv {

        private String escape(String value) {
            return ReflectionTestUtils.invokeMethod(service, "escapeCsv", value);
        }

        @Test
        void escapesDoubleQuotes() {
            assertThat(escape("She said \"hello\"")).isEqualTo("She said \"\"hello\"\"");
        }

        @Test
        void returnsEmptyForNull() {
            assertThat(escape(null)).isEqualTo("");
        }

        @Test
        void returnsEmptyForBlank() {
            assertThat(escape("   ")).isEqualTo("");
        }

        @Test
        void passesPlainTextThrough() {
            assertThat(escape("plain text")).isEqualTo("plain text");
        }
    }

    @Nested
    class TrimReview {

        private String trim(String review) {
            return ReflectionTestUtils.invokeMethod(service, "trimReview", review);
        }

        @Test
        void returnsNullForNull() {
            assertThat(trim(null)).isNull();
        }

        @Test
        void returnsNullForBlank() {
            assertThat(trim("   ")).isNull();
        }

        @Test
        void trimsWhitespace() {
            assertThat(trim("  hello  ")).isEqualTo("hello");
        }

        @Test
        void truncatesLongReview() {
            String longReview = "a".repeat(6000);
            String result = trim(longReview);
            assertThat(result).hasSize(ImportExportService.MAX_REVIEW_LENGTH);
        }

        @Test
        void preservesReviewUnderLimit() {
            String review = "a".repeat(100);
            assertThat(trim(review)).isEqualTo(review);
        }
    }

    @Nested
    class ImportGoodreads {

        private User user;

        @BeforeEach
        void setUp() {
            user = new User("testuser");
            user.setId(1L);
            when(userRepository.getReferenceById(1L)).thenReturn(user);
            when(rankingRepository.findByUserId(1L)).thenReturn(List.of());
            when(rankingRepository.findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(
                1L, Bookshelf.UNRANKED, BookCategory.UNRANKED)).thenReturn(List.of());
        }

        private ImportExportService.ImportResult importCsv(String csv) {
            return service.importGoodreads(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 1L);
        }

        @Test
        void importsBasicCsv() {
            Book book = new Book(null, null, "Dune", "Frank Herbert", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), eq("Dune"), eq("Frank Herbert"), any(), any()))
                .thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);

            var result = importCsv("Title,Author\nDune,Frank Herbert\n");

            assertThat(result.imported()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            assertThat(result.failed()).isEqualTo(0);
        }

        @Test
        void returnsZerosForEmptyFile() {
            var result = importCsv("");
            assertThat(result.imported()).isEqualTo(0);
        }

        @Test
        void returnsZerosWhenMissingRequiredHeaders() {
            var result = importCsv("Foo,Bar\nval1,val2\n");
            assertThat(result.imported()).isEqualTo(0);
        }

        @Test
        void stripsColonFromTitle() {
            Book book = new Book(null, null, "Dune", "Frank Herbert", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), eq("Dune"), eq("Frank Herbert"), any(), any()))
                .thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);

            importCsv("Title,Author\nDune: The Epic Saga,Frank Herbert\n");

            verify(bookService).findOrCreateBook(any(), any(), eq("Dune"), eq("Frank Herbert"), any(), any());
        }

        @Test
        void stripsSeriesIdentifier() {
            Book book = new Book(null, null, "Dune", "Frank Herbert", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), eq("Dune"), eq("Frank Herbert"), any(), any()))
                .thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);

            importCsv("Title,Author\nDune (#1),Frank Herbert\n");

            verify(bookService).findOrCreateBook(any(), any(), eq("Dune"), eq("Frank Herbert"), any(), any());
        }

        @Test
        void skipsDuplicatesByTitleAuthorKey() {
            // Simulate existing book
            Book existingBook = new Book(null, null, "Dune", "Frank Herbert", null, null);
            existingBook.setId(5L);
            Ranking existingRanking = new Ranking(user, existingBook, Bookshelf.FICTION, BookCategory.LIKED, 0);
            when(rankingRepository.findByUserId(1L)).thenReturn(List.of(existingRanking));

            var result = importCsv("Title,Author\nDune,Frank Herbert\n");

            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.imported()).isEqualTo(0);
        }

        @Test
        void skipsEmptyTitleOrAuthor() {
            var result = importCsv("Title,Author\n,Frank Herbert\nDune,\n");

            assertThat(result.imported()).isEqualTo(0);
            verify(bookService, never()).findOrCreateBook(any(), any(), any(), any(), any(), any());
        }

        @Test
        void importsReview() {
            Book book = new Book(null, null, "Dune", "Frank Herbert", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), any(), any(), any(), any())).thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);
            when(rankingRepository.save(any(Ranking.class))).thenAnswer(inv -> inv.getArgument(0));

            importCsv("Title,Author,My Review\nDune,Frank Herbert,Great book!\n");

            verify(rankingRepository).save(any(Ranking.class));
        }

        @Test
        void handlesQuotedFieldsInCsv() {
            Book book = new Book(null, null, "Hello, World", "Author", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), eq("Hello, World"), eq("Author"), any(), any()))
                .thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);

            importCsv("Title,Author\n\"Hello, World\",Author\n");

            verify(bookService).findOrCreateBook(any(), any(), eq("Hello, World"), eq("Author"), any(), any());
        }

        @Test
        void skipsBlankLines() {
            Book book = new Book(null, null, "Dune", "Frank Herbert", null, null);
            book.setId(10L);
            when(bookService.findOrCreateBook(any(), any(), any(), any(), any(), any())).thenReturn(book);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);

            var result = importCsv("Title,Author\n\n\nDune,Frank Herbert\n\n");

            assertThat(result.imported()).isEqualTo(1);
        }

        @Test
        void assignsIncrementingPositions() {
            Book book1 = new Book(null, null, "Book1", "Author1", null, null);
            book1.setId(10L);
            Book book2 = new Book(null, null, "Book2", "Author2", null, null);
            book2.setId(11L);
            when(bookService.findOrCreateBook(any(), any(), eq("Book1"), eq("Author1"), any(), any())).thenReturn(book1);
            when(bookService.findOrCreateBook(any(), any(), eq("Book2"), eq("Author2"), any(), any())).thenReturn(book2);
            when(rankingRepository.existsByUserIdAndBookId(1L, 10L)).thenReturn(false);
            when(rankingRepository.existsByUserIdAndBookId(1L, 11L)).thenReturn(false);

            List<Ranking> savedRankings = new ArrayList<>();
            when(rankingRepository.save(any(Ranking.class))).thenAnswer(inv -> {
                savedRankings.add(inv.getArgument(0));
                return inv.getArgument(0);
            });

            importCsv("Title,Author\nBook1,Author1\nBook2,Author2\n");

            assertThat(savedRankings).hasSize(2);
            assertThat(savedRankings.get(0).getPosition()).isEqualTo(0);
            assertThat(savedRankings.get(1).getPosition()).isEqualTo(1);
        }
    }
}
