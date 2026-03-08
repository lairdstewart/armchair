package armchair.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public record BookInfo(Long id, String workOlid, String editionOlid, String title, String author,
                       String review, Integer firstPublishYear, Integer coverId) {
    public String bookUrl() {
        if (workOlid != null) return "https://openlibrary.org/works/" + workOlid;
        return "https://openlibrary.org/search?q=" + URLEncoder.encode(title + " " + author, StandardCharsets.UTF_8);
    }

    public String coverUrl() {
        if (coverId != null) return "https://covers.openlibrary.org/b/id/" + coverId + "-M.jpg";
        return null;
    }
}
