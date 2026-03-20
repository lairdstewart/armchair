package armchair.entity;

/**
 * Explicit mode field for RankingState.
 * LIST is not a ranking mode - it's the absence of RankingState.
 */
public enum RankingMode {
    RESOLVE,        // Unverified book needs Open Library match
    CATEGORIZE,     // User selects bookshelf + category
    RANK,           // Binary search pairwise comparisons
    REVIEW,         // Editing a book's review
    RE_RANK,        // User selecting a book to re-rank
    REMOVE          // User selecting a book to remove
}
