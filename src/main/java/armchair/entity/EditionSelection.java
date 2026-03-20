package armchair.entity;

import java.io.Serializable;

public class EditionSelection implements Serializable {
    private static final long serialVersionUID = 1L;

    private String editionOlid;
    private String isbn13;
    private boolean editionSelected;

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

    public void clear() {
        this.editionOlid = null;
        this.isbn13 = null;
        this.editionSelected = false;
    }
}
