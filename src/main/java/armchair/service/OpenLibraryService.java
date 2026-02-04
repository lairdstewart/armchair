package armchair.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class OpenLibraryService {
    private static final Logger log = LoggerFactory.getLogger(OpenLibraryService.class);

    private final RestTemplate restTemplate = new RestTemplateBuilder()
        .defaultHeader("User-Agent", "Armchair (armchair@lairdstewart.com)")
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BookResult(String workOlid, String coverEditionOlid, String title, String author, Integer firstPublishYear) {
        public String bookUrl() {
            if (workOlid != null) return "https://openlibrary.org/works/" + workOlid;
            return "https://www.google.com/search?udm=36&q=" + URLEncoder.encode(title + " " + author, StandardCharsets.UTF_8);
        }
    }

    public List<BookResult> searchBooks(String query) {
        return searchBooks(query, 3);
    }

    public List<BookResult> searchBooks(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            String url = String.format(
                "https://openlibrary.org/search.json?q=%s&lang=en&limit=%d&fields=author_name,title,cover_edition_key,key,first_publish_year,editions,editions.title,editions.key",
                URLEncoder.encode(query, StandardCharsets.UTF_8),
                maxResults
            );

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode docs = root.get("docs");

            if (docs == null || !docs.isArray()) {
                return List.of();
            }

            List<BookResult> results = new ArrayList<>();
            for (JsonNode doc : docs) {
                String key = doc.has("key") ? doc.get("key").asText() : null;
                if (key == null) continue;

                // Strip /works/ prefix from key to get the work OLID
                String workOlid = key.startsWith("/works/") ? key.substring("/works/".length()) : key;

                // Prefer English edition title over work-level title (which may be in the original language)
                String title = doc.has("title") ? doc.get("title").asText() : "Unknown Title";
                String coverEditionOlid = doc.has("cover_edition_key") ? doc.get("cover_edition_key").asText(null) : null;
                JsonNode editions = doc.path("editions").path("docs");
                if (editions.isArray() && editions.size() > 0) {
                    JsonNode edition = editions.get(0);
                    if (edition.has("title")) {
                        title = edition.get("title").asText();
                    }
                    String editionKey = edition.has("key") ? edition.get("key").asText() : null;
                    if (editionKey != null) {
                        coverEditionOlid = editionKey.startsWith("/books/") ? editionKey.substring("/books/".length()) : editionKey;
                    }
                }

                String author = "Unknown Author";
                JsonNode authorNames = doc.get("author_name");
                if (authorNames != null && authorNames.isArray() && authorNames.size() > 0) {
                    author = authorNames.get(0).asText();
                }

                Integer firstPublishYear = null;
                if (doc.has("first_publish_year") && !doc.get("first_publish_year").isNull()) {
                    firstPublishYear = doc.get("first_publish_year").asInt();
                }

                results.add(new BookResult(workOlid, coverEditionOlid, title, author, firstPublishYear));
            }

            return results;
        } catch (Exception e) {
            log.error("Error searching Open Library: {}", e.getMessage());
            return List.of();
        }
    }
}
