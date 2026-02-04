package armchair.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
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

    public List<BookResult> searchByTitleAndAuthor(String title, String author, int maxResults) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String params = "title=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        if (author != null && !author.isBlank()) {
            params += "&author=" + URLEncoder.encode(author, StandardCharsets.UTF_8);
        }
        return doSearch(params, maxResults);
    }

    public List<BookResult> searchBooks(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return doSearch("q=" + URLEncoder.encode(query, StandardCharsets.UTF_8), maxResults);
    }

    private List<BookResult> doSearch(String queryParams, int maxResults) {
        try {
            String url = String.format(
                "https://openlibrary.org/search.json?%s&lang=en&limit=%d&fields=author_name,author_key,title,cover_edition_key,key,first_publish_year,editions,editions.title,editions.key",
                queryParams,
                maxResults
            );

            // Use URI.create to prevent RestTemplate from re-encoding already-encoded params
            String response = restTemplate.getForObject(URI.create(url), String.class);
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

                // If author name contains non-ASCII characters, fetch English name from author entity
                if (!isAscii(author)) {
                    JsonNode authorKeys = doc.get("author_key");
                    if (authorKeys != null && authorKeys.isArray() && authorKeys.size() > 0) {
                        String englishName = fetchEnglishAuthorName(authorKeys.get(0).asText());
                        if (englishName != null) {
                            author = englishName;
                        }
                    }
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

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /**
     * Fetches the author entity and returns an ASCII name if available.
     * Tries personal_name first (converting "Last, First" to "First Last"),
     * then falls back to the first ASCII alternate_name.
     */
    private String fetchEnglishAuthorName(String authorKey) {
        try {
            String url = "https://openlibrary.org/authors/" + authorKey + ".json";
            String response = restTemplate.getForObject(URI.create(url), String.class);
            JsonNode author = objectMapper.readTree(response);

            // Try personal_name (often "Last, First" format)
            if (author.has("personal_name")) {
                String personalName = author.get("personal_name").asText();
                if (isAscii(personalName)) {
                    if (personalName.contains(", ")) {
                        String[] parts = personalName.split(", ", 2);
                        return parts[1] + " " + parts[0];
                    }
                    return personalName;
                }
            }

            // Fall back to first ASCII alternate_name
            JsonNode altNames = author.get("alternate_names");
            if (altNames != null && altNames.isArray()) {
                for (JsonNode alt : altNames) {
                    String name = alt.asText();
                    if (isAscii(name)) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch author {}: {}", authorKey, e.getMessage());
        }
        return null;
    }
}
