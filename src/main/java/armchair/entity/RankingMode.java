package armchair.entity;

/**
 * Explicit mode field for RankingState.
 *
 * Note: LIST is not a ranking mode - it's the absence of RankingState.
 * RE_RANK and REMOVE are LIST with action flags set.
 */
public enum RankingMode {
    RESOLVE,        // Unverified book needs Open Library match
    SELECT_EDITION, // User picks which edition/cover to use
    CATEGORIZE,     // User selects bookshelf + category
    RANK,           // Binary search pairwise comparisons
    REVIEW          // Editing a book's review
}
