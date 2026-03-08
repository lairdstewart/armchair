package armchair.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static armchair.service.OpenLibraryService.OL_WORKS_URL;
import static armchair.service.OpenLibraryService.OL_SEARCH_URL;
import static armchair.service.OpenLibraryService.OL_COVER_URL;
import static armchair.service.OpenLibraryService.OL_COVER_SUFFIX;

public record BookInfo(Long id, String workOlid, String editionOlid, String title, String author,
                       String review, Integer firstPublishYear, Integer coverId) {
    public String bookUrl() {
        if (workOlid != null) return OL_WORKS_URL + workOlid;
        return OL_SEARCH_URL + URLEncoder.encode(title + " " + author, StandardCharsets.UTF_8);
    }

    public String coverUrl() {
        if (coverId != null) return OL_COVER_URL + coverId + OL_COVER_SUFFIX;
        return null;
    }
}
