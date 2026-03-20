package armchair.entity;

import java.io.Serializable;

public class RestorationState implements Serializable {
    private static final long serialVersionUID = 1L;

    private BookCategory originalCategory;
    private Integer originalPosition;
    private String originalResolveTitle;
    private String originalResolveAuthor;
    private String originalResolveWorkOlid;
    private String originalResolveEditionOlid;

    public RestorationState() {}

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

    public boolean hasReRankRestore() {
        return originalCategory != null && originalPosition != null;
    }

    public boolean hasResolveRestore() {
        return originalResolveTitle != null;
    }

    public void clearResolve() {
        this.originalResolveTitle = null;
        this.originalResolveAuthor = null;
        this.originalResolveWorkOlid = null;
        this.originalResolveEditionOlid = null;
    }
}
