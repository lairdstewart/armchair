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

    public record BookResult(String workOlid, String editionOlid, String title, String author, Integer firstPublishYear) {
        public String bookUrl() {
            if (workOlid != null) return "https://openlibrary.org/works/" + workOlid;
            return "https://openlibrary.org/search?q=" + URLEncoder.encode(title + " " + author, StandardCharsets.UTF_8);
        }
    }

    public record EditionResult(String editionOlid, String title, String isbn13, Integer coverId, String publisher, String publishDate) {
        public String coverUrl() {
            if (coverId != null) return "https://covers.openlibrary.org/b/id/" + coverId + "-M.jpg";
            if (editionOlid != null) return "https://covers.openlibrary.org/b/olid/" + editionOlid + "-M.jpg";
            return null;
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
                String editionOlid = doc.has("cover_edition_key") ? doc.get("cover_edition_key").asText(null) : null;
                JsonNode editions = doc.path("editions").path("docs");
                if (editions.isArray() && editions.size() > 0) {
                    JsonNode edition = editions.get(0);
                    if (edition.has("title")) {
                        title = edition.get("title").asText();
                    }
                    String editionKey = edition.has("key") ? edition.get("key").asText() : null;
                    if (editionKey != null) {
                        editionOlid = editionKey.startsWith("/books/") ? editionKey.substring("/books/".length()) : editionKey;
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

                results.add(new BookResult(workOlid, editionOlid, title, author, firstPublishYear));
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

    /**
     * Fetches editions for a work from Open Library's editions API.
     * Only returns editions that have an ISBN (either isbn_13 or isbn_10).
     */
    public List<EditionResult> getEditionsForWork(String workOlid, int limit, int offset) {
        if (workOlid == null || workOlid.isBlank()) {
            return List.of();
        }
        try {
            String url = String.format(
                "https://openlibrary.org/works/%s/editions.json?limit=%d&offset=%d",
                workOlid, limit, offset
            );

            String response = restTemplate.getForObject(URI.create(url), String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode entries = root.get("entries");

            if (entries == null || !entries.isArray()) {
                return List.of();
            }

            List<EditionResult> results = new ArrayList<>();
            for (JsonNode entry : entries) {
                // Extract ISBN - prefer isbn_13, fall back to isbn_10 (converted)
                String isbn13 = extractIsbn13(entry);
                if (isbn13 == null) {
                    continue; // Skip editions without ISBN
                }

                // Extract edition OLID from key field
                String key = entry.has("key") ? entry.get("key").asText() : null;
                if (key == null) continue;
                String editionOlid = key.startsWith("/books/") ? key.substring("/books/".length()) : key;

                // Extract title
                String title = entry.has("title") ? entry.get("title").asText() : "Unknown Title";

                // Extract cover ID (numeric ID for covers.openlibrary.org/b/id/)
                Integer coverId = null;
                JsonNode covers = entry.get("covers");
                if (covers != null && covers.isArray() && covers.size() > 0) {
                    coverId = covers.get(0).asInt();
                }

                // Extract publisher (first one from array)
                String publisher = null;
                JsonNode publishers = entry.get("publishers");
                if (publishers != null && publishers.isArray() && publishers.size() > 0) {
                    publisher = publishers.get(0).asText();
                }

                // Extract publish date
                String publishDate = entry.has("publish_date") ? entry.get("publish_date").asText() : null;

                results.add(new EditionResult(editionOlid, title, isbn13, coverId, publisher, publishDate));
            }

            return results;
        } catch (Exception e) {
            log.error("Error fetching editions for work {}: {}", workOlid, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extracts ISBN-13 from an edition entry. Prefers isbn_13 field, converts isbn_10 if needed.
     */
    private String extractIsbn13(JsonNode entry) {
        // Try isbn_13 first
        JsonNode isbn13Node = entry.get("isbn_13");
        if (isbn13Node != null && isbn13Node.isArray() && isbn13Node.size() > 0) {
            return isbn13Node.get(0).asText();
        }

        // Fall back to isbn_10 and convert
        JsonNode isbn10Node = entry.get("isbn_10");
        if (isbn10Node != null && isbn10Node.isArray() && isbn10Node.size() > 0) {
            return convertIsbn10ToIsbn13(isbn10Node.get(0).asText());
        }

        return null;
    }

    /**
     * Converts an ISBN-10 to ISBN-13 by prepending "978" and recalculating the check digit.
     */
    static String convertIsbn10ToIsbn13(String isbn10) {
        if (isbn10 == null || isbn10.length() != 10) {
            return null;
        }

        // Remove any hyphens
        isbn10 = isbn10.replace("-", "");
        if (isbn10.length() != 10) {
            return null;
        }

        // Take first 9 digits and prepend "978"
        String prefix = "978" + isbn10.substring(0, 9);

        // Calculate ISBN-13 check digit
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = Character.getNumericValue(prefix.charAt(i));
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int checkDigit = (10 - (sum % 10)) % 10;

        return prefix + checkDigit;
    }
}
