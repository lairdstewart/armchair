package armchair.recommendation;

import java.util.List;

public interface RecommendationAlgorithm {
    List<ScoredBook> getFictionRecommendations(Long userId, int limit);
    List<ScoredBook> getNonfictionRecommendations(Long userId, int limit);
}
