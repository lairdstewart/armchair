package armchair.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class GoogleBooksService {

    @Value("${google.books.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BookResult(String googleBooksId, String title, String author, String isbn13) {
        public String bookUrl() {
            if (googleBooksId != null) return "https://books.google.com/books?id=" + googleBooksId;
            if (isbn13 != null) return "https://www.google.com/search?tbo=p&tbm=bks&q=isbn:" + isbn13;
            return null;
        }
    }

    public static String convertIsbn10To13(String isbn10) {
        if (isbn10 == null || isbn10.length() != 10) return null;
        String prefix = "978" + isbn10.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = prefix.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int checkDigit = (10 - (sum % 10)) % 10;
        return prefix + checkDigit;
    }

    public List<BookResult> searchBooks(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            String url = String.format(
                "https://www.googleapis.com/books/v1/volumes?q=%s&maxResults=5&key=%s",
                query.replace(" ", "+"),
                apiKey
            );

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode items = root.get("items");

            if (items == null || !items.isArray()) {
                return List.of();
            }

            List<BookResult> results = new ArrayList<>();
            for (JsonNode item : items) {
                String googleBooksId = item.has("id") ? item.get("id").asText() : null;
                JsonNode volumeInfo = item.get("volumeInfo");
                if (volumeInfo != null && googleBooksId != null) {
                    String title = volumeInfo.has("title") ? volumeInfo.get("title").asText() : "Unknown Title";
                    String author = "Unknown Author";

                    JsonNode authors = volumeInfo.get("authors");
                    if (authors != null && authors.isArray() && authors.size() > 0) {
                        author = authors.get(0).asText();
                    }

                    String isbn13 = null;
                    String isbn10 = null;
                    JsonNode identifiers = volumeInfo.get("industryIdentifiers");
                    if (identifiers != null && identifiers.isArray()) {
                        for (JsonNode id : identifiers) {
                            String idType = id.path("type").asText();
                            if ("ISBN_13".equals(idType)) {
                                isbn13 = id.path("identifier").asText();
                            } else if ("ISBN_10".equals(idType)) {
                                isbn10 = id.path("identifier").asText();
                            }
                        }
                    }
                    if (isbn13 == null && isbn10 != null) {
                        isbn13 = convertIsbn10To13(isbn10);
                    }

                    results.add(new BookResult(googleBooksId, title, author, isbn13));
                }
            }

            return results;
        } catch (Exception e) {
            System.err.println("Error searching Google Books: " + e.getMessage());
            return List.of();
        }
    }
}
