package armchair.repository;

import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RankingRepository extends JpaRepository<Ranking, Long> {
    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.user.id = :userId AND r.bookshelf = :bookshelf AND r.category = :category ORDER BY r.position ASC")
    List<Ranking> findByUserIdAndBookshelfAndCategoryOrderByPositionAsc(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf, @Param("category") BookCategory category);

    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.user.id = :userId AND r.bookshelf = :bookshelf ORDER BY r.position ASC")
    List<Ranking> findByUserIdAndBookshelfOrderByPositionAsc(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf);
    boolean existsByUserIdAndBookWorkOlid(Long userId, String workOlid);
    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.user.id = :userId AND r.book.workOlid = :workOlid")
    Ranking findByUserIdAndBookWorkOlid(@Param("userId") Long userId, @Param("workOlid") String workOlid);
    boolean existsByUserIdAndBookId(Long userId, Long bookId);
    boolean existsByBookId(Long bookId);
    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.user.id = :userId")
    List<Ranking> findByUserId(@Param("userId") Long userId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ranking r SET r.position = r.position - 1 WHERE r.user.id = :userId AND r.bookshelf = :bookshelf AND r.category = :category AND r.position > :removedPosition")
    void decrementPositionsAbove(@Param("userId") Long userId, @Param("bookshelf") Bookshelf bookshelf, @Param("category") BookCategory category, @Param("removedPosition") int removedPosition);

    long countByUserIdAndBookshelfAndCategoryIn(Long userId, Bookshelf bookshelf, List<BookCategory> categories);

    @Query("SELECT r.book.id FROM Ranking r WHERE r.user.id = :userId")
    List<Long> findBookIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.bookshelf = :bookshelf ORDER BY r.user.id ASC, r.position ASC")
    List<Ranking> findByBookshelfOrderByUserIdAscPositionAsc(@Param("bookshelf") Bookshelf bookshelf);

    @Query("SELECT r FROM Ranking r JOIN FETCH r.book WHERE r.user.id IN :userIds AND r.category = :category AND r.bookshelf = :bookshelf ORDER BY r.position ASC")
    List<Ranking> findByUserIdInAndCategoryAndBookshelfOrderByPositionAsc(@Param("userIds") List<Long> userIds, @Param("category") BookCategory category, @Param("bookshelf") Bookshelf bookshelf);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
