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
    CUSTOM
}
