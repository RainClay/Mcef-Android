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
 * <p>WebDisplays 用它把页面里的 CSS cursor 同步到游戏内指针。</p>
 *
 * <p><b>目前不会被触发</b>：IPC 桥里没有 cursor 命令 —— native
 * （{@code mcef_bridge.cpp}）和 host（{@code CefriumHostService}）两侧都搜不到
 * 相关 cmd，Cefrium 也没暴露光标类型回调。{@link
 * com.cinemamod.mcef.MCEFBrowser#setCursor} 只会把值转给已注册的监听者，
 * 而注册者永远等不到事件。</p>
 *
 * <p>要真正支持，需要在 host 侧接上 Cefrium 的 cursor 回调、上报给 native、
 * native 再加一条查询命令，最后由 MCEFBrowser 分发。保留本接口是为了让
 * 消费方代码能编译并通过链接。</p>
 */
public interface MCEFCursorChangeListener {

    /** 光标类型变化。 */
    void onCursorChange(CefCursorType type);
}
