package armchair.tool;

import armchair.service.OpenLibraryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time enrichment tool that looks up Open Library metadata for books in curated list JSON files.
 * Adds work_olid, cover_id, and first_publish_year to each book entry.
 *
 * Usage: CuratedListEnricher <path-to-json>
 */
public class CuratedListEnricher {
    private static final Logger log = LoggerFactory.getLogger(CuratedListEnricher.class);
    private static final int API_RATE_LIMIT_DELAY_MS = 100;

    @Configuration
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class})
    @ComponentScan(basePackages = "armchair.service")
    @EntityScan("armchair.entity")
    @EnableJpaRepositories("armchair.repository")
    static class Config {}

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        if (args.length != 1) {
            log.error("Usage: CuratedListEnricher <absolute-path-to-file.json>");
            System.exit(1);
        }

        String filePath = args[0];
        File file = new File(filePath);

        SpringApplication app = new SpringApplication(Config.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = app.run(args)) {
            OpenLibraryService openLibraryService = context.getBean(OpenLibraryService.class);
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);

            Map<String, Object> data = mapper.readValue(file, new TypeReference<>() {});
            List<Map<String, Object>> books = (List<Map<String, Object>>) data.get("books");

            if (books == null) {
                log.error("No 'books' array found in {}", filePath);
                System.exit(1);
            }

            List<String> noMatch = new ArrayList<>();
            int enriched = 0;

            for (int i = 0; i < books.size(); i++) {
                Map<String, Object> entry = books.get(i);
                String title = (String) entry.get("title");
                String author = (String) entry.get("author");

                // Skip if already enriched
                if (entry.containsKey("work_olid") && entry.get("work_olid") != null) {
                    log.info("  [{}] Already enriched: {} by {}", i + 1, title, author);
                    continue;
                }

                // Strip "translated by..." and "(year)" suffixes from author for better search results
                String searchAuthor = author;
                if (searchAuthor.contains(", translated by") || searchAuthor.contains("; translated by")) {
                    searchAuthor = searchAuthor.replaceAll("[,;]\\s*translated by.*", "").trim();
                }
                if (searchAuthor.contains("(")) {
                    searchAuthor = searchAuthor.replaceAll("\\(.*?\\)", "").trim();
                }

                List<OpenLibraryService.BookResult> results = openLibraryService.searchByTitleAndAuthor(title, searchAuthor, 1);

                // Fallback: try title-only search if title+author fails
                if (results.isEmpty()) {
                    results = openLibraryService.searchByTitleAndAuthor(title, null, 1);
                }

                if (!results.isEmpty()) {
                    OpenLibraryService.BookResult result = results.get(0);

                    // Use LinkedHashMap to preserve insertion order
                    Map<String, Object> ordered = new LinkedHashMap<>();
                    ordered.put("rank", entry.get("rank"));
                    ordered.put("title", entry.get("title"));
                    ordered.put("author", entry.get("author"));
                    if (entry.containsKey("category")) {
                        ordered.put("category", entry.get("category"));
                    }
                    ordered.put("review", entry.get("review"));
                    ordered.put("work_olid", result.workOlid());
                    ordered.put("cover_id", result.coverId() != null ? String.valueOf(result.coverId()) : null);
                    ordered.put("first_publish_year", result.firstPublishYear() != null ? String.valueOf(result.firstPublishYear()) : null);

                    books.set(i, ordered);
                    enriched++;

                    log.info("  [{}] {} by {} -> {} (cover:{}, year:{})",
                            i + 1, title, author, result.workOlid(), result.coverId(), result.firstPublishYear());
                } else {
                    noMatch.add(String.format("[%d] %s by %s", i + 1, title, author));
                    log.warn("  [{}] NO MATCH: {} by {}", i + 1, title, author);
                }

                try {
                    Thread.sleep(API_RATE_LIMIT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            mapper.writeValue(file, data);

            log.info("Enriched {} of {} books in {}", enriched, books.size(), filePath);
            if (!noMatch.isEmpty()) {
                log.warn("No matches found for {} books:", noMatch.size());
                noMatch.forEach(s -> log.warn("  {}", s));
            }
        } catch (Exception e) {
            log.error("Error enriching {}: {}", filePath, e.getMessage());
            System.exit(1);
        }
    }
}
