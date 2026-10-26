package org.cef.misc;

/**
 * 光标类型。JCEF 用它把页面里 CSS cursor 映射到 AWT 光标；
 * Android 上我们只有一个真实光标（触屏），这里保留枚举以便消费方代码能编译，
 * 桥接层会把需要的类型（POINTER / HAND / TEXT）映射成 Android 的 PointerIcon。
 */
public enum CefCursorType {
    POINTER,
    CROSS,
    HAND,
    IBEAM,
    WAIT,
    HELP,
    EAST_RESIZE,
    NORTH_RESIZE,
    NORTHEAST_RESIZE,
    NORTHWEST_RESIZE,
    SOUTH_RESIZE,
    SOUTHEAST_RESIZE,
    SOUTHWEST_RESIZE,
    WEST_RESIZE,
    NORTH_SOUTH_RESIZE,
    EAST_WEST_RESIZE,
    NORTHEAST_SOUTHWEST_RESIZE,
    NORTHWEST_SOUTHEAST_RESIZE,
    COLUMN_RESIZE,
    ROW_RESIZE,
    MIDDLE_PANNING,
    NONE,
    NOT_ALLOWED,
    COPY,
    MOVE,
    ZOOM_IN,
    ZOOM_OUT,
    GRAB,
    GRABBING,
    CONTEXT_MENU,
    CELL,
    VERTICAL_TEXT,
    NO_DROP,
    CUSTOM;

    /**
     * GLFW 标准光标常量。
     *
     * <p>WebDisplays 的 GuiMinePad 里直接传给 {@code GLFW.glfwSetCursor}：
     * {@code GLFW.glfwSetCursor(win, CefCursorType.fromId(id).glfwId)}。
     * 值用 GLFW 的 {@code GLFW_*_CURSOR} 常量（0x00036001 起），
     * 未映射的类型回退成箭头光标。</p>
     */
    public final int glfwId;

    CefCursorType() {
        this.glfwId = defaultGlfwId(ordinal());
    }

    private static int defaultGlfwId(int ordinal) {
        switch (ordinal) {
            case 1:  return 0x00036003; // CROSS       → GLFW_CROSSHAIR_CURSOR
            case 2:  return 0x00036004; // HAND        → GLFW_HAND_CURSOR
            case 3:  return 0x00036002; // IBEAM       → GLFW_IBEAM_CURSOR
            case 11: return 0x00036005; // EAST_RESIZE → GLFW_HRESIZE_CURSOR
            case 10:
            case 12: return 0x00036006; // NORTH/SOUTH → GLFW_VRESIZE_CURSOR
            case 20: return 0x00036005; // COLUMN_RESIZE
            case 21: return 0x00036006; // ROW_RESIZE
            default: return 0x00036001; // POINTER 及其他 → GLFW_ARROW_CURSOR
        }
    }

    /**
     * 按序号取光标类型。
     *
     * <p>WebDisplays 用它把页面报上来的鼠标类型（int）转成枚举：
     * {@code CefCursorType.fromId(data.mouseType)}。越界时回退到
     * {@link #POINTER} 而不是抛异常 —— 页面给的 cursor 值来自 CSS，
     * 不保证落在我们的枚举范围内，为此让整帧渲染崩掉不值得。</p>
     */
    public static CefCursorType fromId(int id) {
        final CefCursorType[] all = values();
        if (id < 0 || id >= all.length) {
            return POINTER;
        }
        return all[id];
    }
}
