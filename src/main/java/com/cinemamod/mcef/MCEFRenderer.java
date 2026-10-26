package com.cinemamod.mcef;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * MCEFRenderer —— 把 CEF 的帧上传成一张 GL 纹理，供 MC 渲染。
 *
 * <p>上游 CinemaMod 的实现用 {@code glTexImage2D(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)}。
 * <b>在 Android + MobileGlues 上这会崩</b>（hs_err: libmobileglues glTexImage2D+0x9c），
 * 所以这里改成 GLES 原生的 {@code GL_RGBA + GL_UNSIGNED_BYTE}，并在 CPU 侧把
 * BGRA 交换成 RGBA。</p>
 *
 * <h3>为什么不直接用 RenderSystem.bindTexture</h3>
 * <p>MC 的 {@code com.mojang.blaze3d.systems.RenderSystem} 在 26.1.2 里是 Java 25 的
 * class（version 69），而本工程在只有 Java 21 的环境下也要能编译，所以这里只用
 * LWJGL 的原始 GL 调用（{@code org.lwjgl.opengl.GL11}），不碰 blaze3d。
 * 好处是这套代码对 MC 版本完全不敏感 —— 升级 MC 不用改这里。</p>
 *
 * <h3>GL 状态卫生</h3>
 * <p>上传前后都不碰 {@code GL_UNPACK_ROW_LENGTH}：CEF 的 buffer 是紧凑的，
 * GL 默认值（0 = 按纹理宽度）本来就对。设了不还原会污染 MC 自己所有 GUI 贴图的
 * 上传，表现是整个界面贴图错位、撕裂。</p>
 *
 * <p>绑定/解绑：上传前 bind 我们的纹理，上传完 bind(0) 交还状态，
 * 不让 MC 的后续绘制以为绑着的是它的纹理。</p>
 */
public class MCEFRenderer {

    private static final String TAG = "MCEF/Renderer";

    private final boolean transparent;

    /** GL 纹理 id；0 表示尚未创建。 */
    private final int[] textureId = new int[1];

    private int texWidth;
    private int texHeight;

    /** 是否已至少上传过一帧。 */
    private volatile boolean painted = false;

    public MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
    }

    /**
     * 创建 GL 纹理。必须在渲染线程调用（当前 GL 上下文有效）。
     *
     * <p>幂等：重复调用直接返回，避免泄漏纹理 id。</p>
     */
    public void initialize() {
        if (textureId[0] != 0) return;

        textureId[0] = glGenTextures();
        if (textureId[0] == 0) {
            Log.e(TAG, "glGenTextures returned 0");
            return;
        }

        glBindTexture(GL_TEXTURE_2D, textureId[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // CLAMP_TO_EDGE：页面尺寸通常正好等于纹理尺寸，但缩放时 REPEAT 会让
        // 边缘像素串到另一侧，必须夹紧。
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        Log.i(TAG, "texture created id=" + textureId[0]);
    }

    /**
     * 上传一帧。必须在渲染线程调用。
     *
     * @param buffer BGRA 像素，尺寸 width*height*4
     */
    public void onPaint(ByteBuffer buffer, int width, int height) {
        if (buffer == null || width <= 0 || height <= 0) return;
        if (textureId[0] == 0) {
            Log.w(TAG, "onPaint before initialize, dropping frame");
            return;
        }

        // 尺寸变化时重建存储；其余帧原地更新。每帧全量 texImage2D 会重分配
        // 十几 MB，在移动 GPU 上直接把渲染线程拖住。
        boolean recreated = (width != texWidth || height != texHeight);

        glBindTexture(GL_TEXTURE_2D, textureId[0]);

        if (recreated) {
            // 先分配空存储，后面统一走 subImage（只需一份像素处理逻辑）。
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            texWidth = width;
            texHeight = height;
            Log.i(TAG, "texture resized to " + width + "x" + height);
        }

        // CEF 给的是 BGRA；交换成 RGBA 后按 GLES 原生组合上传。
        swapRgbaInPlace(buffer, width * height);

        buffer.position(0);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        glBindTexture(GL_TEXTURE_2D, 0);

        painted = true;
    }

    /** GL 纹理 id；0 表示还没初始化。 */
    public int getTextureID() {
        return textureId[0];
    }

    /** 是否已有帧。 */
    public boolean isPainted() {
        return painted;
    }

    public int getWidth() {
        return texWidth;
    }

    public int getHeight() {
        return texHeight;
    }

    /** 删除纹理。渲染线程调用。 */
    public void close() {
        if (textureId[0] != 0) {
            glDeleteTextures(textureId[0]);
            textureId[0] = 0;
            texWidth = 0;
            texHeight = 0;
            painted = false;
        }
    }

    /**
     * 就地交换每像素的第 0 与第 2 字节（BGRA ↔ RGBA）。
     *
     * <p>按 int 批量处理：小端序下 BGRA 四字节读成 int 是 0xAARRGGBB，
     * 交换后为 0xAABBGGRR。88 万像素约 2~4ms，比逐字节快一个数量级。</p>
     *
     * <p>用 {@code Math.min} 兜住 pixelCount 与 buffer 实际容量的差异 ——
     * CEF 的 buffer 偶尔带 padding，越界会崩在 memcpy 里。</p>
     */
    private static void swapRgbaInPlace(ByteBuffer buffer, int pixelCount) {
        IntBuffer ints = buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int n = Math.min(pixelCount, ints.limit());
        for (int i = 0; i < n; i++) {
            int v = ints.get(i);
            ints.put(i, (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF));
        }
    }
}
