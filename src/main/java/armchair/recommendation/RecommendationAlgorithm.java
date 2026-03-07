package armchair.recommendation;

import armchair.entity.Book;

import java.util.List;

public interface RecommendationAlgorithm {
    List<Book> getFictionRecommendations(Long userId, int limit);
    List<Book> getNonfictionRecommendations(Long userId, int limit);
}
