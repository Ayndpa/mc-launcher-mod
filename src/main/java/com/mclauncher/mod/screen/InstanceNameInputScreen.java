package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;

/**
 * 输入目标实例名称，把版本下载到 instances/<名称>/。
 * <ul>
 *   <li>预填版本 id（如 26.3），同名冲突时下载器自动追加 (1) (2)…</li>
 *   <li>留空 = 下载到当前游戏所在实例（instances/default）</li>
 *   <li>输入已存在的实例名 = 追加到该实例</li>
 * </ul>
 */
public class InstanceNameInputScreen extends Screen {
    private final Screen parent;
    private final String preset;
    private final BiConsumer<Screen, String> onConfirm; // (返回的父页面, 实例名或null)
    private EditBox nameBox;

    public InstanceNameInputScreen(Screen parent, String preset, BiConsumer<Screen, String> onConfirm) {
        super(Component.literal(I18n.get("mc_launcher.instance.title")));
        this.parent = parent;
        this.preset = preset;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        nameBox = new EditBox(this.font, cx - 100, top + 30, 200, 20,
                Component.literal(I18n.get("mc_launcher.instance.box_hint")));
        nameBox.setHint(Component.literal(I18n.get("mc_launcher.instance.hint")));
        nameBox.setMaxLength(48);
        if (preset != null && !preset.isBlank()) {
            nameBox.setValue(preset);
        }
        addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.instance.ok")), b -> confirm())
                .bounds(cx - 104, top + 62, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.instance.cancel")), b ->
                        this.minecraft.setScreen(parent))
                .bounds(cx + 4, top + 62, 100, 20).build());
    }

    private void confirm() {
        String s = nameBox.getValue().trim();
        // 非法字符替换为下划线（与 Rust 侧 instance_name 一致）
        s = s.replace('/', '_').replace('\\', '_');
        onConfirm.accept(parent, s.isEmpty() ? null : s);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);
        g.centeredText(this.font, I18n.get("mc_launcher.instance.prompt"), cx, top, 0xFFFFFFFF);
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
