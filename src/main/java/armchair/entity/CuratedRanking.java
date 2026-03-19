package armchair.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "curated_rankings", uniqueConstraints = @UniqueConstraint(columnNames = {"curated_list_id", "book_id"}))
public class CuratedRanking implements BookRanking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "curated_list_id")
    private CuratedList curatedList;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    private Book book;

    @Enumerated(EnumType.STRING)
    @Column(name = "bookshelf")
    private Bookshelf bookshelf;

    @Enumerated(EnumType.STRING)
    private BookCategory category;

    private Integer position;

    @Column(length = 5000)
    private String review;

    public CuratedRanking() {}

    public CuratedRanking(CuratedList curatedList, Book book, Bookshelf bookshelf, BookCategory category, Integer position) {
        this.curatedList = curatedList;
        this.book = book;
        this.bookshelf = bookshelf;
        this.category = category;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CuratedList getCuratedList() {
        return curatedList;
    }

    public void setCuratedList(CuratedList curatedList) {
        this.curatedList = curatedList;
    }

    @Override
    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    @Override
    public Bookshelf getBookshelf() {
        return bookshelf;
    }

    public void setBookshelf(Bookshelf bookshelf) {
        this.bookshelf = bookshelf;
    }

    @Override
    public BookCategory getCategory() {
        return category;
    }

    public void setCategory(BookCategory category) {
        this.category = category;
    }

    @Override
    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    @Override
    public String getReview() {
        return review;
    }

    public void setReview(String review) {
        this.review = review;
    }
}
