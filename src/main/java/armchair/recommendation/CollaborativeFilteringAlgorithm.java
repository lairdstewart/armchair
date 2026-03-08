package armchair.recommendation;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Ranking;
import armchair.entity.User;
import armchair.repository.BookRepository;
import armchair.repository.RankingRepository;
import armchair.repository.UserRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collaborative filtering recommendation algorithm. Computes taste similarity
 * via cosine similarity on overlapping book scores, then produces weighted
 * recommendations. See docs/collaborative-filtering-algorithm.md for details.
 */
@Component
@Primary
public class CollaborativeFilteringAlgorithm implements RecommendationAlgorithm {

    private static final int CONFIDENCE_THRESHOLD = 5;

    private final RankingRepository rankingRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;

    public CollaborativeFilteringAlgorithm(RankingRepository rankingRepository,
                                           BookRepository bookRepository,
                                           UserRepository userRepository) {
        this.rankingRepository = rankingRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
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
        // Build curated user ID set for score computation
        Set<Long> curatedUserIds = userRepository.findByIsCurated(true).stream()
                .map(User::getId)
                .collect(Collectors.toSet());

        // Get current user's scores for this bookshelf
        List<Ranking> myRankings = rankingRepository.findByUserIdAndBookshelfOrderByPositionAsc(userId, bookshelf);
        Map<Long, Double> myScores = computeUserScores(myRankings, curatedUserIds.contains(userId));

        // Book IDs the current user already has (across all bookshelves)
        Set<Long> ownBookIds = new HashSet<>(rankingRepository.findBookIdsByUserId(userId));

        // Batch-fetch all rankings for this bookshelf, grouped by user
        List<Ranking> allRankings = rankingRepository.findByBookshelfOrderByUserIdAscPositionAsc(bookshelf);
        Map<Long, List<Ranking>> rankingsByUser = allRankings.stream()
                .collect(Collectors.groupingBy(Ranking::getUserId));

        // Check if we have any overlap with anyone
        boolean hasAnyOverlap = false;

        // Compute similarities and collect candidate scores
        // candidateScores: bookId -> list of (similarity, score) pairs
        Map<Long, List<double[]>> candidateData = new HashMap<>();
        // Track books for later lookup
        Map<Long, Book> bookMap = new HashMap<>();

        for (Map.Entry<Long, List<Ranking>> entry : rankingsByUser.entrySet()) {
            Long otherUserId = entry.getKey();
            if (otherUserId.equals(userId)) continue;

            List<Ranking> otherRankings = entry.getValue();
            if (otherRankings.isEmpty()) continue;

            Map<Long, Double> otherScores = computeUserScores(otherRankings, curatedUserIds.contains(otherUserId));

            double similarity = computeSimilarity(myScores, otherScores);
            if (similarity != 0.0) {
                hasAnyOverlap = true;
            }

            // Collect candidate books from this user
            for (Ranking r : otherRankings) {
                Long bookId = r.getBook().getId();
                if (ownBookIds.contains(bookId)) continue;

                bookMap.putIfAbsent(bookId, r.getBook());
                candidateData.computeIfAbsent(bookId, k -> new ArrayList<>())
                        .add(new double[]{similarity, otherScores.get(bookId)});
            }
        }

        // No-overlap fallback: treat all similarities as 1.0
        if (!hasAnyOverlap && !candidateData.isEmpty()) {
            for (List<double[]> entries : candidateData.values()) {
                for (double[] entry : entries) {
                    entry[0] = 1.0;
                }
            }
        }

        // Score candidates: weighted average
        List<Map.Entry<Long, Double>> scoredCandidates = new ArrayList<>();
        for (Map.Entry<Long, List<double[]>> entry : candidateData.entrySet()) {
            List<double[]> pairs = entry.getValue();
            double weightedSum = 0;
            for (double[] pair : pairs) {
                weightedSum += pair[0] * pair[1];
            }
            double recScore = weightedSum / pairs.size();
            scoredCandidates.add(Map.entry(entry.getKey(), recScore));
        }

        // Sort descending by score
        scoredCandidates.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Book> result = scoredCandidates.stream()
                .limit(limit)
                .map(e -> bookMap.get(e.getKey()))
                .toList();

        if (result.isEmpty()) {
            return bookRepository.findRandomBooks().stream()
                    .limit(limit)
                    .toList();
        }

        return result;
    }

    /**
     * Compute scores for each book in a user's ranked list on a single bookshelf.
     * Rankings are ordered by position ascending (position 0 = best).
     * The algorithm wants i=0 as worst, so we reverse: i = (N-1) - idx.
     */
    Map<Long, Double> computeUserScores(List<Ranking> rankings, boolean isCurated) {
        Map<Long, Double> scores = new HashMap<>();

        if (isCurated) {
            for (Ranking r : rankings) {
                scores.put(r.getBook().getId(), 0.75);
            }
            return scores;
        }

        // Group by category, preserving position order
        Map<BookCategory, List<Ranking>> byCategory = new HashMap<>();
        for (Ranking r : rankings) {
            if (r.getCategory() == BookCategory.UNRANKED) continue;
            byCategory.computeIfAbsent(r.getCategory(), k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<BookCategory, List<Ranking>> entry : byCategory.entrySet()) {
            BookCategory category = entry.getKey();
            List<Ranking> catRankings = entry.getValue();
            int n = catRankings.size();

            for (int idx = 0; idx < n; idx++) {
                // position 0 = best, so reverse: i = (N-1) - idx means last item (worst) gets i=0
                int i = (n - 1) - idx;
                double score = computeScore(category, i, n);
                scores.put(catRankings.get(idx).getBook().getId(), score);
            }
        }

        return scores;
    }

    private double computeScore(BookCategory category, int i, int n) {
        return switch (category) {
            case DISLIKED -> n == 1 ? -0.75 : -1.0 + 0.5 * i / (n - 1);
            case OK -> n == 1 ? 0.0 : -0.5 + 1.0 * i / (n - 1);
            case LIKED -> n == 1 ? 0.75 : 0.5 + 0.5 * i / (n - 1);
            default -> 0.0;
        };
    }

    /**
     * Compute effective similarity between two users based on overlapping book scores.
     * Returns cosine similarity * confidence discount (threshold T=5).
     */
    double computeSimilarity(Map<Long, Double> myScores, Map<Long, Double> otherScores) {
        List<Long> overlap = myScores.keySet().stream()
                .filter(otherScores::containsKey)
                .toList();

        if (overlap.isEmpty()) return 0.0;

        double dot = 0, normA = 0, normB = 0;
        for (Long bookId : overlap) {
            double a = myScores.get(bookId);
            double b = otherScores.get(bookId);
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA == 0 || normB == 0) return 0.0;

        double cosineSim = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        cosineSim = Math.max(-1.0, Math.min(1.0, cosineSim));

        double confidence = Math.min(overlap.size(), CONFIDENCE_THRESHOLD) / (double) CONFIDENCE_THRESHOLD;
        return cosineSim * confidence;
    }
}
