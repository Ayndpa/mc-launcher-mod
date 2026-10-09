package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
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
        super(Component.literal(I18n.get("mc_launcher.proxy.title")));
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

        hostBox = new EditBox(this.font, cx - 100, top + 62, 148, 20, Component.literal(I18n.get("mc_launcher.proxy.host")));
        hostBox.setValue(LauncherState.getProxyHost());
        hostBox.setHint(Component.literal(I18n.get("mc_launcher.proxy.host_hint")));
        addRenderableWidget(hostBox);

        portBox = new EditBox(this.font, cx + 56, top + 62, 44, 20, Component.literal(I18n.get("mc_launcher.proxy.port")));
        portBox.setValue(String.valueOf(LauncherState.getProxyPort()));
        portBox.setHint(Component.literal(I18n.get("mc_launcher.proxy.port_hint")));
        portBox.setMaxLength(5);
        addRenderableWidget(portBox);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.proxy.save")), b -> save())
                .bounds(cx - 104, top + 100, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.proxy.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx + 4, top + 100, 100, 20).build());

        updateFields();
    }

    private ProxyMode nextMode() {
        ProxyMode[] modes = ProxyMode.values();
        ProxyMode cur = LauncherState.getProxyMode();
        return modes[(cur.ordinal() + 1) % modes.length];
    }

    private Component modeLabel() {
        return Component.literal(I18n.get("mc_launcher.proxy.mode", I18n.get(modeKey(LauncherState.getProxyMode()))));
    }

    private static String modeKey(ProxyMode mode) {
        return switch (mode) {
            case DIRECT -> "mc_launcher.proxy.mode_direct";
            case SYSTEM -> "mc_launcher.proxy.mode_system";
            case CUSTOM -> "mc_launcher.proxy.mode_custom";
        };
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
                status = I18n.get("mc_launcher.proxy.host_empty");
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portBox.getValue().trim());
            } catch (NumberFormatException e) {
                status = I18n.get("mc_launcher.proxy.port_invalid");
                return;
            }
            if (port < 1 || port > 65535) {
                status = I18n.get("mc_launcher.proxy.port_range");
                return;
            }
            LauncherState.setProxyHost(host);
            LauncherState.setProxyPort(port);
        }
        LauncherState.save();
        Toasts.success(I18n.get("mc_launcher.proxy.saved"));
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, I18n.get("mc_launcher.proxy.title"), cx, top - 20, 0xFFFFFFFF);
        g.text(this.font, I18n.get("mc_launcher.proxy.host_label"), cx - 100, top + 68, 0xFFAAAAAA);
        g.text(this.font, I18n.get("mc_launcher.proxy.port_label"), cx + 56, top + 68, 0xFFAAAAAA);
        g.centeredText(this.font,
                I18n.get("mc_launcher.proxy.effect_hint"),
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
