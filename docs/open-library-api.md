# Open Library API Notes

This file documents findings and design decisions related to the Open Library API integration.

## API Endpoints Used

### Search API
`https://openlibrary.org/search.json?q=...`

- Supports `lang=en` to favor English results
- Supports `fields=` to specify returned data
- Good for finding works, returns one "best" edition per work
- Returns `cover_i` (cover image ID) when a cover exists
- Returns `cover_edition_key` (edition OLID) for the cover edition

### Editions API
`https://openlibrary.org/works/{workOlid}/editions.json`

- Supports `limit` and `offset` for pagination
- **NO sorting or filtering support** - returns editions in arbitrary database insertion order
- Used for "which edition did you read?" selection

### Covers API
Two URL patterns for cover images:
- By cover ID: `https://covers.openlibrary.org/b/id/{coverId}-M.jpg` (guaranteed to exist)
- By edition OLID: `https://covers.openlibrary.org/b/olid/{editionOlid}-M.jpg` (may return empty/transparent image)

Size suffixes: `-S` (small), `-M` (medium), `-L` (large)

## Cover ID vs Edition OLID

**Key insight:** Having an `editionOlid` does NOT guarantee a cover image exists.

- `cover_i` from the Search API is the actual cover image ID - if present, the cover definitely exists
- `cover_edition_key` is just an edition OLID - the edition may or may not have a cover
- When fetching by OLID, Open Library returns a 1x1 transparent pixel if no cover exists

**Implementation:** We store `coverId` (from `cover_i`) on the Book entity. When displaying covers:
1. If `coverId` exists, use `/b/id/{coverId}` URL (guaranteed to work)
2. Fall back to `/b/olid/{editionOlid}` for older books (may show nothing)
3. Show grey placeholder if neither exists

## Editions API Ordering Problem (Feb 2025)

### Problem

The Open Library editions API returns editions in database insertion order (when they were added to Open Library), NOT by relevance or popularity.

For example, "21 Lessons for the 21st Century" by Yuval Noah Harari has 32 editions:
- Row 1: Debolsillo 2022 (Spanish, no cover)
- Row 2-3: German editions
- Row 4-5: Catalan editions
- Row 10: First English edition (Spiegel & Grau)
- Row 24: Penguin Random House 2019 English edition

### API Investigation

Tested parameters on the editions API:
- `sort=new` - No effect, not supported
- `language=eng` - No effect, not supported

The editions API has NO server-side sorting or filtering capabilities.

### Solution Implemented

In `OpenLibraryService.getEditionsForWork()`:

1. Fetch more editions than requested (up to 50) to have a pool to sort from
2. Score each edition based on:
   - +1000 points: Matches the cover edition from search (preferredEditionOlid parameter)
   - +100 points: English language (languages field contains /languages/eng)
   - +50 points: ASCII-only title (suggests English vs translated title)
   - +25 points: Has cover image
3. Sort by score descending
4. Return the requested number of editions after sorting

The cover edition from the Search API is passed to `getEditionsForWork()` and gets a large score boost. This ensures the edition the user saw in search results appears first.

## Slow Cover Image Loading (Mar 2025)

Cover images sometimes load slowly. This is entirely a browser-side concern —
our server is not involved.

### How covers are loaded

Cover images are rendered as plain `<img>` tags pointing directly to
`covers.openlibrary.org`. The browser fetches them independently after the page
HTML is served. For example:

```html
<img src="https://covers.openlibrary.org/b/id/12345-M.jpg" loading="lazy" />
```

### What the server-side timeout does NOT control

`OpenLibraryService` has a 5s connect / 30s read timeout on its `RestTemplate`.
This timeout applies only to **API calls** made by the server (search, editions,
author lookups). It has **no effect** on cover image loading, which bypasses our
server entirely.

### Why we can't fix slow covers

Since the browser fetches covers directly from Open Library's CDN, the load
speed depends on Open Library's infrastructure. We have no control over it.

Possible mitigations (not currently worth the complexity):
- **Proxy and cache covers server-side** — adds storage, bandwidth, and
  cache-invalidation complexity for a third-party service issue
- **Use a smaller size suffix** (`-S` instead of `-M`) — reduces file size but
  degrades image quality

For now, we accept that covers may load slowly and rely on `loading="lazy"` to
avoid blocking initial page rendering.

## References

- Open Library API docs: https://openlibrary.org/developers/api
- Search API docs: https://openlibrary.org/dev/docs/api/search
