package armchair.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "books")
public class Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String workOlid;
    private String editionOlid;
    private String isbn13;
    private Integer coverId;
    @Column(nullable = false, length = 1000)
    private String title;
    @Column(nullable = false, length = 1000)
    private String author;
    private Integer firstPublishYear;

    public Book() {}

    public Book(String workOlid, String editionOlid, String title, String author, Integer firstPublishYear, Integer coverId) {
        this.workOlid = workOlid;
        this.editionOlid = editionOlid;
        this.title = title;
        this.author = author;
        this.firstPublishYear = firstPublishYear;
        this.coverId = coverId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWorkOlid() {
        return workOlid;
    }

    public void setWorkOlid(String workOlid) {
        this.workOlid = workOlid;
    }

    public String getEditionOlid() {
        return editionOlid;
    }

    public void setEditionOlid(String editionOlid) {
        this.editionOlid = editionOlid;
    }

    public String getIsbn13() {
        return isbn13;
    }

    public void setIsbn13(String isbn13) {
        this.isbn13 = isbn13;
    }

    public Integer getCoverId() {
        return coverId;
    }

    public void setCoverId(Integer coverId) {
        this.coverId = coverId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Integer getFirstPublishYear() {
        return firstPublishYear;
    }

    public void setFirstPublishYear(Integer firstPublishYear) {
        this.firstPublishYear = firstPublishYear;
    }

}
