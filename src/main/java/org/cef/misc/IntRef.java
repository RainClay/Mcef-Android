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

    /**
     * 设置值。
     *
     * <p>WebDisplays 的 {@code WDScheme.readResponse} 用
     * {@code bytesRead.set(n)} 回填读到的字节数，所以这个 setter 必须有。</p>
     */
    public void set(int value) {
        this.value = value;
    }

    public int get() {
        return value;
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
