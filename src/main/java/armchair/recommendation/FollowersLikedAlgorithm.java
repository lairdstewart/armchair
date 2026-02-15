package armchair.recommendation;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default recommendation algorithm. Recommends books that users you follow
 * have ranked as LIKED, excluding books you already have in your library.
 * Falls back to random books if no social recommendations are available.
 */
@Component
@Primary
public class FollowersLikedAlgorithm implements RecommendationAlgorithm {

    private final RankingRepository rankingRepository;
    private final FollowRepository followRepository;
    private final BookRepository bookRepository;

    public FollowersLikedAlgorithm(RankingRepository rankingRepository,
                                   FollowRepository followRepository,
                                   BookRepository bookRepository) {
        this.rankingRepository = rankingRepository;
        this.followRepository = followRepository;
        this.bookRepository = bookRepository;
    }

    @Override
    public List<Book> getRecommendations(Long userId, int limit) {
        // Get IDs of users this person follows
        List<Long> followedUserIds = followRepository.findByFollowerId(userId).stream()
                .map(Follow::getFollowedId)
                .toList();

        if (!followedUserIds.isEmpty()) {
            // Get book IDs the user already has
            Set<Long> ownBookIds = rankingRepository.findByUserId(userId).stream()
                    .map(r -> r.getBook().getId())
                    .collect(Collectors.toSet());

            // Find LIKED books from followed users, excluding user's own books
            List<Book> socialRecs = followedUserIds.stream()
                    .flatMap(fid -> rankingRepository.findByUserId(fid).stream())
                    .filter(r -> r.getCategory() == BookCategory.LIKED)
                    .filter(r -> r.getBookshelf() == Bookshelf.FICTION || r.getBookshelf() == Bookshelf.NONFICTION)
                    .map(Ranking::getBook)
                    .filter(book -> !ownBookIds.contains(book.getId()))
                    .distinct()
                    .limit(limit)
                    .toList();

            if (!socialRecs.isEmpty()) {
                return socialRecs;
            }
        }

        // Fallback: random books
        return bookRepository.findRandomBooks().stream()
                .limit(limit)
                .toList();
    }
}
