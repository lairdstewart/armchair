package armchair.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class OpenLibraryService {
    private static final Logger log = LoggerFactory.getLogger(OpenLibraryService.class);

    public static final String OL_WORKS_URL = "https://openlibrary.org/works/";
    public static final String OL_SEARCH_URL = "https://openlibrary.org/search?q=";
    public static final String OL_AUTHOR_SEARCH_URL = "https://openlibrary.org/search/authors?q=";
    public static final String OL_COVER_URL = "https://covers.openlibrary.org/b/id/";
    public static final String OL_COVER_SUFFIX = "-M.jpg";

    private static final String OL_SEARCH_API_URL = "https://openlibrary.org/search.json?%s&lang=en&limit=%d&fields=author_name,author_key,title,cover_edition_key,cover_i,key,first_publish_year,edition_count,editions,editions.title,editions.key";
    private static final String OL_EDITIONS_API_URL = "https://openlibrary.org/works/%s/editions.json?limit=%d&offset=0";
    private static final String OL_AUTHOR_API_URL = "https://openlibrary.org/authors/%s.json";
    private static final String WORKS_KEY_PREFIX = "/works/";
    private static final String BOOKS_KEY_PREFIX = "/books/";
    private static final String ENGLISH_LANGUAGE_KEY = "/languages/eng";
    private static final int SCORE_PREFERRED_EDITION = 1000;
    private static final int SCORE_ENGLISH = 100;
    private static final int SCORE_ASCII_TITLE = 50;
    private static final int SCORE_HAS_COVER = 25;
    public static final int DEFAULT_EDITION_FETCH_LIMIT = 50;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenLibraryService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
            .defaultHeader("User-Agent", "Armchair (help@armchairlist.com)")
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(10))
            .build();
    }

    public record BookResult(String workOlid, String editionOlid, String title, String author, Integer firstPublishYear, Integer coverId, Integer editionCount) {
        public String bookUrl() {
            if (workOlid != null) return OL_WORKS_URL + workOlid;
            return OL_SEARCH_URL + URLEncoder.encode(title + " " + author, StandardCharsets.UTF_8);
        }
        public String coverUrl() {
            if (coverId != null) return OL_COVER_URL + coverId + OL_COVER_SUFFIX;
            return null;
        }
    }

    public record EditionResult(String editionOlid, String title, String isbn13, Integer coverId, String publisher, String publishDate) {
        public String coverUrl() {
            if (coverId != null) return OL_COVER_URL + coverId + OL_COVER_SUFFIX;
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
                OL_SEARCH_API_URL,
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
                String workOlid = key.startsWith(WORKS_KEY_PREFIX) ? key.substring(WORKS_KEY_PREFIX.length()) : key;

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
                        editionOlid = editionKey.startsWith(BOOKS_KEY_PREFIX) ? editionKey.substring(BOOKS_KEY_PREFIX.length()) : editionKey;
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

                Integer coverId = null;
                if (doc.has("cover_i") && !doc.get("cover_i").isNull()) {
                    coverId = doc.get("cover_i").asInt();
                }

                Integer editionCount = null;
                if (doc.has("edition_count") && !doc.get("edition_count").isNull()) {
                    editionCount = doc.get("edition_count").asInt();
                }

                results.add(new BookResult(workOlid, editionOlid, title, author, firstPublishYear, coverId, editionCount));
            }

            return results;
        } catch (ResourceAccessException e) {
            throw e;
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
            String url = String.format(OL_AUTHOR_API_URL, authorKey);
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
     *
     * Results are sorted to prioritize English editions with covers, since the
     * Open Library API returns editions in arbitrary database insertion order.
     * See open-library-api-notes.txt for details.
     */
    public List<EditionResult> getEditionsForWork(String workOlid, int limit, int offset) {
        return getEditionsForWork(workOlid, limit, offset, null);
    }

    /**
     * Fetches editions for a work, with an optional preferred edition to show first.
     * The preferredEditionOlid (typically the cover edition from search) gets a large
     * score boost to ensure it appears at the top of results.
     */
    public List<EditionResult> getEditionsForWork(String workOlid, int limit, int offset, String preferredEditionOlid) {
        if (workOlid == null || workOlid.isBlank()) {
            return List.of();
        }
        try {
            // Fetch more editions than requested so we can sort and return the best ones.
            // The API returns editions in database insertion order, not by relevance.
            int fetchLimit = Math.max(DEFAULT_EDITION_FETCH_LIMIT, limit + offset);
            String url = String.format(
                OL_EDITIONS_API_URL,
                workOlid, fetchLimit
            );

            String response = restTemplate.getForObject(URI.create(url), String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode entries = root.get("entries");

            if (entries == null || !entries.isArray()) {
                return List.of();
            }

            List<EditionWithScore> scoredResults = new ArrayList<>();
            for (JsonNode entry : entries) {
                // Extract ISBN - prefer isbn_13, fall back to isbn_10 (converted)
                String isbn13 = extractIsbn13(entry);
                if (isbn13 == null) {
                    continue; // Skip editions without ISBN
                }

                // Extract edition OLID from key field
                String key = entry.has("key") ? entry.get("key").asText() : null;
                if (key == null) continue;
                String editionOlid = key.startsWith(BOOKS_KEY_PREFIX) ? key.substring(BOOKS_KEY_PREFIX.length()) : key;

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

                // Check if English language
                boolean isEnglish = false;
                JsonNode languages = entry.get("languages");
                if (languages != null && languages.isArray()) {
                    for (JsonNode lang : languages) {
                        String langKey = lang.has("key") ? lang.get("key").asText() : "";
                        if (langKey.equals(ENGLISH_LANGUAGE_KEY)) {
                            isEnglish = true;
                            break;
                        }
                    }
                }

                // Calculate relevance score (higher = better)
                int score = 0;
                if (preferredEditionOlid != null && editionOlid.equals(preferredEditionOlid)) {
                    score += SCORE_PREFERRED_EDITION;  // Strongly prefer the cover edition from search
                }
                if (isEnglish) score += SCORE_ENGLISH;
                if (isAscii(title)) score += SCORE_ASCII_TITLE;  // ASCII title suggests English
                if (coverId != null) score += SCORE_HAS_COVER;

                EditionResult result = new EditionResult(editionOlid, title, isbn13, coverId, publisher, publishDate);
                scoredResults.add(new EditionWithScore(result, score));
            }

            // Sort by score descending, then apply offset and limit
            scoredResults.sort((a, b) -> Integer.compare(b.score, a.score));

            List<EditionResult> results = new ArrayList<>();
            for (int i = offset; i < scoredResults.size() && results.size() < limit; i++) {
                results.add(scoredResults.get(i).edition);
            }

            return results;
        } catch (ResourceAccessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching editions for work {}: {}", workOlid, e.getMessage());
            return List.of();
        }
    }

    private record EditionWithScore(EditionResult edition, int score) {}

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
