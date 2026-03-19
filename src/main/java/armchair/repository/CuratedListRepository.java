package armchair.repository;

import armchair.entity.CuratedList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CuratedListRepository extends JpaRepository<CuratedList, Long> {
    Optional<CuratedList> findByUsername(String username);

    @Query("SELECT c FROM CuratedList c WHERE LOWER(c.username) LIKE LOWER(CONCAT('%', :username, '%'))")
    List<CuratedList> searchByUsername(@Param("username") String username);
}
