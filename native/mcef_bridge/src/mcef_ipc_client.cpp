#include "mcef_ipc.h"
#include "mcef_bridge.h"

#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr long long kMaxFrameBytes = 64LL * 1024LL * 1024LL;
constexpr size_t kMaxControlLineBytes = 256 * 1024;
constexpr size_t kMaxReceiveBufferBytes = 80 * 1024 * 1024;
// 出站队列上限：Host 挂掉时不能让桥接层无限攒消息。
constexpr size_t kMaxOutboundBytes = 4 * 1024 * 1024;

std::mutex g_send_mutex;
std::thread g_rx_thread;
// g_want：客户端生命周期（stop 置 false）；g_running：当前 socket 是否连通。
std::atomic<bool> g_want{false};
std::atomic<bool> g_running{false};
std::atomic<int> g_fd{-1};
std::string g_endpoint;
mcef_ipc_resync_fn g_resync_cb = nullptr;
thread_local std::string g_escape_buffer;

// 出站队列 + 独立发送线程。
// 桥接层 API（create/loadUrl/input/eval）大多在游戏线程上、且持有桥接层状态锁，
// 绝不能在那里阻塞在 socket 上；这里只入队，由 g_tx_thread 顺序写出。
std::mutex g_queue_mutex;
std::condition_variable g_queue_cv;
std::deque<std::string> g_out_queue;
size_t g_out_queue_bytes = 0;
std::thread g_tx_thread;
std::atomic<bool> g_tx_want{false};

void clear_outbound_queue();

// 简单提取 JSON 字符串字段。只给我们自己的行协议使用，不做完整 JSON 解析。
std::string extract_json_string(const std::string& line, const char* key) {
    const std::string pat = std::string("\"") + key + "\"";
    size_t p = line.find(pat);
    if (p == std::string::npos) return {};
    p = line.find(':', p + pat.size());
    if (p == std::string::npos) return {};
    p = line.find('"', p + 1);
    if (p == std::string::npos) return {};
    std::string out;
    bool esc = false;
    for (++p; p < line.size(); ++p) {
        char c = line[p];
        if (esc) {
            switch (c) {
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case '\\': out.push_back('\\'); break;
                case '"': out.push_back('"'); break;
                default: out.push_back(c); break;
            }
            esc = false;
            continue;
        }
        if (c == '\\') { esc = true; continue; }
        if (c == '"') break;
        out.push_back(c);
    }
    return out;
}

long long extract_json_int(const std::string& line, const char* key, long long def = 0) {
    const std::string pat = std::string("\"") + key + "\"";
    size_t p = line.find(pat);
    if (p == std::string::npos) return def;
    p = line.find(':', p + pat.size());
    if (p == std::string::npos) return def;
    ++p;
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    char* end = nullptr;
    const long long v = std::strtoll(line.c_str() + p, &end, 10);
    return end == line.c_str() + p ? def : v;
}

bool looks_like_frame(const std::string& line) {
    return extract_json_string(line, "event") == "frame";
}

void handle_line(const std::string& line) {
    const std::string event = extract_json_string(line, "event");
    const auto id = static_cast<mcef_browser_t>(extract_json_int(line, "id", 0));
    if (id <= 0) return;

    if (event == "dom") {
        const std::string json = extract_json_string(line, "json");
        mcef_host_set_dom_json(id, json.c_str());
        return;
    }
    if (event == "url") {
        const std::string url = extract_json_string(line, "url");
        mcef_host_set_url(id, url.c_str());
        return;
    }
    if (event == "created") {
        return; // 目前无需处理，保留语义。
    }
}

bool endpoint_split(const char* endpoint, std::string& host, int& port) {
    if (!endpoint || !*endpoint) return false;
    std::string s(endpoint);
    size_t colon = s.rfind(':');
    if (colon == std::string::npos) return false;
    host = s.substr(0, colon);
    port = std::atoi(s.c_str() + colon + 1);
    if (host.empty()) host = "127.0.0.1";
    return port > 0 && port < 65536;
}

// 建立一次连接；成功返回 true 并写入 g_fd。
bool connect_once() {
    std::string host;
    int port = 0;
    if (!endpoint_split(g_endpoint.c_str(), host, port)) return false;

    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return false;

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (::inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
        hostent* he = ::gethostbyname(host.c_str());
        if (!he || he->h_addrtype != AF_INET || he->h_length <= 0) {
            ::close(fd);
            return false;
        }
        std::memcpy(&addr.sin_addr, he->h_addr_list[0], sizeof(addr.sin_addr));
    }

    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0 || ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        ::close(fd);
        return false;
    }
    const int connect_rc = ::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (connect_rc != 0 && errno != EINPROGRESS) {
        ::close(fd);
        return false;
    }
    if (connect_rc != 0) {
        pollfd pfd{fd, POLLOUT, 0};
        if (::poll(&pfd, 1, 2000) <= 0 || !(pfd.revents & POLLOUT)) {
            ::close(fd);
            return false;
        }
        int error = 0;
        socklen_t error_len = sizeof(error);
        if (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &error_len) != 0 || error != 0) {
            ::close(fd);
            return false;
        }
    }
    if (!g_want.load()) {
        ::close(fd);
        return false;
    }
    if (::fcntl(fd, F_SETFL, flags) != 0) {
        ::close(fd);
        return false;
    }
    timeval send_timeout{};
    send_timeout.tv_sec = 2;
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout));

    g_fd.store(fd);
    g_running.store(true);
    return true;
}

void close_current() {
    std::lock_guard<std::mutex> lock(g_send_mutex);
    g_running.store(false);
    const int fd = g_fd.exchange(-1);
    if (fd >= 0) {
        ::shutdown(fd, SHUT_RDWR);
        ::close(fd);
    }
}

// 单次连接上的接收循环。帧消息在此消费（header 行 + size 字节裸数据）。
void run_connection() {
    std::string buffer;
    std::vector<char> tmp(256 * 1024);
    while (g_running.load() && g_want.load()) {
        const int fd = g_fd.load();
        if (fd < 0) break;
        const ssize_t n = ::recv(fd, tmp.data(), tmp.size(), 0);
        if (n <= 0) {
            if (errno == EINTR) continue;
            break; // 对端关闭或出错 → 外层重连
        }
        buffer.append(tmp.data(), tmp.data() + n);
        if (buffer.size() > kMaxReceiveBufferBytes) return;
        for (;;) {
            size_t nl = buffer.find('\n');
            if (nl == std::string::npos) {
                if (buffer.size() > kMaxControlLineBytes) return;
                break;
            }
            if (nl > kMaxControlLineBytes) return;
            std::string line = buffer.substr(0, nl);
            if (!line.empty() && line.back() == '\r') line.pop_back();

            if (looks_like_frame(line)) {
                const long long size = extract_json_int(line, "size", -1);
                // size 决定 payload 边界：不合理就无法重新同步，只能断开重连。
                // 同时在等待前先设上限，否则假 header 能让 buffer 一直涨。
                if (size <= 0 || size > kMaxFrameBytes) return;
                if (static_cast<long long>(buffer.size()) < static_cast<long long>(nl) + 1 + size) {
                    break; // payload 未收全，等待更多数据（不消费 header）
                }
                const auto id = static_cast<mcef_browser_t>(extract_json_int(line, "id", 0));
                const long long wll = extract_json_int(line, "w", 0);
                const long long hll = extract_json_int(line, "h", 0);
                const long long strideLL = extract_json_int(line, "stride", 0);
                // 头字段不自洽时只丢这一帧：payload 边界由 size 唯一确定，可以安全跳过，
                // 不必因为一帧坏数据把整条连接打掉。
                const bool consistent =
                    id > 0 && wll > 0 && hll > 0 && wll <= 16384 && hll <= 16384 &&
                    strideLL >= wll * 4 && strideLL <= 65536 &&
                    strideLL > 0 && hll <= kMaxFrameBytes / strideLL &&
                    size == strideLL * hll;
                // 诊断：帧头解析结果。视频不显示时靠它区分
                // 「根本没收到帧」和「收到了但字段不自洽被丢弃」。
                //
                // 写文件而不是 logcat：本 so 加载在 MC 的纯 JVM 进程里，
                // 读 logcat 需要特权通道；而写 /storage/emulated/0 是普通权限。
                static int g_frameLogCount = 0;
                if (++g_frameLogCount <= 8 || (g_frameLogCount % 300 == 0)) {
                    if (FILE* f = ::fopen("/storage/emulated/0/mcef-host-logs/mcef-bridge.log", "a")) {
                        ::fprintf(f,
                            "frame hdr #%d id=%lld w=%lld h=%lld stride=%lld size=%lld consistent=%d\n",
                            g_frameLogCount, (long long) id, wll, hll, strideLL, size,
                            consistent ? 1 : 0);
                        ::fclose(f);
                    }
                }
                if (consistent) {
                    const int frameRc = mcef_host_set_frame(
                        id, buffer.data() + nl + 1,
                        static_cast<int>(wll), static_cast<int>(hll),
                        static_cast<int>(strideLL));
                    // 诊断：看 set_frame 是否真的接受了这一帧。
                    // rc != 0 说明 find_browser_locked(id) 没找到 ——
                    // 那 version 不会递增，MC 侧就会永远看到 ver=0。
                    static int g_rcLogCount = 0;
                    if (++g_rcLogCount <= 8 || (g_rcLogCount % 300 == 0)) {
                        if (FILE* f = ::fopen("/storage/emulated/0/mcef-host-logs/mcef-bridge.log", "a")) {
                            ::fprintf(f, "host_set_frame #%d id=%lld rc=%d\n",
                                      g_rcLogCount, (long long) id, frameRc);
                            ::fclose(f);
                        }
                    }
                }
                buffer.erase(0, nl + 1 + static_cast<size_t>(size));
                continue;
            }

            buffer.erase(0, nl + 1);
            if (!line.empty()) handle_line(line);
        }
    }
}

// 接收线程主体：连不上/掉线时按 1s 间隔重连；每次成功连上后触发 resync。
void rx_main() {
    while (g_want.load()) {
        if (!connect_once()) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
            continue;
        }
        if (g_resync_cb) {
            g_resync_cb(); // 把本地已知浏览器重新发给 Host（create 幂等）。
        }
        run_connection();
        close_current();
        // 断线后丢弃未发出的旧消息：Host 已经没了，重放旧输入只会干扰重连后的新页面。
        // 浏览器状态仍留在 mcef_bridge 的 g_browsers 中，下一次 resync 会重新 create。
        clear_outbound_queue();
        if (g_want.load()) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
    g_running.store(false);
}

// 发送线程主体：只负责把队列里的行按 FIFO 顺序写出。
// 断线期间 mcef_ipc_send_json 会立刻返回错误，消息按原逻辑丢弃（等价旧行为，只是不再阻塞调用方）。
void tx_main() {
    while (g_tx_want.load()) {
        std::string line;
        {
            std::unique_lock<std::mutex> lock(g_queue_mutex);
            g_queue_cv.wait_for(lock, std::chrono::milliseconds(200), [] {
                return !g_out_queue.empty() || !g_tx_want.load();
            });
            if (g_out_queue.empty()) continue;
            line = std::move(g_out_queue.front());
            g_out_queue.pop_front();
            g_out_queue_bytes -= line.size();
        }
        if (!g_running.load()) continue;
        mcef_ipc_send_json(line.c_str());
    }
}

void clear_outbound_queue() {
    std::lock_guard<std::mutex> lock(g_queue_mutex);
    g_out_queue.clear();
    g_out_queue_bytes = 0;
}

} // namespace

extern "C" {

int mcef_ipc_start(const char* endpoint) {
    if (g_want.load()) {
        // 已在运行（或正在重连）：endpoint 不同则视为错误，相同直接复用。
        return endpoint && g_endpoint == endpoint ? MCEF_OK : MCEF_ERR_INVALID_ARGUMENT;
    }
    if (!endpoint || !*endpoint) return MCEF_ERR_INVALID_ARGUMENT;
    g_endpoint = endpoint;
    clear_outbound_queue();
    g_want.store(true);
    g_rx_thread = std::thread(rx_main);
    g_tx_want.store(true);
    g_tx_thread = std::thread(tx_main);
    // 首次连接可能未完成就返回；调用方可稍后用 mcef_ipc_is_running 判断。
    return MCEF_OK;
}

void mcef_ipc_stop(void) {
    g_want.store(false);
    g_tx_want.store(false);
    g_queue_cv.notify_all();
    if (g_tx_thread.joinable()) g_tx_thread.join();
    close_current();
    if (g_rx_thread.joinable()) g_rx_thread.join();
    clear_outbound_queue();
}

int mcef_ipc_enqueue(const char* json_line) {
    if (!g_tx_want.load() || !json_line) return MCEF_ERR_NOT_INITIALIZED;
    std::string line(json_line);
    if (line.empty() || line.back() != '\n') line.push_back('\n');
    if (line.size() > kMaxControlLineBytes) return MCEF_ERR_INVALID_ARGUMENT;
    {
        std::lock_guard<std::mutex> lock(g_queue_mutex);
        if (g_out_queue_bytes + line.size() > kMaxOutboundBytes) {
            return MCEF_ERR_BUFFER_TOO_SMALL;
        }
        g_out_queue_bytes += line.size();
        g_out_queue.emplace_back(std::move(line));
    }
    g_queue_cv.notify_one();
    return MCEF_OK;
}

int mcef_ipc_is_running(void) {
    return g_running.load() ? 1 : 0;
}

void mcef_ipc_set_resync_callback(mcef_ipc_resync_fn fn) {
    g_resync_cb = fn;
}

int mcef_ipc_send_json(const char* json_line) {
    if (!g_running.load() || !json_line) return MCEF_ERR_NOT_INITIALIZED;
    std::lock_guard<std::mutex> lock(g_send_mutex);
    const int fd = g_fd.load();
    if (fd < 0 || !g_running.load()) return MCEF_ERR_NOT_INITIALIZED;
    std::string line(json_line);
    if (line.size() > kMaxControlLineBytes) return MCEF_ERR_INVALID_ARGUMENT;
    if (line.empty() || line.back() != '\n') line.push_back('\n');
    const char* p = line.data();
    size_t left = line.size();
    while (left > 0) {
        ssize_t n = ::send(fd, p, left, MSG_NOSIGNAL);
        if (n <= 0) {
            if (errno == EINTR) continue;
            g_running.store(false);
            const int failed_fd = g_fd.exchange(-1);
            if (failed_fd >= 0) {
                ::shutdown(failed_fd, SHUT_RDWR);
                ::close(failed_fd);
            }
            return MCEF_ERR_INTERNAL;
        }
        p += n;
        left -= static_cast<size_t>(n);
    }
    return MCEF_OK;
}

const char* mcef_ipc_escape_json_temp(const char* text) {
    g_escape_buffer.clear();
    if (!text) return g_escape_buffer.c_str();
    for (const unsigned char c : std::string(text)) {
        switch (c) {
            case '\\': g_escape_buffer += "\\\\"; break;
            case '"': g_escape_buffer += "\\\""; break;
            case '\b': g_escape_buffer += "\\b"; break;
            case '\f': g_escape_buffer += "\\f"; break;
            case '\n': g_escape_buffer += "\\n"; break;
            case '\r': g_escape_buffer += "\\r"; break;
            case '\t': g_escape_buffer += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    g_escape_buffer += buf;
                } else {
                    g_escape_buffer += static_cast<char>(c);
                }
        }
    }
    return g_escape_buffer.c_str();
}

} // extern C
