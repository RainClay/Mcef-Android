#pragma once

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define MCEF_API __declspec(dllexport)
#else
#define MCEF_API __attribute__((visibility("default")))
#endif

// libmcef.so 对外暴露的稳定 C ABI。
//
// 设计目的：让 Minecraft / LiquidBounce Mod 侧只依赖一组很薄的 native 接口，
// 不把 Cefrium、IPC、DOM 脚本、帧缓存等复杂逻辑塞进 Kotlin/Java Mod 代码里。
//
// 当前版本是“后端中立”的桥接层：
// - 负责管理 browser 句柄；
// - 负责缓存 BGRA 帧；
// - 负责缓存 DOM JSON；
// - 负责接收输入/JS/DOM 请求；
// - 等待后续 Cefrium Host 或 IPC 后端真正执行这些请求。
//
// 后续不管 Cefrium 跑在同进程，还是 Zalith Android 进程里通过 IPC 通信，
// Mod 侧都可以继续使用这一套 API，不需要大改。

// 浏览器句柄。Java/Kotlin 侧用 long 保存，不直接暴露内部对象指针。
typedef int64_t mcef_browser_t;

// 统一错误码。所有返回 int 的接口都尽量返回这些值。
typedef enum mcef_result_t {
    MCEF_OK = 0,
    MCEF_ERR_NOT_INITIALIZED = -1,
    MCEF_ERR_INVALID_ARGUMENT = -2,
    MCEF_ERR_NOT_FOUND = -3,
    MCEF_ERR_BUFFER_TOO_SMALL = -4,
    MCEF_ERR_CLOSED = -5,
    MCEF_ERR_UNSUPPORTED = -6,
    MCEF_ERR_INTERNAL = -7
} mcef_result_t;

// 输入事件类型。MC 侧鼠标/键盘事件先转成这些通用类型，再交给后端处理。
typedef enum mcef_input_type_t {
    MCEF_INPUT_MOUSE_MOVE = 0,
    MCEF_INPUT_MOUSE_DOWN = 1,
    MCEF_INPUT_MOUSE_UP = 2,
    MCEF_INPUT_MOUSE_WHEEL = 3,
    MCEF_INPUT_KEY_DOWN = 4,
    MCEF_INPUT_KEY_UP = 5,
    MCEF_INPUT_CHAR = 6
} mcef_input_type_t;

// 初始化配置。路径字段先保存下来，后续 Cefrium/IPC 后端会使用。
typedef struct mcef_init_config_t {
    const char* runtime_dir;
    const char* cache_dir;
    const char* cef_library_path;
    int enable_ipc;
    const char* ipc_endpoint;
} mcef_init_config_t;

// 帧信息。当前约定像素格式 format=1 表示 BGRA8888。
typedef struct mcef_frame_info_t {
    int width;
    int height;
    int stride;
    int format;      // 1 = BGRA8888，和 Cefrium OnPaint 输出一致
    uint64_t version;
} mcef_frame_info_t;

// 通用输入事件载体。鼠标、滚轮、按键、字符输入共用这一结构。
//
// 与 Android Cefrium Host 后端的实际能力对齐（两边必须一致）：
// - type 0/1/2 (MOVE/DOWN/UP) 转成 sendTouchEvent，只有 x/y 生效；
// - type 3 (WHEEL) 走 JS scrollBy 回退，只有 delta_x/delta_y 生效；
// - type 4/5/6 (KEY_DOWN/KEY_UP/CHAR) 后端不支持：CefriumBrowser 未暴露键盘
//   API，Host 会明确记录一次日志而不是静默丢弃；
// - button / key_code / scan_code / modifiers / codepoint 目前不生效，保留在
//   结构里是为了协议稳定，等 SDK 提供对应 API 后再接。
typedef struct mcef_input_event_t {
    int type;
    int x;
    int y;
    int button;
    int key_code;
    int scan_code;
    int modifiers;
    double delta_x;
    double delta_y;
    uint32_t codepoint;
} mcef_input_event_t;

// 初始化/关闭 libmcef。注意：这里还不等于 Cefrium.initialize(context)。
MCEF_API int mcef_init(const mcef_init_config_t* config);
MCEF_API void mcef_shutdown(void);
MCEF_API int mcef_is_initialized(void);

// 创建/销毁/控制浏览器句柄。真正的 Cefrium browser 可由 Host 按同一 id 映射。
MCEF_API mcef_browser_t mcef_create_browser(const char* url, int width, int height, float scale, int transparent);
MCEF_API int mcef_destroy_browser(mcef_browser_t browser);
MCEF_API int mcef_resize(mcef_browser_t browser, int width, int height);
MCEF_API int mcef_load_url(mcef_browser_t browser, const char* url);
MCEF_API int mcef_reload(mcef_browser_t browser, int ignore_cache);
MCEF_API int mcef_go_back(mcef_browser_t browser);
MCEF_API int mcef_go_forward(mcef_browser_t browser);
MCEF_API int mcef_stop_loading(mcef_browser_t browser);

// 输入与 JS/DOM 请求。当前先进入 native 队列/缓存，后续由 Host 或 IPC 后端执行。
MCEF_API int mcef_send_input(mcef_browser_t browser, const mcef_input_event_t* event);
MCEF_API int mcef_evaluate_javascript(mcef_browser_t browser, const char* script);
MCEF_API int mcef_request_dom_snapshot(mcef_browser_t browser);
MCEF_API int mcef_request_elements_at_point(mcef_browser_t browser, int x, int y);
MCEF_API int mcef_click_dom(mcef_browser_t browser, const char* selector);

// 读取当前缓存的帧、DOM 和 URL。MC 渲染线程主要使用 mcef_copy_frame。
MCEF_API int mcef_get_frame_info(mcef_browser_t browser, mcef_frame_info_t* out_info);
MCEF_API int mcef_copy_frame(mcef_browser_t browser, void* out_bgra, size_t out_size, mcef_frame_info_t* out_info);
MCEF_API int mcef_get_dom_json(mcef_browser_t browser, char* out_utf8, size_t out_size, size_t* required_size);
MCEF_API int mcef_get_url(mcef_browser_t browser, char* out_utf8, size_t out_size, size_t* required_size);
MCEF_API int mcef_get_last_error(char* out_utf8, size_t out_size, size_t* required_size);

// Host 侧回填接口。
//
// Android Cefrium Host 或 IPC 接收端拿到真实浏览器数据后调用这些函数：
// - onPaint(w,h,pixels) -> mcef_host_set_frame；
// - cefriumQuery / DOM 查询结果 -> mcef_host_set_dom_json；
// - 地址变化 -> mcef_host_set_url。
//
// 这样即使 libmcef.so 暂时不直接链接 libcef.so，也能作为稳定桥接层工作。
MCEF_API int mcef_host_set_frame(mcef_browser_t browser, const void* bgra, int width, int height, int stride);
MCEF_API int mcef_host_set_dom_json(mcef_browser_t browser, const char* json);
MCEF_API int mcef_host_set_url(mcef_browser_t browser, const char* url);

#ifdef __cplusplus
}
#endif
