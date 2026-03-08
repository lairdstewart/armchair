package armchair.service;

import armchair.entity.Book;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BookService {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private RankingRepository rankingRepository;

    /**
     * Finds an existing book or creates a new one. Deduplicates by workOlid first,
     * then falls back to case-insensitive title+author match for unverified books.
     */
    @Transactional
    public Book findOrCreateBook(String workOlid, String editionOlid, String title, String author, Integer firstPublishYear, Integer coverId) {
        // Look up by workOlid
        if (workOlid != null) {
            var existing = bookRepository.findByWorkOlid(workOlid);
            if (existing.isPresent()) {
                Book book = existing.get();
                boolean updated = false;
                // Enrich with editionOlid if missing
                if (book.getEditionOlid() == null && editionOlid != null) {
                    book.setEditionOlid(editionOlid);
                    updated = true;
                }
                // Enrich with coverId if missing
                if (book.getCoverId() == null && coverId != null) {
                    book.setCoverId(coverId);
                    updated = true;
                }
                if (updated) {
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

        // No match — create new Book. If a concurrent insert wins the race,
        // the unique constraint on workOlid catches it and we retry the lookup.
        try {
            return bookRepository.save(new Book(workOlid, editionOlid, title, author, firstPublishYear, coverId));
        } catch (DataIntegrityViolationException e) {
            if (workOlid != null) {
                return bookRepository.findByWorkOlid(workOlid)
                        .orElseThrow(() -> new IllegalStateException(
                                "Duplicate workOlid constraint violated but book not found: " + workOlid, e));
            }
            throw e;
        }
    }

    public void deleteIfOrphaned(Long bookId) {
        if (!rankingRepository.existsByBookId(bookId)) {
            bookRepository.deleteById(bookId);
        }
    }
}
