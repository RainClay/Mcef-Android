package com.cinemamod.mcef;

import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 的 mod 入口类。
 *
 * <p><b>为什么必须有这个类</b>：Forge 不只看 {@code mods.toml}，它还要在 jar 里找到
 * 带 {@link Mod} 注解的类，否则直接 FATAL：
 * {@code constructed 0 mods: [], but had 1 mods specified: [mcef]}。</p>
 *
 * <h3>为什么这里不挂任何渲染事件</h3>
 *
 * <p>帧上传的驱动点是 {@code MCEFRenderer.getTextureID()}，不是 RenderTickEvent。</p>
 *
 * <p>试过 RenderTickEvent.START：那是帧的最开始，MC 可能还在切 FBO、用别的
 * texture unit，此时调 glTexImage2D 在 MobileGlues 上会直接段错误
 * （libmobileglues+0x5971f0）。</p>
 *
 * <p>改成在 getTextureID() 里按需上传：WebDisplays 的 ScreenRenderer 调用它
 * 之后会立刻 RenderSystem._setShaderTexture(0, texId) 采样，那一刻 GL 正处于
 * 「准备画这张纹理」的完整状态，最安全。</p>
 */
@Mod("mcef")
public class MCEFMod {

    private static final Logger LOGGER = LogManager.getLogger("MCEF");

    public MCEFMod() {
        LOGGER.info("MCEF (Android/Cefrium) mod loaded");
    }
}
