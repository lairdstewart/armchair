package armchair.recommendation;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.Ranking;
import armchair.repository.BookRepository;
import armchair.repository.FollowRepository;
import armchair.repository.RankingRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
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
    public List<Book> getFictionRecommendations(Long userId, int limit) {
        return getRecommendationsForBookshelf(userId, Bookshelf.FICTION, limit);
    }

    @Override
    public List<Book> getNonfictionRecommendations(Long userId, int limit) {
        return getRecommendationsForBookshelf(userId, Bookshelf.NONFICTION, limit);
    }

    private List<Book> getRecommendationsForBookshelf(Long userId, Bookshelf bookshelf, int limit) {
        List<Long> followedUserIds = followRepository.findByFollowerId(userId).stream()
                .map(Follow::getFollowedId)
                .toList();

        if (!followedUserIds.isEmpty()) {
            Set<Long> ownBookIds = rankingRepository.findByUserId(userId).stream()
                    .map(r -> r.getBook().getId())
                    .collect(Collectors.toSet());

            List<Book> socialRecs = followedUserIds.stream()
                    .flatMap(fid -> rankingRepository.findByUserId(fid).stream())
                    .filter(r -> r.getCategory() == BookCategory.LIKED)
                    .filter(r -> r.getBookshelf() == bookshelf)
                    .map(Ranking::getBook)
                    .filter(book -> !ownBookIds.contains(book.getId()))
                    .distinct()
                    .limit(limit)
                    .toList();

            if (!socialRecs.isEmpty()) {
                return socialRecs;
            }
        }

        return bookRepository.findRandomBooks().stream()
                .limit(limit)
                .toList();
    }
}
