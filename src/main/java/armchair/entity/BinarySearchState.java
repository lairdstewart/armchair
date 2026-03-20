package armchair.entity;

import java.io.Serializable;

public class BinarySearchState implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer compareToIndex;
    private Integer lowIndex;
    private Integer highIndex;

    public BinarySearchState() {}

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

    public void initialize(int low, int high) {
        this.lowIndex = low;
        this.highIndex = high;
        this.compareToIndex = (low + high) / 2;
    }

    public int insertionIndex() {
        return lowIndex;
    }
}
