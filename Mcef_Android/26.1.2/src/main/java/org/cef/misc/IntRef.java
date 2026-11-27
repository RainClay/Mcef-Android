package org.cef.misc;

/**
 * JCEF 用可变 int 当出参（Java 没有 out 参数）。保留同名同义。
 */
public class IntRef {
    public int value;

    public IntRef() {
        this(0);
    }

    public IntRef(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
