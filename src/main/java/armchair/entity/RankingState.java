package armchair.entity;

import java.io.Serializable;

public class RankingState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String workOlidBeingRanked;
    private String editionOlidBeingRanked;
    private String isbn13BeingRanked;
    private boolean editionSelected;
    private String titleBeingRanked;
    private String authorBeingRanked;
    private String reviewBeingRanked;

    private Bookshelf bookshelf;
    private BookCategory category;

    private Integer compareToIndex;
    private Integer lowIndex;
    private Integer highIndex;

    private boolean rankAll;
    private boolean wantToRead;
    private Long bookIdBeingReviewed;

    private RankingMode mode;

    // For re-rank restoration: store original position so abandoned re-ranks can be restored
    private BookCategory originalCategory;
    private Integer originalPosition;

    // ID of the UNRANKED ranking row to delete atomically when ranking completes
    private Long unrankedRankingId;

    // Pre-resolve book state so /back-to-resolve can undo mutations
    private String originalResolveTitle;
    private String originalResolveAuthor;
    private String originalResolveWorkOlid;
    private String originalResolveEditionOlid;

    public RankingState() {}

    public RankingState(String workOlidBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        Bookshelf bookshelf, BookCategory category) {
        this(workOlidBeingRanked, titleBeingRanked, authorBeingRanked, bookshelf, category, 0, 0, 0);
    }

    public RankingState(String workOlidBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        Bookshelf bookshelf, BookCategory category, Integer compareToIndex, Integer lowIndex,
                        Integer highIndex) {
        this.workOlidBeingRanked = workOlidBeingRanked;
        this.titleBeingRanked = titleBeingRanked;
        this.authorBeingRanked = authorBeingRanked;
        this.bookshelf = bookshelf;
        this.category = category;
        this.compareToIndex = compareToIndex;
        this.lowIndex = lowIndex;
        this.highIndex = highIndex;
    }

    public String getWorkOlidBeingRanked() {
        return workOlidBeingRanked;
    }

    public void setWorkOlidBeingRanked(String workOlidBeingRanked) {
        this.workOlidBeingRanked = workOlidBeingRanked;
    }

    public String getTitleBeingRanked() {
        return titleBeingRanked;
    }

    public void setTitleBeingRanked(String titleBeingRanked) {
        this.titleBeingRanked = titleBeingRanked;
    }

    public String getAuthorBeingRanked() {
        return authorBeingRanked;
    }

    public void setAuthorBeingRanked(String authorBeingRanked) {
        this.authorBeingRanked = authorBeingRanked;
    }

    public void setBookInfo(String workOlid, String title, String author) {
        this.workOlidBeingRanked = workOlid;
        this.titleBeingRanked = title;
        this.authorBeingRanked = author;
    }

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

    public Integer getCompareToIndex() {
        return compareToIndex;
    }

    public void setCompareToIndex(Integer compareToIndex) {
        this.compareToIndex = compareToIndex;
    }

    public Integer getLowIndex() {
        return lowIndex;
    }

    public void setLowIndex(Integer lowIndex) {
        this.lowIndex = lowIndex;
    }

    public Integer getHighIndex() {
        return highIndex;
    }

    public void setHighIndex(Integer highIndex) {
        this.highIndex = highIndex;
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

    public String getEditionOlidBeingRanked() {
        return editionOlidBeingRanked;
    }

    public void setEditionOlidBeingRanked(String editionOlidBeingRanked) {
        this.editionOlidBeingRanked = editionOlidBeingRanked;
    }

    public String getIsbn13BeingRanked() {
        return isbn13BeingRanked;
    }

    public void setIsbn13BeingRanked(String isbn13BeingRanked) {
        this.isbn13BeingRanked = isbn13BeingRanked;
    }

    public boolean isEditionSelected() {
        return editionSelected;
    }

    public void setEditionSelected(boolean editionSelected) {
        this.editionSelected = editionSelected;
    }

    public BookCategory getOriginalCategory() {
        return originalCategory;
    }

    public void setOriginalCategory(BookCategory originalCategory) {
        this.originalCategory = originalCategory;
    }

    public Integer getOriginalPosition() {
        return originalPosition;
    }

    public void setOriginalPosition(Integer originalPosition) {
        this.originalPosition = originalPosition;
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

    public String getOriginalResolveTitle() {
        return originalResolveTitle;
    }

    public void setOriginalResolveTitle(String originalResolveTitle) {
        this.originalResolveTitle = originalResolveTitle;
    }

    public String getOriginalResolveAuthor() {
        return originalResolveAuthor;
    }

    public void setOriginalResolveAuthor(String originalResolveAuthor) {
        this.originalResolveAuthor = originalResolveAuthor;
    }

    public String getOriginalResolveWorkOlid() {
        return originalResolveWorkOlid;
    }

    public void setOriginalResolveWorkOlid(String originalResolveWorkOlid) {
        this.originalResolveWorkOlid = originalResolveWorkOlid;
    }

    public String getOriginalResolveEditionOlid() {
        return originalResolveEditionOlid;
    }

    public void setOriginalResolveEditionOlid(String originalResolveEditionOlid) {
        this.originalResolveEditionOlid = originalResolveEditionOlid;
    }
}
