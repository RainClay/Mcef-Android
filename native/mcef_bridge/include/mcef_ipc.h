/*
 * libmcef —— MCEF 的 Android native 桥接层
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
 */

#pragma once

#include "mcef_bridge.h"

#ifdef __cplusplus
extern "C" {
#endif

// libmcef.so 内部使用的轻量 IPC 客户端。
// 协议为 TCP 行协议：每条控制/事件消息是一行 JSON（\n 结尾）。
// 大帧使用「JSON header 行 + 紧跟 size 字节裸 BGRA」两段式：
//   {"event":"frame","id":N,"w":W,"h":H,"stride":S,"size":S*H}\n<raw bytes>
// Host 掉线（如被系统回收）后，客户端会自动重连，成功后调用 resync 回调
// 让桥接层把已知浏览器重新创建一遍。

int mcef_ipc_start(const char* endpoint);
void mcef_ipc_stop(void);
int mcef_ipc_is_running(void);
int mcef_ipc_send_json(const char* json_line);

// 非阻塞入队：由独立发送线程按 FIFO 顺序真正写出。
// 调用方（通常是持有桥接层状态锁、来自游戏线程的 API）不会阻塞在 socket 上。
// 队列有上限；已满时丢弃并返回 MCEF_ERR_BUFFER_TOO_SMALL。
int mcef_ipc_enqueue(const char* json_line);

// 重连成功后需要把本地已知浏览器重新发给 Host。回调由 mcef_bridge.cpp 注册，
// 在 IPC 接收线程上执行，实现方需自行加锁。
typedef void (*mcef_ipc_resync_fn)(void);
void mcef_ipc_set_resync_callback(mcef_ipc_resync_fn fn);

// JSON 字符串转义工具，给 mcef_bridge.cpp 拼协议消息用。
// 返回值是不含首尾引号的已转义内容。
const char* mcef_ipc_escape_json_temp(const char* text);

#ifdef __cplusplus
}
#endif
