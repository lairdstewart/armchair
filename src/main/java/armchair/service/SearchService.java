package armchair.service;

import armchair.repository.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    @Autowired
    private OpenLibraryService openLibraryService;

    @Autowired
    private BookRepository bookRepository;

    /**
     * Combines results from structured title+author search with general query search.
     * Handles cases where Open Library's canonical title differs from the common title
     * (e.g., "Nineteen Eighty-Four" vs "1984"). Results are deduplicated by workOlid and
     * sorted by edition count (descending) so the most authoritative work appears first.
     */
    public List<OpenLibraryService.BookResult> combinedSearch(String title, String author, int maxResults) {
        List<OpenLibraryService.BookResult> structured =
            openLibraryService.searchByTitleAndAuthor(title, author, maxResults);
        List<OpenLibraryService.BookResult> general =
            openLibraryService.searchBooks(title + " " + author, maxResults);

        List<OpenLibraryService.BookResult> combined = new ArrayList<>(structured);
        combined.addAll(general);
        combined = new ArrayList<>(deduplicateResults(combined));

        combined.sort((a, b) -> {
            int countA = a.editionCount() != null ? a.editionCount() : 0;
            int countB = b.editionCount() != null ? b.editionCount() : 0;
            return Integer.compare(countB, countA);
        });

        return combined.size() > maxResults ? combined.subList(0, maxResults) : combined;
    }

    public static List<OpenLibraryService.BookResult> deduplicateResults(List<OpenLibraryService.BookResult> results) {
        var seen = new LinkedHashSet<String>();
        return results.stream().filter(r -> seen.add(bookResultKey(r))).toList();
    }

    public List<OpenLibraryService.BookResult> getRandomBooksExcluding(Map<String, ?> userBooks) {
        return bookRepository.findRandomBooks().stream()
            .filter(b -> !userBooks.containsKey(b.getWorkOlid()))
            .map(b -> new OpenLibraryService.BookResult(b.getWorkOlid(), b.getEditionOlid(),
                b.getTitle(), b.getAuthor(), b.getFirstPublishYear(), b.getCoverId(), null))
            .toList();
    }

    private static String bookResultKey(OpenLibraryService.BookResult b) {
        return b.workOlid();
    }
}
