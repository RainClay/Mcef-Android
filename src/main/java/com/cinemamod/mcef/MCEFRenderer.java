package com.cinemamod.mcef;

import com.mojang.blaze3d.systems.RenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * MCEFRenderer —— 把 CEF 的帧上传成一张 GL 纹理，供 MC 渲染。
 *
 * <p>上游 CinemaMod 的实现用 {@code glTexImage2D(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)}。
 * <b>在 Android + MobileGlues 上这会崩</b>（hs_err: libmobileglues glTexImage2D+0x9c），
 * 所以这里改成 GLES 原生的 {@code GL_RGBA + GL_UNSIGNED_BYTE}，并在 CPU 侧把
 * BGRA 交换成 RGBA。</p>
 *
 * <h3>为什么走 RenderSystem 而不是裸 GL</h3>
 * <p>MC 1.20.1 的 {@code RenderSystem} 提供 {@code bindTexture} / {@code texParameter}
 * 等封装，它们会同时维护 MC 自己的 GL 状态缓存。绕过它直接用 {@code glBindTexture}
 * 会让缓存与实际状态不一致，MC 后续绘制就可能绑错纹理。
 * （Android 版 mcef-android 里因为不能依赖 blaze3d 才用了裸 GL，这里可以正常用。）</p>
 *
 * <h3>GL 状态卫生</h3>
 * <p>不碰 {@code GL_UNPACK_ROW_LENGTH}：CEF 的 buffer 是紧凑的，GL 默认值
 * （0 = 按纹理宽度）本来就对。设了不还原会污染 MC 所有 GUI 贴图的上传，
 * 表现是整个界面贴图错位、撕裂。</p>
 */
public class MCEFRenderer {

    private static final Logger LOGGER = LogManager.getLogger("MCEF/Renderer");

    private final boolean transparent;

    /** GL 纹理 id；0 表示尚未创建。 */
    private final int[] textureId = new int[1];

    private int texWidth;
    private int texHeight;

    private volatile boolean painted = false;

    /** 累计上传次数；用于定位「崩在第几次」这类规律。 */
    private volatile int uploadCount = 0;

    public MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
    }

    /**
     * 创建 GL 纹理。必须在渲染线程调用。
     */
    public void initialize() {
        if (textureId[0] != 0) return;

        textureId[0] = glGenTextures();
        if (textureId[0] == 0) {
            LOGGER.error("glGenTextures returned 0");
            return;
        }

        // bindTextureForSetup 是 MC 为「纹理设置」提供的绑定入口，
        // 它会正确处理 active texture unit 与 gl4es 的状态同步；
        // 用普通 bindTexture 时 gl4es 内部状态可能没更新，导致后续上传越界。
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(textureId[0]);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);

        LOGGER.info("texture created id={}", textureId[0]);
    }

    /**
     * 上传一帧。必须在渲染线程调用。
     *
     * @param buffer BGRA 像素，尺寸 width*height*4
     */
    public void onPaint(ByteBuffer buffer, int width, int height) {
        if (buffer == null || width <= 0 || height <= 0) return;
        if (textureId[0] == 0) {
            // 正常情况下 tick() 会先 initialize()，走到这里说明调用方顺序错了。
            // 用 debug 级别避免刷屏（每帧一条会淹掉日志）。
            LOGGER.debug("onPaint before initialize, dropping frame");
            return;
        }

        // 防御：GL 会按 width*height*4 读，buffer 不够就会越界崩在 memcpy 里。
        final int need = width * height * 4;
        if (buffer.remaining() < need) {
            LOGGER.warn("frame too small: need={} remaining={}", need, buffer.remaining());
            return;
        }

        com.mojang.blaze3d.platform.GlStateManager._bindTexture(textureId[0]);

        // 上传格式用 GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV —— 上游 mcef 的原始组合。
        //
        // 为什么不换 GL_RGBA + GL_UNSIGNED_BYTE（GLES 原生）：在 MobileGlues /
        // gl4es 这条路上，那个组合会崩在驱动内部 memcpy：
        //   si_signo: 11 (SIGSEGV); R2 = 0x1400 (= 1280*4, 正好一行)
        //   __memcpy_aarch64_simd ← glTexImage2D / glTexSubImage2D ← onPaint
        // 源地址有效、崩的是驱动内部的拷贝目标 —— 驱动实现问题，换 API 没用。
        //
        // GL_BGRA + INT_8_8_8_8_REV 是唯一不崩的上传组合。
        //
        // 但实测颜色是反的（红蓝互换）—— 说明这条路上驱动并没有按 GL_BGRA
        // 的语义处理，而是当 GL_RGBA 读了。CEF 给的是 [B][G][R][A]，
        // 按 RGBA 读就变成 R=B、B=R。
        //
        // 所以在 CPU 侧先把字节换成 [R][G][B][A]，让「按 RGBA 读」的结果正好
        // 正确。格式参数仍写 GL_BGRA，是为了继续走不崩的那条路径。
        swapRgbaInPlace(buffer, width * height);
        buffer.position(0);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);

        if (width != texWidth || height != texHeight) {
            texWidth = width;
            texHeight = height;
            LOGGER.info("texture sized to {}x{}", width, height);
        }

        com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);

        painted = true;
        uploadCount++;
        // 前 5 次上传打日志：如果崩在第 N 次，能立刻看出规律
        // （例如"每次都是第 2 次崩"→ 重复 texImage2D 的问题）。
        if (uploadCount <= 5 || uploadCount % 300 == 0) {
            LOGGER.info("frame upload #{}, {}x{}, buffer={}",
                    uploadCount, width, height, buffer.remaining());
        }
    }

    /**
     * 取 GL 纹理 id。
     *
     * <p><b>这里是帧上传的驱动点</b>，不是 RenderTickEvent。</p>
     *
     * <p>原因：WebDisplays 的 ScreenRenderer 渲染屏幕方块时会调
     * {@code getRenderer().getTextureID()}，然后立刻
     * {@code RenderSystem._setShaderTexture(0, texId)} 采样 —— 调这个方法的
     * 时刻，GL 正处于「准备画这张纹理」的完整状态。</p>
     *
     * <p>之前改用 RenderTickEvent.START 上传，那是帧的最开始，MC 可能还在切
     * FBO、用别的 texture unit，结果在 MobileGlues 上 glTexImage2D 直接段错误
     * （libmobileglues+0x5971f0）。</p>
     */
    public int getTextureID() {
        final Runnable pump = beforeUse;
        if (pump != null) {
            try {
                pump.run();
            } catch (Throwable t) {
                LOGGER.debug("frame pump failed", t);
            }
        }
        return textureId[0];
    }

    /** 由 MCEFBrowser 注入：纹理被取用前同步最新帧。 */
    private volatile Runnable beforeUse;

    public void setBeforeUse(Runnable beforeUse) {
        this.beforeUse = beforeUse;
    }

    public boolean isPainted() {
        return painted;
    }

    public int getWidth() {
        return texWidth;
    }

    public int getHeight() {
        return texHeight;
    }

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
     * <p>小端序下 BGRA 四字节读成 int 是 0xAARRGGBB，交换后为 0xAABBGGRR。</p>
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
