package armchair.entity;

import java.io.Serializable;

public class RankingState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final BookIdentity bookIdentity = new BookIdentity();
    private final EditionSelection editionSelection = new EditionSelection();
    private final BinarySearchState binarySearch = new BinarySearchState();
    private final RestorationState restoration = new RestorationState();

    private String reviewBeingRanked;
    private Bookshelf bookshelf;
    private BookCategory category;
    private boolean rankAll;
    private boolean wantToRead;
    private Long bookIdBeingReviewed;
    private RankingMode mode;
    private Long unrankedRankingId;

    public RankingState() {}

    public RankingState(String workOlidBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        Bookshelf bookshelf, BookCategory category) {
        this(workOlidBeingRanked, titleBeingRanked, authorBeingRanked, bookshelf, category, 0, 0, 0);
    }

    public RankingState(String workOlidBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        Bookshelf bookshelf, BookCategory category, Integer compareToIndex, Integer lowIndex,
                        Integer highIndex) {
        bookIdentity.setBookInfo(workOlidBeingRanked, titleBeingRanked, authorBeingRanked);
        this.bookshelf = bookshelf;
        this.category = category;
        binarySearch.setCompareToIndex(compareToIndex);
        binarySearch.setLowIndex(lowIndex);
        binarySearch.setHighIndex(highIndex);
    }

    public BookIdentity getBookIdentity() {
        return bookIdentity;
    }

    public EditionSelection getEditionSelection() {
        return editionSelection;
    }

    public BinarySearchState getBinarySearch() {
        return binarySearch;
    }

    public RestorationState getRestoration() {
        return restoration;
    }

    // --- Fields staying on RankingState ---

    public String getReviewBeingRanked() {
        return reviewBeingRanked;
    }

    public void setReviewBeingRanked(String reviewBeingRanked) {
        this.reviewBeingRanked = reviewBeingRanked;
    }

    public Bookshelf getBookshelf() {
        return bookshelf;
    }

    public void setBookshelf(Bookshelf bookshelf) {
        this.bookshelf = bookshelf;
    }

    public BookCategory getCategory() {
        return category;
    }

    public void setCategory(BookCategory category) {
        this.category = category;
    }

    public boolean isRankAll() {
        return rankAll;
    }

    public void setRankAll(boolean rankAll) {
        this.rankAll = rankAll;
    }

    public boolean isWantToRead() {
        return wantToRead;
    }

    public void setWantToRead(boolean wantToRead) {
        this.wantToRead = wantToRead;
    }

    public Long getBookIdBeingReviewed() {
        return bookIdBeingReviewed;
    }

    public void setBookIdBeingReviewed(Long bookIdBeingReviewed) {
        this.bookIdBeingReviewed = bookIdBeingReviewed;
    }

    public RankingMode getMode() {
        return mode;
    }

    public void setMode(RankingMode mode) {
        this.mode = mode;
    }

    public Long getUnrankedRankingId() {
        return unrankedRankingId;
    }

    public void setUnrankedRankingId(Long unrankedRankingId) {
        this.unrankedRankingId = unrankedRankingId;
    }
}
