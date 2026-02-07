package armchair.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ranking_state")
public class RankingState {
    @Id
    private Long userId;

    private String workOlidBeingRanked;
    private String editionOlidBeingRanked;
    private String isbn13BeingRanked;
    private boolean editionSelected;
    @Column(length = 1000)
    private String titleBeingRanked;
    @Column(length = 1000)
    private String authorBeingRanked;
    @Column(length = 5000)
    private String reviewBeingRanked;

    @Enumerated(EnumType.STRING)
    @Column(name = "bookshelf")
    private Bookshelf bookshelf;

    @Enumerated(EnumType.STRING)
    private BookCategory category;

    private Integer compareToIndex;
    private Integer lowIndex;
    private Integer highIndex;

    private boolean rankAll;
    private boolean wantToRead;
    private Long bookIdBeingReviewed;

    @Enumerated(EnumType.STRING)
    private RankingMode mode;

    // For re-rank restoration: store original position so abandoned re-ranks can be restored
    @Enumerated(EnumType.STRING)
    private BookCategory originalCategory;
    private Integer originalPosition;

    public RankingState() {}

    public RankingState(Long userId, String workOlidBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        Bookshelf bookshelf, BookCategory category) {
        this(userId, workOlidBeingRanked, titleBeingRanked, authorBeingRanked, bookshelf, category, 0, 0, 0);
    }

    public RankingState(Long userId, String workOlidBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        Bookshelf bookshelf, BookCategory category, Integer compareToIndex, Integer lowIndex,
                        Integer highIndex) {
        this.userId = userId;
        this.workOlidBeingRanked = workOlidBeingRanked;
        this.titleBeingRanked = titleBeingRanked;
        this.authorBeingRanked = authorBeingRanked;
        this.bookshelf = bookshelf;
        this.category = category;
        this.compareToIndex = compareToIndex;
        this.lowIndex = lowIndex;
        this.highIndex = highIndex;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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
}
