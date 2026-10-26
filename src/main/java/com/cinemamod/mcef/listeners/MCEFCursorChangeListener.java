package com.cinemamod.mcef.listeners;

import org.cef.misc.CefCursorType;

/**
 * 光标类型变化监听。
 *
 * <p>WebDisplays 用它把页面里的 CSS cursor 同步到游戏内指针。
 * <b>Android 限制</b>：Cefrium 没有暴露光标类型回调，所以这个接口
 * 目前<b>不会被触发</b> —— 保留是为了让消费方代码能编译。
 * 真正要实现的话，需要 Cefrium 侧加 {@code setCursorChangeHandler}。</p>
 */
public interface MCEFCursorChangeListener {

    /** 光标类型变化。 */
    void onCursorChange(CefCursorType type);
}
