package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 输入离线用户名，新增离线档案 */
public class OfflineNameInputScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> onConfirm;
    private EditBox nameBox;

    public OfflineNameInputScreen(Screen parent, Consumer<String> onConfirm) {
        super(Component.literal(I18n.get("mc_launcher.offline.title")));
        this.parent = parent;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        nameBox = new EditBox(this.font, cx - 100, top + 30, 200, 20,
                Component.literal(I18n.get("mc_launcher.offline.box_hint")));
        nameBox.setHint(Component.literal(I18n.get("mc_launcher.offline.placeholder")));
        nameBox.setMaxLength(16);
        addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.offline.ok")), b -> confirm())
                .bounds(cx - 104, top + 62, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.offline.cancel")), b ->
                        this.minecraft.setScreen(parent))
                .bounds(cx + 4, top + 62, 100, 20).build());
    }

    private void confirm() {
        String s = nameBox.getValue().trim();
        onConfirm.accept(s.isEmpty() ? null : s);
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);
        g.centeredText(this.font, I18n.get("mc_launcher.offline.prompt"), cx, top, 0xFFFFFFFF);
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
