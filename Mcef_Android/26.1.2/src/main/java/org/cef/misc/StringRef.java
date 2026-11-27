package org.cef.misc;

/**
 * JCEF 用可变 String 当出参。保留同名同义。
 */
public class StringRef {
    public String value;

    public StringRef() {
        this("");
    }

    public StringRef(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
