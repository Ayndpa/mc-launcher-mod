package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** 手动输入 Java 路径的简易输入界面 */
public class JavaPathInputScreen extends Screen {
    private final Screen parent;
    private final Consumer<Path> onConfirm;
    private EditBox pathBox;

    public JavaPathInputScreen(Screen parent, Consumer<Path> onConfirm) {
        super(Component.literal(I18n.get("mc_launcher.java_path.title")));
        this.parent = parent;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        pathBox = new EditBox(this.font, cx - 150, top + 30, 300, 20, Component.literal(I18n.get("mc_launcher.java_path.box_hint")));
        pathBox.setHint(Component.literal(I18n.get("mc_launcher.java_path.placeholder")));
        addRenderableWidget(pathBox);
        this.setInitialFocus(pathBox);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java_path.ok")), b -> confirm())
                .bounds(cx - 104, top + 62, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java_path.cancel")), b -> this.minecraft.setScreen(parent))
                .bounds(cx + 4, top + 62, 100, 20).build());
    }

    private void confirm() {
        String s = pathBox.getValue().trim();
        Path p = Path.of(s);
        if (Files.isDirectory(p)) {
            Path exe = p.resolve("bin").resolve("java.exe");
            onConfirm.accept(Files.exists(exe) ? exe.getParent().getParent() : p);
        } else if (Files.isRegularFile(p)) {
            onConfirm.accept(p.getParent().getParent());
        } else {
            onConfirm.accept(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);
        g.centeredText(this.font, I18n.get("mc_launcher.java_path.prompt"), cx, top, 0xFFFFFFFF);
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
