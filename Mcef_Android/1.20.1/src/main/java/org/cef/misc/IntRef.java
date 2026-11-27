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
 * PROVENANCE: the API shape follows JCEF / Chromium Embedded Framework
 * (BSD-3-Clause). This file is an independent Android implementation and
 * contains no JCEF source code. Class, package and method names are kept
 * identical only to satisfy bytecode references from consumers such as
 * WebDisplays.
 */

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
