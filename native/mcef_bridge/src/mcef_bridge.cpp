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

#include "mcef_bridge.h"
#include "mcef_ipc.h"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

// 单个浏览器的 native 状态。
// 注意：这里保存的是桥接层状态，不一定等于真实 CefBrowser 指针。
// 如果后端跑在 Zalith 进程里，这个 id 会映射到远端 CefriumBrowser。
struct PendingQuery {
    long long query_id = 0;
    int persistent = 0;
    std::string request;
    std::string origin;
};

struct BrowserState {
    mcef_browser_t id = 0;
    int width = 0;
    int height = 0;
    int stride = 0;
    float scale = 1.0f;
    bool transparent = false;
    bool closed = false;
    uint64_t frame_version = 0;
    std::string url;
    std::string dom_json;
    // 待处理的 JS 查询。Host 推上来、MC 侧取走 —— 只有 Host 持有 Cefrium 的
    // callback 实例，所以 MC 算完必须回到 Host 才能答复页面。
    std::deque<PendingQuery> queries;
    // 最新一帧以不可变快照的形式发布：读者在锁内只取一次 shared_ptr（O(1)），
    // 真正的 memcpy 在锁外做。否则几 MB 的帧复制会一直占着 g_mutex，
    // 把 sendInput / loadUrl / Host 回帧一起堵住。
    std::shared_ptr<const std::vector<uint8_t>> frame;
};

// 帧缓冲池：已经发布出去的缓冲在被读者持有期间不能复用。
// use_count()==1 表示只有池在引用，即空闲。只做启发式判断，
// 最坏情况是多分配一块，不影响正确性。
struct FrameBufferPool {
    std::vector<std::shared_ptr<std::vector<uint8_t>>> buffers;

    std::shared_ptr<std::vector<uint8_t>> acquire() {
        for (auto& buf : buffers) {
            if (buf.use_count() == 1) return buf;
        }
        buffers.emplace_back(std::make_shared<std::vector<uint8_t>>());
        return buffers.back();
    }
};

// 全局状态用一把锁保护，先保证简单可靠；后面做高性能帧环形缓冲时再细分锁。
std::mutex g_mutex;
std::unordered_map<mcef_browser_t, BrowserState> g_browsers;
std::atomic<mcef_browser_t> g_next_id{1};
bool g_initialized = false;
std::string g_last_error;
std::string g_runtime_dir;
std::string g_cache_dir;
std::string g_cef_library_path;
std::string g_ipc_endpoint;
bool g_ipc_enabled = false;
constexpr int kMaxBrowserWidth = 16384;
constexpr int kMaxBrowserHeight = 16384;
constexpr size_t kMaxBrowserFrameBytes = 64ULL * 1024ULL * 1024ULL;

void set_error_locked(const char* message) {
    g_last_error = message ? message : "";
}

// C ABI 字符串拷贝工具：调用方可先传空 buffer 获取 required_size。
int copy_string(const std::string& value, char* out, size_t out_size, size_t* required_size) {
    const size_t required = value.size() + 1;
    if (required_size) {
        *required_size = required;
    }
    if (!out || out_size == 0) {
        return MCEF_ERR_BUFFER_TOO_SMALL;
    }
    if (out_size < required) {
        const size_t copy_count = out_size - 1;
        if (copy_count > 0) {
            std::memcpy(out, value.data(), copy_count);
        }
        out[copy_count] = '\0';
        return MCEF_ERR_BUFFER_TOO_SMALL;
    }
    std::memcpy(out, value.c_str(), required);
    return MCEF_OK;
}

// 查找浏览器。调用前必须已经持有 g_mutex。
BrowserState* find_browser_locked(mcef_browser_t browser) {
    auto it = g_browsers.find(browser);
    if (it == g_browsers.end()) {
        set_error_locked("browser not found");
        return nullptr;
    }
    if (it->second.closed) {
        set_error_locked("browser is closed");
        return nullptr;
    }
    return &it->second;
}

// 把 C 字符串安全转成 JS 字符串字面量，避免 selector/script 拼接时转义出错。
std::string js_quote(const char* text) {
    std::string out = "\"";
    if (text) {
        for (const unsigned char c : std::string(text)) {
            switch (c) {
                case '\\': out += "\\\\"; break;
                case '"': out += "\\\""; break;
                case '\b': out += "\\b"; break;
                case '\f': out += "\\f"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default:
                    if (c < 0x20) {
                        char buf[7];
                        std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                        out += buf;
                    } else {
                        out += static_cast<char>(c);
                    }
            }
        }
    }
    out += "\"";
    return out;
}

std::string json_escape_local(const char* text) {
    const char* escaped = mcef_ipc_escape_json_temp(text ? text : "");
    return escaped ? std::string(escaped) : std::string();
}

// 只入队，不发送。调用点大多持有 g_mutex（来自游戏线程），
// 在这里直接 send 会让 Host 卡顿直接传染成游戏卡顿。
void ipc_send_if_enabled(const std::string& json) {
    if (g_ipc_enabled) {
        mcef_ipc_enqueue(json.c_str());
    }
}

void ipc_send_create_locked(const BrowserState& b) {
    ipc_send_if_enabled("{\"cmd\":\"create\",\"id\":" + std::to_string(b.id) +
        ",\"url\":\"" + json_escape_local(b.url.c_str()) + "\",\"width\":" + std::to_string(b.width) +
        ",\"height\":" + std::to_string(b.height) + ",\"scale\":" + std::to_string(b.scale) +
        ",\"transparent\":" + (b.transparent ? "true" : "false") + "}");
}

void ipc_send_simple_id(const char* cmd, mcef_browser_t id) {
    ipc_send_if_enabled("{\"cmd\":\"" + std::string(cmd) + "\",\"id\":" + std::to_string(id) + "}");
}

// IPC 重连成功后的重同步：把所有已知浏览器重新发给 Host。
// Host 侧 create 是幂等的（同 id 会先关旧再建新），所以直接重发即可。
// 该函数在 IPC 接收线程上被调用，内部自行获取 g_mutex。
void ipc_resync_browsers() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ipc_enabled) return;
    for (auto& kv : g_browsers) {
        if (!kv.second.closed) {
            ipc_send_create_locked(kv.second);
        }
    }
}

} // namespace

extern "C" {

// 初始化桥接层。这里只记录配置并打开 API；不在这里强行初始化 Android Cefrium。
int mcef_init(const mcef_init_config_t* config) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_initialized = true;
    g_runtime_dir = config && config->runtime_dir ? config->runtime_dir : "";
    g_cache_dir = config && config->cache_dir ? config->cache_dir : "";
    g_cef_library_path = config && config->cef_library_path ? config->cef_library_path : "";
    g_ipc_enabled = config && config->enable_ipc != 0;
    g_ipc_endpoint = config && config->ipc_endpoint ? config->ipc_endpoint : "";
    set_error_locked("");
    if (g_ipc_enabled && !g_ipc_endpoint.empty()) {
        mcef_ipc_set_resync_callback(ipc_resync_browsers);
        const int ipc_rc = mcef_ipc_start(g_ipc_endpoint.c_str());
        if (ipc_rc != MCEF_OK) {
            set_error_locked("ipc connect failed");
            // IPC 失败不阻止本地缓存模式使用；新客户端在后台自动重连，
            // Host 恢复后浏览器会随 resync 自动重建。
        }
    }
    return MCEF_OK;
}

void mcef_shutdown(void) {
    mcef_ipc_stop();
    std::lock_guard<std::mutex> lock(g_mutex);
    g_browsers.clear();
    g_initialized = false;
}

int mcef_is_initialized(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_initialized ? 1 : 0;
}

// 创建桥接层浏览器。先分配句柄和缓存，真实浏览器由 Host/IPC 后端按需创建。
mcef_browser_t mcef_create_browser(const char* url, int width, int height, float scale, int transparent) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) { set_error_locked("mcef is not initialized"); return 0; }
    if (width <= 0 || height <= 0 || width > kMaxBrowserWidth || height > kMaxBrowserHeight) {
        set_error_locked("invalid browser size");
        return 0;
    }
    const size_t widthSize = static_cast<size_t>(width);
    const size_t heightSize = static_cast<size_t>(height);
    if (widthSize > SIZE_MAX / 4 || widthSize * 4 > kMaxBrowserFrameBytes ||
        heightSize > kMaxBrowserFrameBytes / (widthSize * 4)) {
        set_error_locked("browser frame too large");
        return 0;
    }
    BrowserState b;
    b.id = g_next_id.fetch_add(1);
    b.width = width;
    b.height = height;
    b.stride = width * 4;
    b.scale = scale <= 0.0f ? 1.0f : scale;
    b.transparent = transparent != 0;
    b.url = url ? url : "about:blank";
    b.dom_json = "{\"type\":\"empty\",\"items\":[]}";
    // 先发布一张全透明初始帧，保证首帧到达前 copy_frame 也有合法尺寸可读。
    b.frame = std::make_shared<const std::vector<uint8_t>>(
        static_cast<size_t>(b.stride) * static_cast<size_t>(height), 0);
    const mcef_browser_t id = b.id;
    // 先登记状态再发送 create，避免 Host 回帧快于 emplace 时丢掉首帧。
    g_browsers.emplace(id, std::move(b));
    ipc_send_create_locked(g_browsers.at(id));
    return id;
}

int mcef_destroy_browser(mcef_browser_t browser) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_browsers.find(browser);
    if (it == g_browsers.end()) return MCEF_ERR_NOT_FOUND;
    ipc_send_simple_id("destroy", browser);
    g_browsers.erase(it);
    return MCEF_OK;
}

int mcef_resize(mcef_browser_t browser, int width, int height) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (width <= 0 || height <= 0) return MCEF_ERR_INVALID_ARGUMENT;

    // 空操作，尺寸一律以 mcef_create_browser 时的值为准。
    //
    // 原因：Host(Cefrium SDK) 侧没有公开的 resize API，CEF 浏览器的实际尺寸在
    // create 时就固定了，Host 的 handleResize 也只是记录。这里若真的改尺寸：
    //   1) 已发布的帧快照会被换成一张全透明帧，frame_version++；
    //   2) 客户端下一次 tick 看到版本变化，就把这一整帧全透明内容上传成纹理
    //      → 界面瞬间变空白；
    //   3) 页面静止时没有新的 damage，CEF 不再回调 OnPaint，于是没有新帧来覆盖
    //      这张空白帧，白屏会一直留着——表现为"点一下界面就白 / 打不开"。
    // 同时 CEF 仍按旧尺寸出帧，缓存与真实帧尺寸不一致还会让 mcef_copy_frame
    // 的校验/stride 对不上，所以不能假装 resize 已经成功。
    set_error_locked("resize unsupported by IPC host");
    return MCEF_ERR_UNSUPPORTED;
}

int mcef_load_url(mcef_browser_t browser, const char* url) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (!url) return MCEF_ERR_INVALID_ARGUMENT;
    b->url = url;
    ipc_send_if_enabled("{\"cmd\":\"loadUrl\",\"id\":" + std::to_string(browser) +
        ",\"url\":\"" + json_escape_local(url) + "\"}");
    return MCEF_OK;
}

int mcef_reload(mcef_browser_t browser, int) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    ipc_send_simple_id("reload", browser);
    return MCEF_OK;
}

int mcef_go_back(mcef_browser_t browser) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    ipc_send_simple_id("goBack", browser);
    return MCEF_OK;
}

int mcef_go_forward(mcef_browser_t browser) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    ipc_send_simple_id("goForward", browser);
    return MCEF_OK;
}

int mcef_stop_loading(mcef_browser_t browser) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    ipc_send_simple_id("stopLoading", browser);
    return MCEF_OK;
}

int mcef_send_input(mcef_browser_t browser, const mcef_input_event_t* event) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (!event) return MCEF_ERR_INVALID_ARGUMENT;
    ipc_send_if_enabled("{\"cmd\":\"input\",\"id\":" + std::to_string(browser) +
        ",\"type\":" + std::to_string(event->type) + ",\"x\":" + std::to_string(event->x) +
        ",\"y\":" + std::to_string(event->y) + ",\"button\":" + std::to_string(event->button) +
        ",\"keyCode\":" + std::to_string(event->key_code) + ",\"scanCode\":" + std::to_string(event->scan_code) +
        ",\"modifiers\":" + std::to_string(event->modifiers) + ",\"deltaX\":" + std::to_string(event->delta_x) +
        ",\"deltaY\":" + std::to_string(event->delta_y) + ",\"codepoint\":" + std::to_string(event->codepoint) + "}");
    return MCEF_OK;
}

int mcef_evaluate_javascript(mcef_browser_t browser, const char* script) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (!script) return MCEF_ERR_INVALID_ARGUMENT;
    ipc_send_if_enabled("{\"cmd\":\"eval\",\"id\":" + std::to_string(browser) +
        ",\"script\":\"" + json_escape_local(script) + "\"}");
    return MCEF_OK;
}

int mcef_request_dom_snapshot(mcef_browser_t browser) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    // Host 已有专用命令；不要再额外发送同一份 eval 脚本。
    ipc_send_if_enabled("{\"cmd\":\"domSnapshot\",\"id\":" + std::to_string(browser) + "}");
    return MCEF_OK;
}

int mcef_request_elements_at_point(mcef_browser_t browser, int x, int y) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    ipc_send_if_enabled("{\"cmd\":\"elementsAtPoint\",\"id\":" + std::to_string(browser) +
        ",\"x\":" + std::to_string(x) + ",\"y\":" + std::to_string(y) + "}");
    return MCEF_OK;
}

int mcef_click_dom(mcef_browser_t browser, const char* selector) {
    if (!selector) return MCEF_ERR_INVALID_ARGUMENT;
    const std::string script = "(() => { const el = document.querySelector(" + js_quote(selector) + "); if (el) { el.click(); return true; } return false; })()";
    return mcef_evaluate_javascript(browser, script.c_str());
}

int mcef_get_frame_info(mcef_browser_t browser, mcef_frame_info_t* out_info) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (!out_info) return MCEF_ERR_INVALID_ARGUMENT;
    out_info->width = b->width; out_info->height = b->height; out_info->stride = b->stride; out_info->format = 1; out_info->version = b->frame_version;
    return MCEF_OK;
}

int mcef_copy_frame(mcef_browser_t browser, void* out_bgra, size_t out_size, mcef_frame_info_t* out_info) {
    if (!out_bgra) return MCEF_ERR_INVALID_ARGUMENT;
    std::shared_ptr<const std::vector<uint8_t>> snapshot;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
        if (out_info) { out_info->width=b->width; out_info->height=b->height; out_info->stride=b->stride; out_info->format=1; out_info->version=b->frame_version; }
        if (!b->frame) return MCEF_ERR_NOT_FOUND;
        if (out_size < b->frame->size()) return MCEF_ERR_BUFFER_TOO_SMALL;
        snapshot = b->frame; // 只做引用计数 +1，锁内不做大拷贝
    }
    if (!snapshot->empty()) std::memcpy(out_bgra, snapshot->data(), snapshot->size());
    return MCEF_OK;
}

int mcef_get_dom_json(mcef_browser_t browser, char* out_utf8, size_t out_size, size_t* required_size) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    return copy_string(b->dom_json, out_utf8, out_size, required_size);
}

int mcef_get_url(mcef_browser_t browser, char* out_utf8, size_t out_size, size_t* required_size) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    return copy_string(b->url, out_utf8, out_size, required_size);
}

int mcef_take_query(mcef_browser_t browser, mcef_query_t* out_query) {
    if (!out_query) return MCEF_ERR_INVALID_ARGUMENT;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (b->queries.empty()) return MCEF_ERR_NOT_FOUND;

    PendingQuery q = std::move(b->queries.front());
    b->queries.pop_front();

    // 用 malloc 而不是 new：跨 DLL/so 边界的释放必须走同一套分配器，
    // 配套的 mcef_free_query 用 free。
    const auto dup = [](const std::string& src) -> char* {
        char* p = static_cast<char*>(std::malloc(src.size() + 1));
        if (!p) return nullptr;
        std::memcpy(p, src.c_str(), src.size() + 1);
        return p;
    };
    out_query->query_id = q.query_id;
    out_query->persistent = q.persistent;
    out_query->request = dup(q.request);
    out_query->origin = dup(q.origin);
    if (!out_query->request || !out_query->origin) {
        std::free(out_query->request);
        std::free(out_query->origin);
        out_query->request = nullptr;
        out_query->origin = nullptr;
        return MCEF_ERR_INTERNAL;
    }
    return MCEF_OK;
}

void mcef_free_query(mcef_query_t* query) {
    if (!query) return;
    std::free(query->request);
    std::free(query->origin);
    query->request = nullptr;
    query->origin = nullptr;
}

int mcef_reply_query(mcef_browser_t browser, long long query_id,
                     int success, const char* result, int error_code) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!find_browser_locked(browser)) return MCEF_ERR_NOT_FOUND;
    const std::string body = result ? result : "";
    ipc_send_if_enabled(
        "{\"cmd\":\"queryReply\",\"id\":" + std::to_string(browser) +
        ",\"queryId\":" + std::to_string(query_id) +
        ",\"success\":" + (success ? "true" : "false") +
        ",\"errorCode\":" + std::to_string(error_code) +
        ",\"result\":\"" + json_escape_local(body.c_str()) + "\"}");
    return MCEF_OK;
}

int mcef_get_last_error(char* out_utf8, size_t out_size, size_t* required_size) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return copy_string(g_last_error, out_utf8, out_size, required_size);
}

// Host 回填真实帧：通常由 CefriumRenderHandler.onPaint 的 BGRA ByteBuffer 调用。
// 注意：几 MB 的拷贝必须在锁外完成。接收线程若握着 g_mutex 做 memcpy，
// 会把每 tick 抢同一把锁的渲染线程（getFrameInfo/copyFrame）以及输入
// （sendInput）一起堵住——实测表现为游戏卡顿 + 点击响应延迟。
int mcef_host_set_frame(mcef_browser_t browser, const void* bgra, int width, int height, int stride) {
    if (!bgra || width <= 0 || height <= 0 || width > kMaxBrowserWidth ||
        height > kMaxBrowserHeight || stride < width * 4 || stride > 65536) {
        return MCEF_ERR_INVALID_ARGUMENT;
    }
    const size_t strideSize = static_cast<size_t>(stride);
    const size_t heightSize = static_cast<size_t>(height);
    if (heightSize > kMaxBrowserFrameBytes / strideSize) return MCEF_ERR_INVALID_ARGUMENT;
    const size_t size = strideSize * heightSize;

    // 锁外填好一块池内空闲缓冲，锁内只发布 shared_ptr 快照（O(1)）。
    static thread_local FrameBufferPool pool;
    auto buffer = pool.acquire();
    buffer->resize(size);
    std::memcpy(buffer->data(), bgra, size);

    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    b->width = width; b->height = height; b->stride = stride;
    b->frame = buffer;
    b->frame_version++;
    return MCEF_OK;
}

// Host 回填 DOM JSON：通常来自 cefriumQuery 或 evaluateJavaScript 的回调。
int mcef_host_set_dom_json(mcef_browser_t browser, const char* json) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    b->dom_json = json ? json : "";
    return MCEF_OK;
}

int mcef_host_push_query(mcef_browser_t browser, long long query_id,
                         const char* request, const char* origin, int persistent) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    if (query_id <= 0) return MCEF_ERR_INVALID_ARGUMENT;
    // 队列上限：MC 侧卡住时不能让 Host 无限堆积内存。超出就丢最旧的，
    // 页面那边的 cefQuery 本来也有超时。
    constexpr size_t kMaxPendingQueries = 64;
    if (b->queries.size() >= kMaxPendingQueries) {
        b->queries.pop_front();
    }
    PendingQuery q;
    q.query_id = query_id;
    q.persistent = persistent ? 1 : 0;
    q.request = request ? request : "";
    q.origin = origin ? origin : "";
    b->queries.push_back(std::move(q));
    return MCEF_OK;
}

int mcef_host_set_url(mcef_browser_t browser, const char* url) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* b = find_browser_locked(browser); if (!b) return MCEF_ERR_NOT_FOUND;
    b->url = url ? url : "";
    return MCEF_OK;
}

} // extern C
