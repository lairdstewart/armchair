package armchair.service;

import armchair.entity.Book;
import armchair.repository.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BookService {

    @Autowired
    private BookRepository bookRepository;

    /**
     * Finds an existing book or creates a new one. Deduplicates by workOlid first,
     * then falls back to case-insensitive title+author match for unverified books.
     */
    public Book findOrCreateBook(String workOlid, String coverEditionOlid, String title, String author, Integer firstPublishYear) {
        // Look up by workOlid
        if (workOlid != null) {
            var existing = bookRepository.findByWorkOlid(workOlid);
            if (existing.isPresent()) {
                Book book = existing.get();
                // Enrich with coverEditionOlid if missing
                if (book.getCoverEditionOlid() == null && coverEditionOlid != null) {
                    book.setCoverEditionOlid(coverEditionOlid);
                    bookRepository.save(book);
                }
                return book;
            }
        }

        // For unverified books (null workOlid), check by title+author to avoid duplicates
        if (workOlid == null) {
            var matches = bookRepository.findByTitleAndAuthorIgnoreCase(title, author);
            if (!matches.isEmpty()) {
                // Prefer the verified book (has workOlid), fall back to first
                return matches.stream().filter(b -> b.getWorkOlid() != null).findFirst().orElse(matches.get(0));
            }
        }

        // No match — create new Book
        return bookRepository.save(new Book(workOlid, coverEditionOlid, title, author, firstPublishYear));
    }

    /**
     * Searches local database for books matching all words in the query.
     * Only returns verified books (those with a workOlid).
     */
    public List<OpenLibraryService.BookResult> searchLocalBooks(String query) {
        String[] words = query.trim().split("\\s+");
        List<Book> candidates = null;
        for (String word : words) {
            if (word.isEmpty()) continue;
            List<Book> matches = bookRepository.searchByTitleOrAuthor(word);
            if (candidates == null) {
                candidates = new ArrayList<>(matches);
            } else {
                Set<Long> matchIds = matches.stream().map(Book::getId).collect(Collectors.toSet());
                candidates.removeIf(b -> !matchIds.contains(b.getId()));
            }
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .map(b -> new OpenLibraryService.BookResult(b.getWorkOlid(), b.getCoverEditionOlid(), b.getTitle(), b.getAuthor(), b.getFirstPublishYear()))
            .toList();
    }
}
