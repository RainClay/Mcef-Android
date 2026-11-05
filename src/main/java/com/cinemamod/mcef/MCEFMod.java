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
 * <h3>帧驱动与上传时机</h3>
 *
 * <p>RenderTickEvent 只递增一个纯 Java 帧序号，<b>不做 JNI、取帧或 GL 操作</b>。
 * 纹理真正被 WebDisplays 取用时，{@code getTextureID()} 才触发一次受帧序号
 * 限流的 pump。pump 内部用 {@code peekTextureID()} 检查纹理状态，绝不回调
 * {@code getTextureID()}，防止递归。</p>
 *
 * <p>这既避免每个屏幕面/渲染 pass 重复调用 {@code getFrameInfo}，也确保 GL 上传
 * 发生在真正取样纹理的渲染阶段。RenderTickEvent 不再承担帧上传。</p>
 */
@Mod("mcef")
public class MCEFMod {

    private static final Logger LOGGER = LogManager.getLogger("MCEF");

    public MCEFMod() {
        LOGGER.info("MCEF (Android/Cefrium) mod loaded");
        // START 只递增纯 Java 帧序号；不在此事件里调用 JNI 或 GL。
        MinecraftForge.EVENT_BUS.addListener(this::onRenderTick);
    }

    private void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            MCEFRenderer.beginMinecraftFrame();
        }
    }
}
