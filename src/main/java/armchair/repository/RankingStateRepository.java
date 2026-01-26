package armchair.repository;

import armchair.entity.RankingState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RankingStateRepository extends JpaRepository<RankingState, Long> {
}
