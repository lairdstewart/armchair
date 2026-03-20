package armchair.entity;

import java.io.Serializable;

public class BookIdentity implements Serializable {
    private static final long serialVersionUID = 1L;

    private String workOlid;
    private String title;
    private String author;

    public BookIdentity() {}

    public String getWorkOlid() {
        return workOlid;
    }

    public void setWorkOlid(String workOlid) {
        this.workOlid = workOlid;
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

    public void setBookInfo(String workOlid, String title, String author) {
        this.workOlid = workOlid;
        this.title = title;
        this.author = author;
    }
}
