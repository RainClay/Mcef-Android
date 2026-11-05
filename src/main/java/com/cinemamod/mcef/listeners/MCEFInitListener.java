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

/**
 * 引擎初始化完成回调。
 *
 * <p><b>签名必须与上游逐字一致</b>：WebDisplays 的
 * {@code MCEF.scheduleForInit((cef) -> onCefInit())} 会在编译期针对上游的
 * 函数式接口生成 lambda 类。如果这里的抽象方法名或参数类型不同，运行期就是
 * {@code AbstractMethodError}：</p>
 * <pre>
 * Receiver class ...$$Lambda$4843 does not define or inherit an implementation
 * of the resolved method 'abstract void onCefInit(MCEFApp)' of interface MCEFInitListener
 * </pre>
 *
 * <p>上游定义是 {@code void onInit(boolean successful)} —— 注意方法名是
 * {@code onInit} 而不是 {@code onCefInit}，参数是 boolean 而不是 MCEFApp。</p>
 */
@FunctionalInterface
public interface MCEFInitListener {

    /**
     * @param successful 引擎是否初始化成功
     */
    void onInit(boolean successful);
}
