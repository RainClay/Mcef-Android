package org.cef.misc;

/**
 * 矩形（x, y, width, height）。
 *
 * <p><b>为什么不直接用 {@code java.awt.Rectangle}</b>：Android 上没有 java.awt，
 * 连 {@code android.jar} 里也只有 {@code java.awt.font} 而<b>没有</b> Rectangle。
 * 所以这里自带一个最小实现，字段名与 AWT 保持一致（x / y / width / height），
 * 让消费方读字段的代码不用改。</p>
 *
 * <p>局部变量声明成 {@code Rectangle} 的地方（例如 WebDisplays 里
 * {@code Rectangle[] dirtyRects}）需要把类型换成这个类 —— 这是移植到 Android
 * 唯一无法避免的源码改动点，已在 README 记录。</p>
 */
public class CefRect {

    public int x;
    public int y;
    public int width;
    public int height;

    public CefRect() {
        this(0, 0, 0, 0);
    }

    public CefRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    public int area() {
        return width * height;
    }

    @Override
    public String toString() {
        return "CefRect[" + x + "," + y + " " + width + "x" + height + "]";
    }
}
