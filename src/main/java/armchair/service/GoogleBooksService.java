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

    public record BookResult(String googleBooksId, String title, String author) {}

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

                    results.add(new BookResult(googleBooksId, title, author));
                }
            }

            return results;
        } catch (Exception e) {
            System.err.println("Error searching Google Books: " + e.getMessage());
            return List.of();
        }
    }
}
