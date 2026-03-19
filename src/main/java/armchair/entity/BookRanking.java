package armchair.entity;

public interface BookRanking {
    Long getId();
    Book getBook();
    Bookshelf getBookshelf();
    BookCategory getCategory();
    Integer getPosition();
    String getReview();
}
