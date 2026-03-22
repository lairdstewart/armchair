# Collaborative Filtering Recommendation Algorithm

## Overview

Recommend books by comparing the current user's ranked lists against all other
users'. Users with similar taste (measured by how they rank overlapping books)
have more influence on recommendations.

Fiction and nonfiction are computed independently — a user's fiction taste may
differ from their nonfiction taste. Only ranked books (LIKED, OK, or DISLIKED)
are used; want-to-read books are excluded.

## Step 1: Score each book in each user's ranked list

Each book has a category (LIKED, OK, DISLIKED) and a position within that
category's ranked list. Scores are assigned via piecewise linear interpolation
within fixed bands:

| Category | Score range   | Midpoint |
|----------|---------------|----------|
| LIKED    | [0.5, 1.0]   | 0.75     |
| OK       | [-0.5, 0.5]  | 0.0      |
| DISLIKED | [-1.0, -0.5] | -0.75    |

Within each category, books are spread across the band by ranked position (`i`
= 0 for worst in the category, counting up to best):

```
DISLIKED (D books):
  D == 1: score = -0.75
  D >= 2: score = -1.0 + 0.5 * i / (D - 1)       -> [-1.0, -0.5]

OK (O books):
  O == 1: score = 0.0
  O >= 2: score = -0.5 + 1.0 * i / (O - 1)        -> [-0.5, 0.5]

LIKED (L books):
  L == 1: score = 0.75
  L >= 2: score = 0.5 + 0.5 * i / (L - 1)         -> [0.5, 1.0]
```

The categories create the non-linearity — LIKED and DISLIKED each span only 0.5
over their books while OK spans 1.0, so strong opinions are more compressed
(small positional differences matter more at the extremes). No sigmoid needed.

**Curated lists:** Curated lists act as synthetic users and are the primary
cold-start solution. When a curated list has rankings (position ordering), its
books are scored using position-based interpolation within the LIKED range
[0.5, 1.0] — the same formula as user-ranked LIKED books. Single-book lists
fall back to 0.75 (the LIKED midpoint).

## Step 2: Compute similarity between users

For the current user and each other user, find books they have in common (within
the same bookshelf). Extract each user's score for those overlapping books to
form two vectors, then compute **cosine similarity**:

```
similarity = dot(v1, v2) / (||v1|| * ||v2||)
```

Returns -1 (opposite taste) to +1 (identical taste).

### Confidence discount

Small overlaps produce unreliable similarity estimates. Discount using threshold
T = 5:

```
effective_similarity = cosine_similarity * min(overlap_count, 5) / 5
```

If two users share only 1 book, the similarity is scaled to 1/5 of its raw
value. Zero overlapping books means similarity is 0.

**No overlap:** If the current user has no overlapping books with any other user
on a bookshelf (either because they have no ranked books or because no one else
has read the same books), treat their similarity with every other user as 1.0
(no discount). This makes the algorithm degrade gracefully into average-score
recommendations.

### Example

```
tom's fiction list:
  DISLIKED: [A, B]         -> A=-1.0, B=-0.5
  OK:       [C, D, E]      -> C=-0.5, D=0.0, E=0.5
  LIKED:    [F, G, H, I]   -> F=0.5, G=0.67, H=0.83, I=1.0

jerry's fiction list:
  DISLIKED: [C]             -> C=-0.75
  OK:       [B, X]          -> B=-0.5, X=0.5
  LIKED:    [Z, E, Y]       -> Z=0.5, E=0.75, Y=1.0

Overlapping books: [B, C, E]
tom's   vector: [-0.5, -0.5,  0.5]
jerry's vector: [-0.5, -0.75, 0.75]

cosine_similarity = (0.25 + 0.375 + 0.375) / (0.866 * 1.089)
                  = 1.0 / 0.943
                  = 1.06 -> clamped to 1.0

effective_similarity = 1.0 * 3/5 = 0.6
```

Tom and Jerry agree on all 3 overlapping books, but the similarity is discounted
to 0.6 because they only share 3 books.

## Step 3: Score candidate books

For each book `b` that the current user does NOT have in their library, compute
a weighted average across all users who have it, using similarity as the weight:

```
rec_score(b) = SUM(effective_similarity(me, u) * score(b, u)) / SUM(|effective_similarity(me, u)|)
```

The denominator is the sum of absolute similarities rather than the raw user
count. This matters because zero similarity means "unknown taste" (no
overlapping books), not "dissimilar." Dividing by user count would let unknown
users inflate the denominator and penalize books that happen to appear on their
lists. Using similarity as the weight ensures only users with actual taste signal
affect the score.

In the no-overlap fallback (all similarities = 1.0), this produces the same
result as dividing by user count, since each user contributes weight 1.0.

If all similarities for a candidate are exactly zero, the book scores 0.

## Step 4: Return top N

Sort candidate books by `rec_score` descending and return the top N. Negative-
scored books naturally fall to the bottom.
