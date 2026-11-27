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

package com.cinemamod.mcef;

import org.cef.CefApp;

/**
 * MCEFApp —— 上游 MCEF 里的「应用」包装。
 *
 * <p>WebDisplays 的调用形状是
 * {@code MCEF.getApp().getHandle().registerSchemeHandlerFactory(...)}，
 * 即 MCEFApp 持有真正的 {@link CefApp}。</p>
 */
public class MCEFApp {

    private final CefApp handle;

    public MCEFApp(CefApp handle) {
        this.handle = handle == null ? CefApp.getInstance() : handle;
    }

    /** 内部的 {@link CefApp}。 */
    public CefApp getHandle() {
        return handle;
    }
}
