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

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
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
 * <h3>帧驱动：本类只做一件事</h3>
 *
 * <p>整个 mod 在渲染事件里唯一的动作，就是在 {@code RenderTickEvent.START}
 * 递增一个纯 Java 帧序号（{@link MCEFRenderer#beginMinecraftFrame()}）。
 * <b>不做 JNI、不取帧、不做 GL</b>。</p>
 *
 * <p>纹理真正的上传时机是 {@code MCEFRenderer.getTextureID()} ——
 * WebDisplays 调它之后会立刻采样这张纹理，那一刻 GL 状态才是对的。
 * 在 {@code RenderTickEvent.START}（帧最开头）上传会让 MobileGlues 在
 * {@code glTexImage2D} 段错误（{@code libmobileglues.so+0x5971f0}）。</p>
 *
 * <p>帧序号的作用是限流：{@code getTextureID()} 每个 MC 帧只放行一次 pump，
 * 否则 WebDisplays 每个屏幕面、每个渲染 pass 都会触发一次
 * {@code getFrameInfo}（实测过 62258 次/帧）。</p>
 */
@Mod("mcef")
public class MCEFMod {

    private static final Logger LOGGER = LogManager.getLogger("MCEF");

    public MCEFMod() {
        LOGGER.info("MCEF (Android/Cefrium) mod loaded");
        MinecraftForge.EVENT_BUS.addListener(this::onRenderTick);
    }

    /** 只处理 START 相位；END 什么都不做。 */
    private void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            MCEFRenderer.beginMinecraftFrame();
        }
    }
}
