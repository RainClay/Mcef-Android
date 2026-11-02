package com.movtery.zalithlauncher.mcef

import android.content.Context
import android.util.Log
import com.movtery.zalithlauncher.path.createOkHttpClient
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 下载并安装 libcef.so。
 *
 * 做法：下载 cefrium-sdk 的 AAR（约 245MB），从里面只抽出
 * `jni/arm64-v8a/libcef.so`（209MB）落到私有目录，AAR 本身用完即删。
 *
 * 为什么不直接下载裸的 .so：Maven Central 只有 AAR 这个分发形式，
 * 没有单独发布 libcef.so。抽 AAR 比自建镜像可靠。
 *
 * 已经存在且大小正确时不会重复下载（CefriumNativeLib.isReady）。
 */
object CefriumDownloader {

    private const val TAG = "MCEF/CefDownload"
    private const val ENTRY = "jni/arm64-v8a/libcef.so"

    data class Progress(val downloaded: Long, val total: Long) {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    /**
     * 下载并安装。返回 true 表示安装完成、可以用了。
     *
     * 全程在 IO 线程；onProgress 回调可能从该线程触发，调用方自行切回主线程。
     */
    @Throws(Exception::class)
    suspend fun downloadAndInstall(
        context: Context,
        onProgress: (Progress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (CefriumNativeLib.isReady(context)) {
            Log.i(TAG, "libcef.so 已就绪，跳过下载")
            return@withContext true
        }

        val dir = CefriumNativeLib.dir(context)
        if (!dir.exists() && !dir.mkdirs()) throw Exception("无法创建目录 ${dir.absolutePath}")

        val aarTmp = File(dir, "cefrium-sdk.aar.part")
        val soTmp = File(dir, "libcef.so.extract")
        try {
            // 1) 下载 AAR
            val client = createOkHttpClient()
            val request = okhttp3.Request.Builder().url(CefriumNativeLib.AAR_URL).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} 下载 AAR 失败")
                val total = resp.body?.contentLength() ?: -1L
                resp.body?.byteStream()?.use { input ->
                    aarTmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            // 每 1MB 报一次，避免回调过密
                            if (done - lastReport >= 1024 * 1024) {
                                lastReport = done
                                onProgress(Progress(done, total))
                            }
                        }
                        out.flush()
                        onProgress(Progress(done, if (total > 0) total else done))
                    }
                }
            }

            // 2) 从 AAR 里抽出 libcef.so
            Log.i(TAG, "AAR 下载完成，开始解压 $ENTRY")
            var extracted = false
            ZipInputStream(aarTmp.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.name == ENTRY) {
                        soTmp.outputStream().use { out -> zis.copyTo(out, 256 * 1024) }
                        extracted = true
                        break
                    }
                    zis.closeEntry()
                }
            }
            if (!extracted) throw Exception("AAR 里找不到 $ENTRY")

            // 3) 校验大小后安装
            if (soTmp.length() != CefriumNativeLib.EXPECTED_SIZE) {
                throw Exception("解压出的 so 大小不符：${soTmp.length()} != ${CefriumNativeLib.EXPECTED_SIZE}")
            }
            CefriumNativeLib.install(context, soTmp)
            Log.i(TAG, "libcef.so 安装完成")
            true
        } finally {
            runCatching { aarTmp.delete() }
            runCatching { soTmp.delete() }
        }
    }
}
