package armchair.tool;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.CuratedList;
import armchair.entity.CuratedRanking;
import armchair.repository.CuratedListRepository;
import armchair.repository.CuratedRankingRepository;
import armchair.service.BookService;
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
    private static final int API_RATE_LIMIT_DELAY_MS = 100;

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
            CuratedListRepository curatedListRepository = context.getBean(CuratedListRepository.class);
            BookService bookService = context.getBean(BookService.class);
            CuratedRankingRepository curatedRankingRepository = context.getBean(CuratedRankingRepository.class);
            OpenLibraryService openLibraryService = context.getBean(OpenLibraryService.class);

            try {
                importFromJson(filePath, curatedListRepository, bookService, curatedRankingRepository, openLibraryService);
            } catch (ImportException e) {
                log.error(e.getMessage());
                System.exit(1);
            }
        }
    }

    record JsonBook(String title, String author, String review, Bookshelf bookshelf, BookCategory category, Integer rank) {}
    record ParsedJsonList(String username, List<JsonBook> books) {}

    static class ImportException extends RuntimeException {
        ImportException(String message) { super(message); }
    }

    @SuppressWarnings("unchecked")
    static ParsedJsonList parseJsonFile(String path) {
        Map<String, Object> data;
        try {
            ObjectMapper mapper = new ObjectMapper();
            data = mapper.readValue(new File(path), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ImportException("Error reading " + path + ": " + e.getMessage());
        }

        return parseJsonData(data, path);
    }

    @SuppressWarnings("unchecked")
    static ParsedJsonList parseJsonData(Map<String, Object> data, String source) {
        String username = (String) data.get("username");
        if (username == null || username.isBlank()) {
            throw new ImportException("No 'username' found in " + source);
        }

        List<Map<String, String>> books = (List<Map<String, String>>) data.get("books");
        if (books == null) {
            throw new ImportException("No 'books' array found in " + source);
        }

        List<JsonBook> result = new ArrayList<>();
        for (int i = 0; i < books.size(); i++) {
            Map<String, String> entry = books.get(i);
            String bookLabel = "book #" + (i + 1);

            if (!entry.containsKey("title")) {
                throw new ImportException("Missing 'title' field on " + bookLabel);
            }
            String title = entry.get("title");
            if (title == null || title.isBlank()) {
                throw new ImportException("Empty 'title' on " + bookLabel);
            }

            if (!entry.containsKey("author")) {
                throw new ImportException("Missing 'author' field on " + bookLabel + " (" + title + ")");
            }
            String author = entry.get("author");
            if (author == null || author.isBlank()) {
                throw new ImportException("Empty 'author' on " + bookLabel + " (" + title + ")");
            }

            if (!entry.containsKey("rank")) {
                throw new ImportException("Missing 'rank' field on " + bookLabel + " (" + title + ")");
            }
            String rank = entry.get("rank");
            boolean isRanked = rank != null && !rank.isEmpty();
            if (isRanked) {
                try {
                    Integer.parseInt(rank);
                } catch (NumberFormatException e) {
                    throw new ImportException("Non-numeric 'rank' \"" + rank + "\" on " + bookLabel + " (" + title + ")");
                }
            }

            if (!entry.containsKey("review")) {
                throw new ImportException("Missing 'review' field on " + bookLabel + " (" + title + ")");
            }
            String review = entry.get("review");

            String categoryStr = entry.getOrDefault("category", "fiction");
            if (!"fiction".equals(categoryStr) && !"non-fiction".equals(categoryStr)) {
                throw new ImportException("Invalid 'category' \"" + categoryStr + "\" on " + bookLabel + " (" + title + "); expected 'fiction' or 'non-fiction'");
            }

            Bookshelf bookshelf = "fiction".equals(categoryStr) ? Bookshelf.FICTION : Bookshelf.NONFICTION;
            BookCategory category = isRanked ? BookCategory.LIKED : BookCategory.UNRANKED;

            Integer rankNum = isRanked ? Integer.parseInt(rank) : null;
            result.add(new JsonBook(title, author, review, bookshelf, category, rankNum));
        }

        return new ParsedJsonList(username, result);
    }

    static void importFromJson(String path, CuratedListRepository curatedListRepository,
                                       BookService bookService,
                                       CuratedRankingRepository curatedRankingRepository, OpenLibraryService openLibraryService) {
        ParsedJsonList parsed = parseJsonFile(path);
        importParsedList(parsed, curatedListRepository, bookService, curatedRankingRepository, openLibraryService);
    }

    static void importParsedList(ParsedJsonList parsed, CuratedListRepository curatedListRepository,
                                         BookService bookService,
                                         CuratedRankingRepository curatedRankingRepository, OpenLibraryService openLibraryService) {
        String username = parsed.username();
        List<JsonBook> allBooks = parsed.books();

        CuratedList curatedList;
        var existing = curatedListRepository.findByUsername(username);
        if (existing.isPresent()) {
            curatedList = existing.get();
            curatedRankingRepository.deleteByCuratedListId(curatedList.getId());
            log.info("Reimporting curated list: {} (cleared existing books)", username);
        } else {
            curatedList = new CuratedList(username);
            curatedListRepository.save(curatedList);
            log.info("Importing curated list: {}", username);
        }

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

        fictionRanked.sort(Comparator.comparingInt(JsonBook::rank));
        nonfictionRanked.sort(Comparator.comparingInt(JsonBook::rank));

        importJsonBooks(curatedList, fictionRanked, bookService, curatedRankingRepository, openLibraryService);
        importJsonBooks(curatedList, fictionUnranked, bookService, curatedRankingRepository, openLibraryService);
        importJsonBooks(curatedList, nonfictionRanked, bookService, curatedRankingRepository, openLibraryService);
        importJsonBooks(curatedList, nonfictionUnranked, bookService, curatedRankingRepository, openLibraryService);

        log.info("Finished importing: {}", username);
    }

    static void importJsonBooks(CuratedList curatedList, List<JsonBook> books,
                                        BookService bookService,
                                        CuratedRankingRepository curatedRankingRepository, OpenLibraryService openLibraryService) {
        int position = 0;
        for (JsonBook jb : books) {
            List<OpenLibraryService.BookResult> results = openLibraryService.searchBooks(jb.title() + ", " + jb.author());

            String workOlid;
            String editionOlid;
            String title;
            String author;
            Integer firstPublishYear;
            Integer coverId;
            if (!results.isEmpty()) {
                OpenLibraryService.BookResult firstResult = results.get(0);
                workOlid = firstResult.workOlid();
                editionOlid = firstResult.editionOlid();
                title = firstResult.title();
                author = firstResult.author();
                firstPublishYear = firstResult.firstPublishYear();
                coverId = firstResult.coverId();
            } else {
                author = jb.author();
                title = jb.title();
                workOlid = null;
                editionOlid = null;
                firstPublishYear = null;
                coverId = null;
            }

            Book book = bookService.findOrCreateBook(workOlid, editionOlid, title, author, firstPublishYear, coverId);

            CuratedRanking ranking = new CuratedRanking(curatedList, book, jb.bookshelf(), jb.category(), position);
            if (jb.review() != null && !jb.review().isEmpty()) {
                ranking.setReview(jb.review());
            }
            curatedRankingRepository.save(ranking);

            log.info("  {}. {} by {}", position + 1, jb.title(), author);
            position++;

            try {
                Thread.sleep(API_RATE_LIMIT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
