package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;

import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 启动器专用的辅助功能界面：只保留与主菜单场景相关的开关。
 * 按键控制、矿车视角、视角摇晃、屏幕效果、FOV/黑暗/受伤效果、附魔光效、
 * 高对比度轮廓、聊天相关（不透明度/行距/延迟/文本背景）等需要进入世界
 * 或与游戏会话绑定的入口全部删除。
 */
public class LauncherAccessibilityScreen extends Screen {
    private final Screen parent;
    private final Options options;

    public LauncherAccessibilityScreen(Screen parent, Options options) {
        super(Component.translatable("options.accessibility"));
        this.parent = parent;
        this.options = options;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        // 仅保留主菜单下有意义的选项
        net.minecraft.client.OptionInstance<?>[][] rows = {
                {options.narrator()},
                {options.menuBackgroundBlurriness()},
                {options.notificationDisplayTime()},
                {options.darkMojangStudiosBackground()},
                {options.panoramaSpeed()},
                {options.narratorHotkey()},
        };

        int halfW = 150;
        int gap = 6;
        int rowW = halfW * 2 + gap;
        int y = top + 20;
        for (net.minecraft.client.OptionInstance<?>[] row : rows) {
            int x = cx - rowW / 2;
            for (net.minecraft.client.OptionInstance<?> opt : row) {
                int w = row.length == 1 ? rowW : halfW;
                AbstractWidget wgt = opt.createButton(this.options, x, y, w);
                addRenderableWidget(wgt);
                x += w + gap;
            }
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.back")), b ->
                        this.minecraft.setScreen(parent))
                .bounds(cx - halfW, y + 8, halfW * 2, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);
        g.centeredText(this.font, this.title, cx, top - 4, 0xFFFFFFFF);
        Toasts.draw(g, delta);
    }

    @Override
    public void removed() {
        this.options.save();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
