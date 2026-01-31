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

    private String googleBooksIdBeingRanked;
    @Column(length = 1000)
    private String titleBeingRanked;
    @Column(length = 1000)
    private String authorBeingRanked;
    private String isbn13BeingRanked;
    @Column(length = 5000)
    private String reviewBeingRanked;

    @Enumerated(EnumType.STRING)
    private BookType type;

    @Enumerated(EnumType.STRING)
    private BookCategory category;

    private Integer compareToIndex;
    private Integer lowIndex;
    private Integer highIndex;

    private boolean reRank;
    private boolean remove;
    private boolean review;
    private Long bookIdBeingReviewed;

    public RankingState() {}

    public RankingState(Long userId, String googleBooksIdBeingRanked, String titleBeingRanked, String authorBeingRanked,
                        BookType type, BookCategory category, Integer compareToIndex, Integer lowIndex,
                        Integer highIndex) {
        this.userId = userId;
        this.googleBooksIdBeingRanked = googleBooksIdBeingRanked;
        this.titleBeingRanked = titleBeingRanked;
        this.authorBeingRanked = authorBeingRanked;
        this.type = type;
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

    public String getGoogleBooksIdBeingRanked() {
        return googleBooksIdBeingRanked;
    }

    public void setGoogleBooksIdBeingRanked(String googleBooksIdBeingRanked) {
        this.googleBooksIdBeingRanked = googleBooksIdBeingRanked;
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

    public String getIsbn13BeingRanked() {
        return isbn13BeingRanked;
    }

    public void setIsbn13BeingRanked(String isbn13BeingRanked) {
        this.isbn13BeingRanked = isbn13BeingRanked;
    }

    public String getReviewBeingRanked() {
        return reviewBeingRanked;
    }

    public void setReviewBeingRanked(String reviewBeingRanked) {
        this.reviewBeingRanked = reviewBeingRanked;
    }

    public BookType getType() {
        return type;
    }

    public void setType(BookType type) {
        this.type = type;
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

    public boolean isReRank() {
        return reRank;
    }

    public void setReRank(boolean reRank) {
        this.reRank = reRank;
    }

    public boolean isRemove() {
        return remove;
    }

    public void setRemove(boolean remove) {
        this.remove = remove;
    }

    public boolean isReview() {
        return review;
    }

    public void setReview(boolean review) {
        this.review = review;
    }

    public Long getBookIdBeingReviewed() {
        return bookIdBeingReviewed;
    }

    public void setBookIdBeingReviewed(Long bookIdBeingReviewed) {
        this.bookIdBeingReviewed = bookIdBeingReviewed;
    }
}
