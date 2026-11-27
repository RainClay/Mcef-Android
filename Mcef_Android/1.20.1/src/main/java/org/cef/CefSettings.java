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

package org.cef;

/**
 * CEF 全局设置。
 *
 * <p>Android 上 Cefrium 的初始化参数（缓存目录、数据目录）由 SDK 自己从
 * Application context 推导，所以这里只保留消费方可能读写的字段，
 * <b>不提供</b>真正生效的通道 —— 需要改行为请用 {@link CefApp} 的说明。</p>
 */
public class CefSettings {

    /** 浏览器子进程的可执行文件路径（Android 上无意义，保留字段）。 */
    public String browser_subprocess_path = "";

    /** 全局缓存目录。 */
    public String cache_path = "";

    /** 日志级别，见内部常量。 */
    public int log_severity = 0;

    /** 日志文件路径。 */
    public String log_file = "";

    /** 是否启用窗口无关（离屏）渲染。Android 侧恒为 true。 */
    public boolean windowless_rendering_enabled = true;

    /** 是否启用无沙箱模式。 */
    public boolean no_sandbox = false;

    /** UI 语言。 */
    public String locale = "";

    /** 是否从命令行开关读取配置。 */
    public boolean command_line_args_disabled = false;

    /** 远程调试端口，&lt;=0 表示关闭。 */
    public int remote_debugging_port = 0;

    /** 未捕获异常栈大小。 */
    public int uncaught_exception_stack_size = 0;

    /** 背景色（ARGB）。 */
    public int background_color = 0xFFFFFFFF;

    /** 接受语言列表。 */
    public String accept_language_list = "";
}
