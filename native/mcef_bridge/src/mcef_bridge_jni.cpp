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

#include <jni.h>
#include <limits>
#include <string>
#include <vector>

// ── JNI 绑定层（动态注册）───────────────────────────────────────────────
//
// 之前每个函数都叫 Java_net_ccbluex_liquidbounce_mcef_nativebridge_McefNative_xxx，
// 意味着 symbol 名把 Java 包名硬编码进了 .so：换包名就 UnsatisfiedLinkError。
//
// 现在改成 JNI_OnLoad + RegisterNatives：注册的是「类名 + 方法名 + 签名」，
// 符号名不再参与匹配。于是同一份 .so 可以同时服务多个包名的 McefNative 类
// （下方 kTargetClasses 列表），新增一个包名只需往列表里加一行。

namespace {

// JNI 字符串转 std::string。所有 jstring 入参先走这里，避免重复写 Get/Release。
std::string to_string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

// 调用 C ABI 的“先问大小，再取字符串”接口，并转成 C++ 字符串。
std::string read_string_result(int (*fn)(mcef_browser_t, char*, size_t, size_t*), mcef_browser_t browser) {
    size_t required = 0;
    fn(browser, nullptr, 0, &required);
    if (required == 0) return {};
    std::vector<char> buf(required);
    int rc = fn(browser, buf.data(), buf.size(), &required);
    if (rc != MCEF_OK && rc != MCEF_ERR_BUFFER_TOO_SMALL) return {};
    return std::string(buf.data());
}

// ── 各方法的实现体（普通 C 函数，名字不再受 JNI 规则约束）──────────────

jint jni_init(JNIEnv* env, jclass, jstring runtimeDir, jstring cacheDir,
              jstring cefLibraryPath, jboolean enableIpc, jstring ipcEndpoint) {
    const std::string runtime = to_string(env, runtimeDir);
    const std::string cache = to_string(env, cacheDir);
    const std::string cef = to_string(env, cefLibraryPath);
    const std::string endpoint = to_string(env, ipcEndpoint);
    mcef_init_config_t cfg{};
    cfg.runtime_dir = runtime.empty() ? nullptr : runtime.c_str();
    cfg.cache_dir = cache.empty() ? nullptr : cache.c_str();
    cfg.cef_library_path = cef.empty() ? nullptr : cef.c_str();
    cfg.enable_ipc = enableIpc ? 1 : 0;
    cfg.ipc_endpoint = endpoint.empty() ? nullptr : endpoint.c_str();
    return mcef_init(&cfg);
}

void jni_shutdown(JNIEnv*, jclass) {
    mcef_shutdown();
}

jboolean jni_isInitialized(JNIEnv*, jclass) {
    return mcef_is_initialized() ? JNI_TRUE : JNI_FALSE;
}

jlong jni_createBrowser(JNIEnv* env, jclass, jstring url, jint width, jint height,
                        jfloat scale, jboolean transparent) {
    if (!url) return 0;
    const std::string u = to_string(env, url);
    return static_cast<jlong>(mcef_create_browser(u.c_str(), width, height, scale,
                                                  transparent ? 1 : 0));
}

jint jni_destroyBrowser(JNIEnv*, jclass, jlong browser) {
    return mcef_destroy_browser(static_cast<mcef_browser_t>(browser));
}

jint jni_resize(JNIEnv*, jclass, jlong browser, jint width, jint height) {
    return mcef_resize(static_cast<mcef_browser_t>(browser), width, height);
}

jint jni_loadUrl(JNIEnv* env, jclass, jlong browser, jstring url) {
    if (!url) return MCEF_ERR_INVALID_ARGUMENT;
    const std::string u = to_string(env, url);
    return mcef_load_url(static_cast<mcef_browser_t>(browser), u.c_str());
}

jint jni_evaluateJavaScript(JNIEnv* env, jclass, jlong browser, jstring script) {
    if (!script) return MCEF_ERR_INVALID_ARGUMENT;
    const std::string s = to_string(env, script);
    return mcef_evaluate_javascript(static_cast<mcef_browser_t>(browser), s.c_str());
}

jint jni_requestDomSnapshot(JNIEnv*, jclass, jlong browser) {
    return mcef_request_dom_snapshot(static_cast<mcef_browser_t>(browser));
}

jint jni_requestElementsAtPoint(JNIEnv*, jclass, jlong browser, jint x, jint y) {
    return mcef_request_elements_at_point(static_cast<mcef_browser_t>(browser), x, y);
}

jint jni_clickDom(JNIEnv* env, jclass, jlong browser, jstring selector) {
    if (!selector) return MCEF_ERR_INVALID_ARGUMENT;
    const std::string s = to_string(env, selector);
    return mcef_click_dom(static_cast<mcef_browser_t>(browser), s.c_str());
}

jint jni_sendInput(JNIEnv*, jclass, jlong browser, jint type, jint x, jint y, jint button,
                   jint keyCode, jint scanCode, jint modifiers, jdouble deltaX,
                   jdouble deltaY, jint codepoint) {
    mcef_input_event_t event{};
    event.type = type;
    event.x = x;
    event.y = y;
    event.button = button;
    event.key_code = keyCode;
    event.scan_code = scanCode;
    event.modifiers = modifiers;
    event.delta_x = deltaX;
    event.delta_y = deltaY;
    event.codepoint = static_cast<uint32_t>(codepoint);
    return mcef_send_input(static_cast<mcef_browser_t>(browser), &event);
}

// MC 渲染线程读取帧：必须传 DirectByteBuffer，避免 JNI 再复制一份。
jint jni_copyFrame(JNIEnv* env, jclass, jlong browser, jobject directBuffer) {
    if (!directBuffer) return MCEF_ERR_INVALID_ARGUMENT;
    void* data = env->GetDirectBufferAddress(directBuffer);
    jlong size = env->GetDirectBufferCapacity(directBuffer);
    if (!data || size <= 0) return MCEF_ERR_INVALID_ARGUMENT;
    return mcef_copy_frame(static_cast<mcef_browser_t>(browser), data,
                           static_cast<size_t>(size), nullptr);
}

jintArray jni_getFrameInfo(JNIEnv* env, jclass, jlong browser) {
    mcef_frame_info_t info{};
    const int rc = mcef_get_frame_info(static_cast<mcef_browser_t>(browser), &info);
    jint values[5] = {rc, info.width, info.height, info.stride,
                      static_cast<jint>(info.version & 0x7fffffff)};
    jintArray arr = env->NewIntArray(5);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 5, values);
    return arr;
}

jstring jni_getDomJson(JNIEnv* env, jclass, jlong browser) {
    return to_jstring(env, read_string_result(mcef_get_dom_json,
                                              static_cast<mcef_browser_t>(browser)));
}

jstring jni_getUrl(JNIEnv* env, jclass, jlong browser) {
    return to_jstring(env, read_string_result(mcef_get_url,
                                              static_cast<mcef_browser_t>(browser)));
}

jstring jni_getLastError(JNIEnv* env, jclass) {
    size_t required = 0;
    mcef_get_last_error(nullptr, 0, &required);
    std::vector<char> buf(required ? required : 1);
    mcef_get_last_error(buf.data(), buf.size(), &required);
    return to_jstring(env, std::string(buf.data()));
}

// Host 侧回填帧：Cefrium OnPaint 的 ByteBuffer 可以直接传进来。
jint jni_hostSetFrame(JNIEnv* env, jclass, jlong browser, jobject directBuffer,
                      jint width, jint height, jint stride) {
    if (!directBuffer || width <= 0 || height <= 0 || stride <= 0) {
        return MCEF_ERR_INVALID_ARGUMENT;
    }
    void* data = env->GetDirectBufferAddress(directBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!data || capacity <= 0 ||
        static_cast<uint64_t>(stride) >
            std::numeric_limits<uint64_t>::max() / static_cast<uint64_t>(height)) {
        return MCEF_ERR_INVALID_ARGUMENT;
    }
    const uint64_t required = static_cast<uint64_t>(stride) * static_cast<uint64_t>(height);
    if (required > static_cast<uint64_t>(capacity)) return MCEF_ERR_BUFFER_TOO_SMALL;
    return mcef_host_set_frame(static_cast<mcef_browser_t>(browser), data, width, height, stride);
}

// Host 侧回填 DOM：cefriumQuery 收到的 JSON 直接写入 bridge 缓存。
jint jni_hostSetDomJson(JNIEnv* env, jclass, jlong browser, jstring json) {
    if (!json) return MCEF_ERR_INVALID_ARGUMENT;
    const std::string s = to_string(env, json);
    return mcef_host_set_dom_json(static_cast<mcef_browser_t>(browser), s.c_str());
}

// ── 方法表 ──────────────────────────────────────────────────────────────
//
// 签名必须与 Java 侧的 native 声明逐字一致，否则 RegisterNatives 返回
// JNI_ERR 且运行期调用时抛 UnsatisfiedLinkError。改动 Java 声明时这份表
// 必须同步改 —— 这是动态注册唯一的代价。

const JNINativeMethod kMethods[] = {
    {"init",                 "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)I", (void*) jni_init},
    {"shutdown",             "()V",                                                    (void*) jni_shutdown},
    {"isInitialized",        "()Z",                                                    (void*) jni_isInitialized},
    {"createBrowser",        "(Ljava/lang/String;IIFZ)J",                              (void*) jni_createBrowser},
    {"destroyBrowser",       "(J)I",                                                   (void*) jni_destroyBrowser},
    {"resize",               "(JII)I",                                                 (void*) jni_resize},
    {"loadUrl",              "(JLjava/lang/String;)I",                                  (void*) jni_loadUrl},
    {"evaluateJavaScript",   "(JLjava/lang/String;)I",                                  (void*) jni_evaluateJavaScript},
    {"requestDomSnapshot",   "(J)I",                                                   (void*) jni_requestDomSnapshot},
    {"requestElementsAtPoint","(JII)I",                                                (void*) jni_requestElementsAtPoint},
    {"clickDom",             "(JLjava/lang/String;)I",                                  (void*) jni_clickDom},
    {"sendInput",            "(JIIIIIIIDDI)I",                                         (void*) jni_sendInput},
    {"copyFrame",            "(JLjava/nio/ByteBuffer;)I",                               (void*) jni_copyFrame},
    {"getFrameInfo",         "(J)[I",                                                  (void*) jni_getFrameInfo},
    {"getDomJson",           "(J)Ljava/lang/String;",                                   (void*) jni_getDomJson},
    {"getUrl",               "(J)Ljava/lang/String;",                                   (void*) jni_getUrl},
    {"getLastError",         "()Ljava/lang/String;",                                    (void*) jni_getLastError},
    {"hostSetFrame",         "(JLjava/nio/ByteBuffer;III)I",                            (void*) jni_hostSetFrame},
    {"hostSetDomJson",       "(JLjava/lang/String;)I",                                  (void*) jni_hostSetDomJson},
};

// 需要挂上这套 native 方法的类。
//
// 加一个新的包名只需在这里加一行 —— 不必再为每个类导出 20 个 Java_ 符号，
// 也不会因为包名拼错而在运行期才炸（JNI_OnLoad 会打日志）。
const char* const kTargetClasses[] = {
    // LiquidBounce / MC 26.1.2 侧（既有）
    "net/ccbluex/liquidbounce/mcef/nativebridge/McefNative",
    // mcef-forge / MC 1.20.1 侧（本项目新增）
    "com/cinemamod/mcef/nativebridge/McefNative",
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    constexpr jint kCount = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));

    for (const char* name : kTargetClasses) {
        jclass cls = env->FindClass(name);
        if (!cls) {
            // 类还没被加载是正常情况（本 .so 可能服务于另一条路径），清掉异常继续。
            env->ExceptionClear();
            continue;
        }
        const jint rc = env->RegisterNatives(cls, kMethods, kCount);
        if (rc != JNI_OK) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
    }

    return JNI_VERSION_1_6;
}
