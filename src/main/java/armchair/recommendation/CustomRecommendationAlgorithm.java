package armchair.recommendation;

import armchair.entity.Book;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stub for a custom recommendation algorithm. To activate this instead of
 * {@link FollowersLikedAlgorithm}, move the {@code @Primary} annotation
 * from that class to this one.
 *
 * <p>Useful repository methods:
 * <ul>
 *   <li>{@code rankingRepository.findByUserId(userId)} — all of a user's rankings</li>
 *   <li>{@code rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(userId, bookshelf)} — ranked list</li>
 *   <li>{@code followRepository.findByFollowerId(userId)} — who the user follows</li>
 *   <li>{@code followRepository.findByFollowedId(userId)} — who follows the user</li>
 *   <li>{@code bookRepository.findRandomBooks()} — random books with covers</li>
 * </ul>
 */
@Component
public class CustomRecommendationAlgorithm implements RecommendationAlgorithm {

    private final RankingRepository rankingRepository;
    private final FollowRepository followRepository;
    private final BookRepository bookRepository;

    public CustomRecommendationAlgorithm(RankingRepository rankingRepository,
                                         FollowRepository followRepository,
                                         BookRepository bookRepository) {
        this.rankingRepository = rankingRepository;
        this.followRepository = followRepository;
        this.bookRepository = bookRepository;
    }

    @Override
    public List<Book> getRecommendations(Long userId, int limit) {
        // TODO: Implement your custom recommendation logic here.
        return List.of();
    }
}
