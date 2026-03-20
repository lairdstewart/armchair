package armchair.entity;

import java.io.Serializable;

public class EditionSelection implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String SOURCE_SEARCH = "SEARCH";
    public static final String SOURCE_RESOLVE = "RESOLVE";

    private String editionOlid;
    private String isbn13;
    private boolean editionSelected;
    private String editionSource;

    public EditionSelection() {}

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

    public boolean isEditionSelected() {
        return editionSelected;
    }

    public void setEditionSelected(boolean editionSelected) {
        this.editionSelected = editionSelected;
    }

    public String getEditionSource() {
        return editionSource;
    }

    public void setEditionSource(String editionSource) {
        this.editionSource = editionSource;
    }

    public void clear() {
        this.editionOlid = null;
        this.isbn13 = null;
        this.editionSelected = false;
        this.editionSource = null;
    }
}
