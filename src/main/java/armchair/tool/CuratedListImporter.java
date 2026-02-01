package armchair.tool;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.BookIsbn;
import armchair.entity.BookType;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookIsbnRepository;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import armchair.service.GoogleBooksService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class CuratedListImporter {

    @Configuration
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class})
    @ComponentScan(basePackages = "armchair.service")
    @EntityScan("armchair.entity")
    @EnableJpaRepositories("armchair.repository")
    static class Config {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: CuratedListImporter <absolute-path-to-file.json>");
            System.exit(1);
        }

        String filePath = args[0];

        SpringApplication app = new SpringApplication(Config.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = app.run(args)) {
            UserRepository userRepository = context.getBean(UserRepository.class);
            BookRepository bookRepository = context.getBean(BookRepository.class);
            BookIsbnRepository bookIsbnRepository = context.getBean(BookIsbnRepository.class);
            RankingRepository rankingRepository = context.getBean(RankingRepository.class);
            GoogleBooksService googleBooksService = context.getBean(GoogleBooksService.class);

            importFromJson(filePath, userRepository, bookRepository, bookIsbnRepository, rankingRepository, googleBooksService);
        }
    }

    private record JsonBook(String title, String author, String review, BookType type, BookCategory category, Integer rank) {}
    private record ParsedJsonList(String username, List<JsonBook> books) {}

    @SuppressWarnings("unchecked")
    private static ParsedJsonList parseJsonFile(String path) {
        Map<String, Object> data;
        try {
            ObjectMapper mapper = new ObjectMapper();
            data = mapper.readValue(new File(path), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            System.err.println("Error reading " + path + ": " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        String username = (String) data.get("username");
        if (username == null || username.isBlank()) {
            System.err.println("Error: no 'username' found in " + path);
            System.exit(1);
        }

        List<Map<String, String>> books = (List<Map<String, String>>) data.get("books");
        if (books == null) {
            System.err.println("Error: no 'books' array found in " + path);
            System.exit(1);
        }

        List<JsonBook> result = new ArrayList<>();
        for (int i = 0; i < books.size(); i++) {
            Map<String, String> entry = books.get(i);
            String bookLabel = "book #" + (i + 1);

            if (!entry.containsKey("title")) {
                System.err.println("Error: missing 'title' field on " + bookLabel);
                System.exit(1);
            }
            String title = entry.get("title");
            if (title == null || title.isBlank()) {
                System.err.println("Error: empty 'title' on " + bookLabel);
                System.exit(1);
            }

            if (!entry.containsKey("author")) {
                System.err.println("Error: missing 'author' field on " + bookLabel + " (" + title + ")");
                System.exit(1);
            }
            String author = entry.get("author");
            if (author == null || author.isBlank()) {
                System.err.println("Error: empty 'author' on " + bookLabel + " (" + title + ")");
                System.exit(1);
            }

            if (!entry.containsKey("rank")) {
                System.err.println("Error: missing 'rank' field on " + bookLabel + " (" + title + ")");
                System.exit(1);
            }
            String rank = entry.get("rank");
            boolean isRanked = rank != null && !rank.isEmpty();
            if (isRanked) {
                try {
                    Integer.parseInt(rank);
                } catch (NumberFormatException e) {
                    System.err.println("Error: non-numeric 'rank' \"" + rank + "\" on " + bookLabel + " (" + title + ")");
                    System.exit(1);
                }
            }

            if (!entry.containsKey("review")) {
                System.err.println("Error: missing 'review' field on " + bookLabel + " (" + title + ")");
                System.exit(1);
            }
            String review = entry.get("review");

            String categoryStr = entry.getOrDefault("category", "fiction");
            if (!"fiction".equals(categoryStr) && !"non-fiction".equals(categoryStr)) {
                System.err.println("Error: invalid 'category' \"" + categoryStr + "\" on " + bookLabel + " (" + title + "); expected 'fiction' or 'non-fiction'");
                System.exit(1);
            }

            BookType type = "fiction".equals(categoryStr) ? BookType.FICTION : BookType.NONFICTION;
            BookCategory category = isRanked ? BookCategory.LIKED : BookCategory.UNRANKED;

            Integer rankNum = isRanked ? Integer.parseInt(rank) : null;
            result.add(new JsonBook(title, author, review, type, category, rankNum));
        }

        return new ParsedJsonList(username, result);
    }

    private static void importFromJson(String path, UserRepository userRepository,
                                        BookRepository bookRepository, BookIsbnRepository bookIsbnRepository,
                                        RankingRepository rankingRepository, GoogleBooksService googleBooksService) {
        // Phase 1: parse and validate entire file before touching the database
        ParsedJsonList parsed = parseJsonFile(path);
        String username = parsed.username();
        List<JsonBook> allBooks = parsed.books();

        // Phase 2: create user or clear existing data
        User user;
        var existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            user = existing.get();
            rankingRepository.deleteByUserId(user.getId());
            System.out.println("Reimporting curated list: " + username + " (cleared existing books)");
        } else {
            user = new User(username);
            user.setCurated(true);
            user.setGuest(false);
            userRepository.save(user);
            System.out.println("Importing curated list: " + username);
        }

        // Group into four sorted lists
        List<JsonBook> fictionRanked = new ArrayList<>();
        List<JsonBook> fictionUnranked = new ArrayList<>();
        List<JsonBook> nonfictionRanked = new ArrayList<>();
        List<JsonBook> nonfictionUnranked = new ArrayList<>();

        for (JsonBook book : allBooks) {
            if (book.type() == BookType.FICTION) {
                if (book.category() == BookCategory.LIKED) fictionRanked.add(book);
                else fictionUnranked.add(book);
            } else {
                if (book.category() == BookCategory.LIKED) nonfictionRanked.add(book);
                else nonfictionUnranked.add(book);
            }
        }

        // Sort ranked lists by rank number to maintain correct position order
        fictionRanked.sort(Comparator.comparingInt(JsonBook::rank));
        nonfictionRanked.sort(Comparator.comparingInt(JsonBook::rank));

        importJsonBooks(user.getId(), fictionRanked, bookRepository, bookIsbnRepository, rankingRepository, googleBooksService);
        importJsonBooks(user.getId(), fictionUnranked, bookRepository, bookIsbnRepository, rankingRepository, googleBooksService);
        importJsonBooks(user.getId(), nonfictionRanked, bookRepository, bookIsbnRepository, rankingRepository, googleBooksService);
        importJsonBooks(user.getId(), nonfictionUnranked, bookRepository, bookIsbnRepository, rankingRepository, googleBooksService);

        System.out.println("Finished importing: " + username);
    }

    private static Book findOrCreateBook(String googleBooksId, String title, String author, String isbn13,
                                          BookRepository bookRepository, BookIsbnRepository bookIsbnRepository) {
        // 1. Try ISBN lookup via book_isbns table
        if (isbn13 != null) {
            List<BookIsbn> isbnMatches = bookIsbnRepository.findByIsbn13(isbn13);
            if (!isbnMatches.isEmpty()) {
                Book book = bookRepository.findById(isbnMatches.get(0).getBookId()).orElse(null);
                if (book != null) {
                    if (book.getGoogleBooksId() == null && googleBooksId != null) {
                        book.setGoogleBooksId(googleBooksId);
                        bookRepository.save(book);
                    }
                    return book;
                }
            }
        }

        // 2. Try normalized title+author match
        if (title != null && author != null) {
            List<Book> titleAuthorMatches = bookRepository.findByNormalizedTitleAndAuthor(title, author);
            if (!titleAuthorMatches.isEmpty()) {
                Book book = titleAuthorMatches.get(0);
                if (isbn13 != null && !bookIsbnRepository.existsByBookIdAndIsbn13(book.getId(), isbn13)) {
                    bookIsbnRepository.save(new BookIsbn(book.getId(), isbn13));
                }
                if (book.getGoogleBooksId() == null && googleBooksId != null) {
                    book.setGoogleBooksId(googleBooksId);
                    bookRepository.save(book);
                }
                return book;
            }
        }

        // 3. No match — create new Book
        Book book = bookRepository.save(new Book(googleBooksId, title, author, isbn13));
        if (isbn13 != null) {
            bookIsbnRepository.save(new BookIsbn(book.getId(), isbn13));
        }
        return book;
    }

    private static void importJsonBooks(Long userId, List<JsonBook> books,
                                         BookRepository bookRepository, BookIsbnRepository bookIsbnRepository,
                                         RankingRepository rankingRepository, GoogleBooksService googleBooksService) {
        int position = 0;
        for (JsonBook jb : books) {
            List<GoogleBooksService.BookResult> results = googleBooksService.searchBooks(jb.title() + ", " + jb.author());

            String googleBooksId;
            String title;
            String author;
            String isbn13;
            if (!results.isEmpty()) {
                GoogleBooksService.BookResult firstResult = results.get(0);
                googleBooksId = firstResult.googleBooksId();
                title = firstResult.title();
                author = firstResult.author();
                isbn13 = firstResult.isbn13();
            } else {
                author = jb.author();
                title = jb.title();
                googleBooksId = null;
                isbn13 = null;
            }

            Book book = findOrCreateBook(googleBooksId, title, author, isbn13, bookRepository, bookIsbnRepository);

            Ranking ranking = new Ranking(userId, book, jb.type(), jb.category(), position);
            if (jb.review() != null && !jb.review().isEmpty()) {
                ranking.setReview(jb.review());
            }
            rankingRepository.save(ranking);

            System.out.println("  " + (position + 1) + ". " + jb.title() + " by " + author);
            position++;

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
