package armchair.tool;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import armchair.service.OpenLibraryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(CuratedListImporter.class);

    @Configuration
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class})
    @ComponentScan(basePackages = "armchair.service")
    @EntityScan("armchair.entity")
    @EnableJpaRepositories("armchair.repository")
    static class Config {}

    public static void main(String[] args) {
        if (args.length != 1) {
            log.error("Usage: CuratedListImporter <absolute-path-to-file.json>");
            System.exit(1);
        }

        String filePath = args[0];

        SpringApplication app = new SpringApplication(Config.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = app.run(args)) {
            UserRepository userRepository = context.getBean(UserRepository.class);
            BookRepository bookRepository = context.getBean(BookRepository.class);
            RankingRepository rankingRepository = context.getBean(RankingRepository.class);
            OpenLibraryService openLibraryService = context.getBean(OpenLibraryService.class);

            importFromJson(filePath, userRepository, bookRepository, rankingRepository, openLibraryService);
        }
    }

    private record JsonBook(String title, String author, String review, Bookshelf bookshelf, BookCategory category, Integer rank) {}
    private record ParsedJsonList(String username, List<JsonBook> books) {}

    @SuppressWarnings("unchecked")
    private static ParsedJsonList parseJsonFile(String path) {
        Map<String, Object> data;
        try {
            ObjectMapper mapper = new ObjectMapper();
            data = mapper.readValue(new File(path), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Error reading {}: {}", path, e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        String username = (String) data.get("username");
        if (username == null || username.isBlank()) {
            log.error("No 'username' found in {}", path);
            System.exit(1);
        }

        List<Map<String, String>> books = (List<Map<String, String>>) data.get("books");
        if (books == null) {
            log.error("No 'books' array found in {}", path);
            System.exit(1);
        }

        List<JsonBook> result = new ArrayList<>();
        for (int i = 0; i < books.size(); i++) {
            Map<String, String> entry = books.get(i);
            String bookLabel = "book #" + (i + 1);

            if (!entry.containsKey("title")) {
                log.error("Missing 'title' field on {}", bookLabel);
                System.exit(1);
            }
            String title = entry.get("title");
            if (title == null || title.isBlank()) {
                log.error("Empty 'title' on {}", bookLabel);
                System.exit(1);
            }

            if (!entry.containsKey("author")) {
                log.error("Missing 'author' field on {} ({})", bookLabel, title);
                System.exit(1);
            }
            String author = entry.get("author");
            if (author == null || author.isBlank()) {
                log.error("Empty 'author' on {} ({})", bookLabel, title);
                System.exit(1);
            }

            if (!entry.containsKey("rank")) {
                log.error("Missing 'rank' field on {} ({})", bookLabel, title);
                System.exit(1);
            }
            String rank = entry.get("rank");
            boolean isRanked = rank != null && !rank.isEmpty();
            if (isRanked) {
                try {
                    Integer.parseInt(rank);
                } catch (NumberFormatException e) {
                    log.error("Non-numeric 'rank' \"{}\" on {} ({})", rank, bookLabel, title);
                    System.exit(1);
                }
            }

            if (!entry.containsKey("review")) {
                log.error("Missing 'review' field on {} ({})", bookLabel, title);
                System.exit(1);
            }
            String review = entry.get("review");

            String categoryStr = entry.getOrDefault("category", "fiction");
            if (!"fiction".equals(categoryStr) && !"non-fiction".equals(categoryStr)) {
                log.error("Invalid 'category' \"{}\" on {} ({}); expected 'fiction' or 'non-fiction'", categoryStr, bookLabel, title);
                System.exit(1);
            }

            Bookshelf bookshelf = "fiction".equals(categoryStr) ? Bookshelf.FICTION : Bookshelf.NONFICTION;
            BookCategory category = isRanked ? BookCategory.LIKED : BookCategory.UNRANKED;

            Integer rankNum = isRanked ? Integer.parseInt(rank) : null;
            result.add(new JsonBook(title, author, review, bookshelf, category, rankNum));
        }

        return new ParsedJsonList(username, result);
    }

    private static void importFromJson(String path, UserRepository userRepository,
                                        BookRepository bookRepository,
                                        RankingRepository rankingRepository, OpenLibraryService openLibraryService) {
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
            log.info("Reimporting curated list: {} (cleared existing books)", username);
        } else {
            user = new User(username);
            user.setCurated(true);
            user.setGuest(false);
            userRepository.save(user);
            log.info("Importing curated list: {}", username);
        }

        // Group into four sorted lists
        List<JsonBook> fictionRanked = new ArrayList<>();
        List<JsonBook> fictionUnranked = new ArrayList<>();
        List<JsonBook> nonfictionRanked = new ArrayList<>();
        List<JsonBook> nonfictionUnranked = new ArrayList<>();

        for (JsonBook book : allBooks) {
            if (book.bookshelf() == Bookshelf.FICTION) {
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

        importJsonBooks(user.getId(), fictionRanked, bookRepository, rankingRepository, openLibraryService);
        importJsonBooks(user.getId(), fictionUnranked, bookRepository, rankingRepository, openLibraryService);
        importJsonBooks(user.getId(), nonfictionRanked, bookRepository, rankingRepository, openLibraryService);
        importJsonBooks(user.getId(), nonfictionUnranked, bookRepository, rankingRepository, openLibraryService);

        log.info("Finished importing: {}", username);
    }

    private static Book findOrCreateBook(String workOlid, String coverEditionOlid, String title, String author, Integer firstPublishYear,
                                          BookRepository bookRepository) {
        // Look up by workOlid
        if (workOlid != null) {
            var existing = bookRepository.findByWorkOlid(workOlid);
            if (existing.isPresent()) {
                Book book = existing.get();
                if (book.getCoverEditionOlid() == null && coverEditionOlid != null) {
                    book.setCoverEditionOlid(coverEditionOlid);
                    bookRepository.save(book);
                }
                return book;
            }
        }

        // For unverified books (null workOlid), check by title+author to avoid duplicates
        if (workOlid == null) {
            var matches = bookRepository.findByTitleAndAuthorIgnoreCase(title, author);
            if (!matches.isEmpty()) {
                if (matches.size() > 1) {
                    log.error("Multiple books found for title=\"{}\" author=\"{}\": {} rows", title, author, matches.size());
                }
                // Prefer the verified book (has workOlid), fall back to first
                return matches.stream().filter(b -> b.getWorkOlid() != null).findFirst().orElse(matches.get(0));
            }
        }

        // No match — create new Book
        return bookRepository.save(new Book(workOlid, coverEditionOlid, title, author, firstPublishYear));
    }

    private static void importJsonBooks(Long userId, List<JsonBook> books,
                                         BookRepository bookRepository,
                                         RankingRepository rankingRepository, OpenLibraryService openLibraryService) {
        int position = 0;
        for (JsonBook jb : books) {
            List<OpenLibraryService.BookResult> results = openLibraryService.searchBooks(jb.title() + ", " + jb.author());

            String workOlid;
            String coverEditionOlid;
            String title;
            String author;
            Integer firstPublishYear;
            if (!results.isEmpty()) {
                OpenLibraryService.BookResult firstResult = results.get(0);
                workOlid = firstResult.workOlid();
                coverEditionOlid = firstResult.coverEditionOlid();
                title = firstResult.title();
                author = firstResult.author();
                firstPublishYear = firstResult.firstPublishYear();
            } else {
                author = jb.author();
                title = jb.title();
                workOlid = null;
                coverEditionOlid = null;
                firstPublishYear = null;
            }

            Book book = findOrCreateBook(workOlid, coverEditionOlid, title, author, firstPublishYear, bookRepository);

            Ranking ranking = new Ranking(userId, book, jb.bookshelf(), jb.category(), position);
            if (jb.review() != null && !jb.review().isEmpty()) {
                ranking.setReview(jb.review());
            }
            rankingRepository.save(ranking);

            log.info("  {}. {} by {}", position + 1, jb.title(), author);
            position++;

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
