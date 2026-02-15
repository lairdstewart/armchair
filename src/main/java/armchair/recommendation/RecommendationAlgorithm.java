package armchair.recommendation;

import armchair.entity.Book;

import java.util.List;

public interface RecommendationAlgorithm {
    List<Book> getRecommendations(Long userId, int limit);
}
