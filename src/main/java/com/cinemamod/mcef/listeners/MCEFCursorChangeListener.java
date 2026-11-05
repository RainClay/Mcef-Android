/*
 * MCEF-Android (Forge 1.20.1)
 * Copyright (C) 2026 Rain_Clay and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * PROVENANCE: independent implementation, not a derivative work of
 * CinemaMod MCEF (LGPL-2.1). Names are kept identical only to satisfy
 * WebDisplays bytecode references; no upstream source, comments or
 * implementation logic is included. See README "与上游的关系".
 */

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
