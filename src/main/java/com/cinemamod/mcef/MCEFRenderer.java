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
 * <p>CEF 提供 BGRA 帧。本实现上传时使用
 * {@code GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV}，并在 CPU 侧交换 R/B；这是当前
 * Android 驱动组合上实测可显示且颜色正确的路径。格式和驱动行为如有变化，须在
 * MobileGlues 与 gl4es 上分别实测，不可只凭 GLES 规范推断。</p>
 *
 * <h3>哪里走 RenderSystem，哪里走裸 GL</h3>
 * <p>分界线是「绑定/参数」走 MC 封装，「分配/上传」走裸 GL：</p>
 * <table border="1">
 *   <caption>调用面</caption>
 *   <tr><th>操作</th><th>用什么</th><th>为什么</th></tr>
 *   <tr><td>绑定纹理</td><td>{@code GlStateManager._bindTexture}</td>
 *       <td>同步 MC 的 GL 状态缓存与 active texture unit</td></tr>
 *   <tr><td>过滤/环绕参数</td><td>{@code RenderSystem.texParameter}</td>
 *       <td>同上，且会走 MC 的纹理参数缓存</td></tr>
 *   <tr><td>{@code glGenTextures} / {@code glTexImage2D} / {@code glDeleteTextures}</td>
 *       <td>裸 GL（{@code GL11} 静态导入）</td>
 *       <td>MC 没有对应封装；上传本身不改 MC 关心的状态</td></tr>
 * </table>
 *
 * <p>裸 {@code glBindTexture} 之所以不能用，是因为它会绕开 MC 的缓存，
 * 让缓存与实际状态不一致，MC 后续绘制就可能绑错纹理。</p>
 *
 * <h3>GL 状态卫生</h3>
 * <p>不碰 {@code GL_UNPACK_ROW_LENGTH}：CEF 的 buffer 是紧凑的，GL 默认值
 * （0 = 按纹理宽度）本来就对。设了不还原会污染 MC 所有 GUI 贴图的上传，
 * 表现是整个界面贴图错位、撕裂。</p>
 *
 * <p>上传前后都绑定回 0，不把绑定位留给 MC 后续绘制。</p>
 *
 * <h3>驱动实测</h3>
 * <p>上面的格式组合在 <b>MobileGlues</b> 上验证可用。切到 <b>gl4es</b>
 * （{@code libng_gl4es.so}）会崩在 {@code __memcpy_aarch64_simd}：它处理
 * 1280×720 这种 NPOT 大纹理上传时内部临时缓冲越界。gl4es 这条路上无解，
 * 不是本类能绕过的。</p>
 */
public class MCEFRenderer {

    private static final Logger LOGGER = LogManager.getLogger("MCEF/Renderer");

    private final boolean transparent;

    /** GL 纹理 id；0 表示尚未创建。 */
    private final int[] textureId = new int[1];

    private int texWidth;
    private int texHeight;
    /** 纹理存储是否已分配过；close() 后要清回 false。 */
    private boolean texAllocated;

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

        // 绑定走 GlStateManager._bindTexture 而非裸 glBindTexture：
        // 它会正确处理 active texture unit 并同步 MC 的 GL 状态缓存。
        // 用裸绑定时缓存没更新，后续 texParameter 可能作用到别的纹理上。
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
     * <p><b>会就地修改 buffer</b>：先做 R/B 交换，再交给 GL。调用方拿到的
     * buffer 已经是 RGBA，不要再按 BGRA 解释。</p>
     *
     * @param buffer BGRA 像素，容量至少 width*height*4
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

        // 显式把 unpack 状态设成"紧凑"再上传，完了还原。
        //
        // MobileGlues 的 glTexImage2D 会按 GL_UNPACK_ROW_LENGTH / ALIGNMENT /
        // SKIP_ROWS / SKIP_PIXELS 推算源数据的步长与跨度：
        //   row_stride = widthalign(row_px * src_size, alignment)
        //   span = start + (height-1)*row_stride + width*ss
        // 我们的 buffer 是紧凑的 width*height*4。一旦别的 mod 或 MC 自己留下
        // 非默认的 unpack 状态，驱动就按错误步长读，读到 buffer 末尾之外 ——
        // 崩溃点正是 libmobileglues 的 memcpy（寄存器里出现 1080 这种
        // 不是 1280*4 的拷贝长度）。
        //
        // 用完必须原样还原：MC 的 GUI 贴图上传依赖默认值，设了不还原会让
        // 整个界面贴图错位。
        final int prevRowLen = glGetInteger(GL_UNPACK_ROW_LENGTH);
        final int prevAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        final int prevSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
        final int prevSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        try {

        // 先交换 R/B，再走 GL_BGRA + REV 上传。三种组合的实测结果：
        //   GL_RGBA  + UNSIGNED_BYTE        → gl4es 崩在 __memcpy_aarch64_simd
        //   GL_BGRA  + REV，不交换          → 不崩，但画面红蓝反转
        //   GL_BGRA  + REV，先交换          → 正确（本实现）
        // 换成别的组合前请先在两套驱动上分别实测，不要按 GLES 规范推断。
        swapRgbaInPlace(buffer, width * height);
        buffer.position(0);

        // 尺寸没变时走 glTexSubImage2D：只更新像素，不重新分配纹理存储。
        //
        // 原来每帧都调 glTexImage2D（完整重新分配）。暂停菜单 ESC 进出后
        // GL 上下文可能被动过（EgLSurface 重建、驱动内部状态失效），
        // 此时再让驱动重新分配会让它按新尺寸重算内部行缓冲 ——
        // 崩溃点正是 libmobileglues 里 glTexImage2D 的 memcpy
        // （SIGSEGV，寄存器里 R2=1080 这类不整的拷贝长度）。
        // 尺寸没变就用 SubImage，驱动只做纯数据拷贝，不碰分配逻辑。
        final boolean resized = (width != texWidth || height != texHeight);
        if (resized || texAllocated == false) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            texWidth = width;
            texHeight = height;
            texAllocated = true;
            LOGGER.info("texture allocated {}x{}", width, height);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
        } finally {
            // 必须还原：MC 的 GUI 贴图上传依赖默认值。
            glPixelStorei(GL_UNPACK_ROW_LENGTH, prevRowLen);
            glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlignment);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, prevSkipRows);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
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

    /** 由 {@code RenderTickEvent.START} 递增的纯 Java 帧序号；事件里不做 JNI 或 GL。 */
    private static volatile long minecraftFrameId;

    /** 由 {@code MCEFMod} 在 {@code RenderTickEvent.START} 调用；只 ++，别的什么都不做。 */
    public static void beginMinecraftFrame() {
        minecraftFrameId++;
    }

    /**
     * 取 GL 纹理 id。
     *
     * <p><b>这是帧上传的唯一驱动点，不是 RenderTickEvent。</b>
     * WebDisplays 的 ScreenRenderer 调本方法后紧接着
     * {@code RenderSystem._setShaderTexture(0, texId)} 采样；此刻 GL 正处于
     * 「准备画这张纹理」的完整状态，上传最安全。改到
     * {@code RenderTickEvent.START}（帧最开头，可能还在切 FBO、用别的
     * texture unit）上传会让 MobileGlues 在 {@code glTexImage2D} 段错误。</p>
     *
     * <h3>两道限流，缺一不可</h3>
     * <ol>
     *   <li><b>按 MC 帧号去重</b>：WebDisplays 每个屏幕面、每个渲染 pass 都会调
     *       本方法，不去重就是每帧几十上百次 {@code getFrameInfo}
     *       （实测过 62258 次/帧）。</li>
     *   <li><b>pump 内部只能用 {@link #peekTextureID()}</b>：回调链是
     *       {@code getTextureID → beforeUse → MCEFBrowser.tick}，
     *       tick 里若再调 getTextureID 就是自递归
     *       （实测 -Xss2040k 下 6.2 万层、-Xss512k 下 7331 层）。</li>
     * </ol>
     */
    public int getTextureID() {
        final Runnable pump = beforeUse;
        if (pump != null && !isGamePaused() && claimPumpForFrame(minecraftFrameId)) {
            try {
                pump.run();
            } catch (Throwable t) {
                LOGGER.debug("frame pump failed", t);
            }
        }
        return textureId[0];
    }

    /**
     * 游戏暂停时不要 pump 帧。
     *
     * <p>暂停后 MC 不再正常推进渲染帧（单机是真正的暂停，多人是暂停菜单），
     * 而 WebDisplays 仍可能在被冻结的渲染 pass 里反复调用 {@code getTextureID}。
     * 此时 pump 会走到 {@code glTexImage2D} 上传纹理 —— 恰好在主线程停摆、
     * GL 上下文处于半冻结状态时操作它，实测会崩（
     * {@code libmobileglues} 里的 {@code __memcpy_aarch64_simd}）。</p>
     *
     * <p>暂停期间直接返回旧纹理：画面本来就冻着，不刷帧没有可见影响；
     * 恢复后帧号变化会自然重新放行。</p>
     */
    private static boolean isGamePaused() {
        try {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc != null && mc.isPaused();
        } catch (Throwable t) {
            // 拿不到就当作没暂停：不能因为诊断代码本身把渲染停掉。
            return false;
        }
    }

    /** 不触发 pump 的裸纹理 id。内部初始化检查专用 —— 见 {@link #getTextureID()} 的递归说明。 */
    public int peekTextureID() {
        return textureId[0];
    }

    /** 上次放行 pump 的帧号；同一帧只放行一次。 */
    private long lastPumpFrameId = Long.MIN_VALUE;

    /** 帧号守卫；synchronized 保证多渲染 pass 并发调用时也不会重复放行。 */
    private synchronized boolean claimPumpForFrame(long frameId) {
        if (lastPumpFrameId == frameId) return false;
        lastPumpFrameId = frameId;
        return true;
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
            texAllocated = false;
            painted = false;
        }
    }

    /**
     * 就地交换每像素的第 0 与第 2 字节（BGRA ↔ RGBA）。
     *
     * <p>小端序下 BGRA 四字节读成 int 是 {@code 0xAARRGGBB}，交换后为
     * {@code 0xAABBGGRR}：保留 A 与 G（{@code 0xFF00FF00}），R 左移到 B 位、
     * B 右移到 R 位。</p>
     *
     * <p>按 int 批量处理而不是逐字节，1280×720 是 92 万次循环 —— 逐字节要
     * 370 万次。这是每帧都跑的热路径。</p>
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
