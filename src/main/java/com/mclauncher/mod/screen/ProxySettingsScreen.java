package com.mclauncher.mod.screen;

import com.mclauncher.mod.LauncherState;
import com.mclauncher.mod.LauncherState.ProxyMode;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 代理设置子界面：直连 / 系统代理 / 自定义代理三种模式，
 * 自定义模式可配置主机与端口。配置持久化到 launcher-cache/config.properties。 */
public class ProxySettingsScreen extends Screen {
    private final Screen parent;
    private Button modeButton;
    private EditBox hostBox;
    private EditBox portBox;
    private String status = "";

    public ProxySettingsScreen(Screen parent) {
        super(Component.literal("代理设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        modeButton = Button.builder(modeLabel(), b -> {
                    LauncherState.setProxyMode(nextMode());
                    updateFields();
                })
                .bounds(cx - 100, top + 24, 200, 20).build();
        addRenderableWidget(modeButton);

        hostBox = new EditBox(this.font, cx - 100, top + 62, 148, 20, Component.literal("主机"));
        hostBox.setValue(LauncherState.getProxyHost());
        hostBox.setHint(Component.literal("127.0.0.1"));
        addRenderableWidget(hostBox);

        portBox = new EditBox(this.font, cx + 56, top + 62, 44, 20, Component.literal("端口"));
        portBox.setValue(String.valueOf(LauncherState.getProxyPort()));
        portBox.setHint(Component.literal("7897"));
        portBox.setMaxLength(5);
        addRenderableWidget(portBox);

        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(cx - 104, top + 100, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.minecraft.setScreen(parent))
                .bounds(cx + 4, top + 100, 100, 20).build());

        updateFields();
    }

    private ProxyMode nextMode() {
        ProxyMode[] modes = ProxyMode.values();
        ProxyMode cur = LauncherState.getProxyMode();
        return modes[(cur.ordinal() + 1) % modes.length];
    }

    private Component modeLabel() {
        return Component.literal("代理模式: " + LauncherState.getProxyMode().label);
    }

    private void updateFields() {
        modeButton.setMessage(modeLabel());
        boolean custom = LauncherState.getProxyMode() == ProxyMode.CUSTOM;
        hostBox.setVisible(custom);
        portBox.setVisible(custom);
    }

    private void save() {
        if (LauncherState.getProxyMode() == ProxyMode.CUSTOM) {
            String host = hostBox.getValue().trim();
            if (host.isEmpty()) {
                status = "代理主机不能为空";
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portBox.getValue().trim());
            } catch (NumberFormatException e) {
                status = "端口格式不正确";
                return;
            }
            if (port < 1 || port > 65535) {
                status = "端口范围 1-65535";
                return;
            }
            LauncherState.setProxyHost(host);
            LauncherState.setProxyPort(port);
        }
        LauncherState.save();
        Toasts.success("代理设置已保存");
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, "代理设置", cx, top - 20, 0xFFFFFFFF);
        g.text(this.font, "主机:", cx - 100, top + 68, 0xFFAAAAAA);
        g.text(this.font, "端口:", cx + 56, top + 68, 0xFFAAAAAA);
        g.centeredText(this.font,
                "网络请求将按所选模式连接；下载/登录/清单均生效",
                cx, top + 132, 0xFF888888);

        if (!status.isEmpty()) {
            g.centeredText(this.font, status, cx, top + 150, 0xFFFF5555);
        }

        Toasts.draw(g, delta);
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
