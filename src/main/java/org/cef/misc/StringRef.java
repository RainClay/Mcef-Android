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

    /** 设置值（JCEF 的出参写法，必须与 IntRef 对称提供）。 */
    public void set(String value) {
        this.value = value;
    }

    public String get() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
