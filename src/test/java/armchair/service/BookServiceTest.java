package armchair.service;

import armchair.entity.Book;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookServiceTest {

    private BookService service;
    private BookRepository bookRepository;
    private RankingRepository rankingRepository;

    @BeforeEach
    void setUp() {
        service = new BookService();
        bookRepository = mock(BookRepository.class);
        rankingRepository = mock(RankingRepository.class);
        ReflectionTestUtils.setField(service, "bookRepository", bookRepository);
        ReflectionTestUtils.setField(service, "rankingRepository", rankingRepository);
    }

    @Nested
    class FindOrCreateBook {

        @Test
        void findsExistingBookByWorkOlid() {
            Book existing = new Book("OL1W", "OL1M", "Dune", "Frank Herbert", 1965, 789);
            existing.setId(1L);
            when(bookRepository.findByWorkOlid("OL1W")).thenReturn(Optional.of(existing));

            Book result = service.findOrCreateBook("OL1W", "OL2M", "Dune", "Frank Herbert", 1965, 999);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getWorkOlid()).isEqualTo("OL1W");
        }

        @Test
        void enrichesExistingBookWithMissingEditionOlid() {
            Book existing = new Book("OL1W", null, "Dune", "Frank Herbert", null, null);
            existing.setId(1L);
            when(bookRepository.findByWorkOlid("OL1W")).thenReturn(Optional.of(existing));

            Book result = service.findOrCreateBook("OL1W", "OL2M", "Dune", "Frank Herbert", null, null);

            assertThat(result.getEditionOlid()).isEqualTo("OL2M");
            verify(bookRepository).save(existing);
        }

        @Test
        void enrichesExistingBookWithMissingCoverId() {
            Book existing = new Book("OL1W", "OL1M", "Dune", "Frank Herbert", null, null);
            existing.setId(1L);
            when(bookRepository.findByWorkOlid("OL1W")).thenReturn(Optional.of(existing));

            Book result = service.findOrCreateBook("OL1W", null, "Dune", "Frank Herbert", null, 999);

            assertThat(result.getCoverId()).isEqualTo(999);
            verify(bookRepository).save(existing);
        }

        @Test
        void doesNotOverwriteExistingEditionOlid() {
            Book existing = new Book("OL1W", "OL1M", "Dune", "Frank Herbert", null, null);
            existing.setId(1L);
            when(bookRepository.findByWorkOlid("OL1W")).thenReturn(Optional.of(existing));

            Book result = service.findOrCreateBook("OL1W", "OL2M", "Dune", "Frank Herbert", null, null);

            assertThat(result.getEditionOlid()).isEqualTo("OL1M");
            verify(bookRepository, never()).save(any());
        }

        @Test
        void findsUnverifiedBookByTitleAndAuthor() {
            Book existing = new Book(null, null, "Dune", "Frank Herbert", null, null);
            existing.setId(1L);
            when(bookRepository.findByTitleAndAuthorIgnoreCase("Dune", "Frank Herbert"))
                .thenReturn(List.of(existing));

            Book result = service.findOrCreateBook(null, null, "Dune", "Frank Herbert", null, null);

            assertThat(result.getId()).isEqualTo(1L);
        }

        @Test
        void prefersVerifiedBookWhenMultipleMatchByTitleAuthor() {
            Book unverified = new Book(null, null, "Dune", "Frank Herbert", null, null);
            unverified.setId(1L);
            Book verified = new Book("OL1W", null, "Dune", "Frank Herbert", null, null);
            verified.setId(2L);
            when(bookRepository.findByTitleAndAuthorIgnoreCase("Dune", "Frank Herbert"))
                .thenReturn(List.of(unverified, verified));

            Book result = service.findOrCreateBook(null, null, "Dune", "Frank Herbert", null, null);

            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        void createsNewBookWhenNoMatch() {
            when(bookRepository.findByWorkOlid("OL1W")).thenReturn(Optional.empty());
            Book saved = new Book("OL1W", "OL1M", "Dune", "Frank Herbert", 1965, 789);
            saved.setId(1L);
            when(bookRepository.save(any(Book.class))).thenReturn(saved);

            Book result = service.findOrCreateBook("OL1W", "OL1M", "Dune", "Frank Herbert", 1965, 789);

            assertThat(result.getId()).isEqualTo(1L);
            verify(bookRepository).save(any(Book.class));
        }

        @Test
        void handlesRaceConditionOnDuplicateWorkOlid() {
            when(bookRepository.findByWorkOlid("OL1W"))
                .thenReturn(Optional.empty())                               // first lookup
                .thenReturn(Optional.of(new Book("OL1W", null, "Dune", "Frank Herbert", null, null))); // retry
            when(bookRepository.save(any(Book.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

            Book result = service.findOrCreateBook("OL1W", null, "Dune", "Frank Herbert", null, null);

            assertThat(result.getWorkOlid()).isEqualTo("OL1W");
        }

        @Test
        void rethrowsDataIntegrityViolationForUnverifiedBook() {
            when(bookRepository.findByTitleAndAuthorIgnoreCase("Dune", "Frank Herbert"))
                .thenReturn(List.of());
            when(bookRepository.save(any(Book.class)))
                .thenThrow(new DataIntegrityViolationException("other constraint"));

            assertThatThrownBy(() -> service.findOrCreateBook(null, null, "Dune", "Frank Herbert", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void throwsWhenRaceConditionRetryFindsNothing() {
            when(bookRepository.findByWorkOlid("OL1W")).thenReturn(Optional.empty());
            when(bookRepository.save(any(Book.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

            assertThatThrownBy(() -> service.findOrCreateBook("OL1W", null, "Dune", "Frank Herbert", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate workOlid");
        }
    }

    @Nested
    class DeleteIfOrphaned {

        @Test
        void deletesBookWithNoRankings() {
            when(rankingRepository.existsByBookId(1L)).thenReturn(false);

            service.deleteIfOrphaned(1L);

            verify(bookRepository).deleteById(1L);
        }

        @Test
        void keepsBookWithRankings() {
            when(rankingRepository.existsByBookId(1L)).thenReturn(true);

            service.deleteIfOrphaned(1L);

            verify(bookRepository, never()).deleteById(any());
        }
    }
}
