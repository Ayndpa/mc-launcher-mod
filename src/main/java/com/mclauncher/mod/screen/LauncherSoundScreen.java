package com.mclauncher.mod.screen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;

/**
 * 启动器专用的音乐和声音界面：只保留主菜单场景有意义的选项——
 * 主音量、音乐音量、复述功能/语音、音频设备、定向音频。
 * 唱片机、天气、方块、生物、玩家、环境等音源只有进入世界后才会发声，
 * 隐藏式字幕等与世界会话绑定的选项删除。
 * <p>
 * 继承原版 {@link SoundOptionsScreen} 仅为了 Mixin 的 NEW 重定向类型兼容
 * （AccessibilityOnboardingScreen 里 {@code new SoundOptionsScreen(...)} 被
 * 重定向为本类实例）；布局绘制完全自绘，未使用父级的 OptionsList。
 */
public class LauncherSoundScreen extends SoundOptionsScreen {
    private final Screen returnTo;
    private final Options options;

    public LauncherSoundScreen(Screen returnTo, Options options) {
        super(returnTo, options);
        this.returnTo = returnTo;
        this.options = options;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);
        int w = 310;
        int gap = 4;
        int y = top + 10;

        // 主音量
        addRenderableWidget(this.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER)
                .createButton(this.options, cx - w / 2, y, w));
        y += 24 + gap;

        // 音乐（主菜单背景音乐就是 MUSIC 音源）
        addRenderableWidget(this.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC)
                .createButton(this.options, cx - w / 2, y, w));
        y += 24 + gap;

        // 复述功能/语音（旁白用户需要）
        addRenderableWidget(this.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.VOICE)
                .createButton(this.options, cx - w / 2, y, w));
        y += 24 + gap * 2;

        // 音频设备
        addRenderableWidget(this.options.soundDevice().createButton(this.options, cx - w / 2, y, w));
        y += 24 + gap;

        // 定向音频
        addRenderableWidget(this.options.directionalAudio().createButton(this.options, cx - w / 2, y, w));
        y += 24 + gap * 2;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b ->
                        this.minecraft.setScreen(returnTo))
                .bounds(cx - w / 2, y, w, 20)
                .build());
    }

    @Override
    protected void addOptions() {
        // 布局完全自绘，不使用父类的 OptionsList
    }

    @Override
    public void removed() {
        this.options.save();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(this.font, this.title, this.width / 2, Math.max(20, this.height / 4 - 20), 0xFFFFFFFF);
        Toasts.draw(g, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(returnTo);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
