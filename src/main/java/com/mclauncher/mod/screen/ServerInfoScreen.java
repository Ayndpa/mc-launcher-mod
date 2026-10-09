package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;

import java.io.IOException;
import java.util.List;

/**
 * 服务器信息界面：只读展示一个服务器条目的全部已知信息（名称、地址、状态、版本、协议号、
 * 延迟、在线人数、MOTD、在线玩家列表与服务器图标）。
 * 本界面只提供“返回”按钮与 ESC 关闭，不提供任何加入服务器或编辑条目的入口 ——
 * 毕竟这是个启动器，不是真的能进去玩。
 */
public class ServerInfoScreen extends Screen {
    private static final int MAX_SHOWN_PLAYERS = 10;
    private static final int ICON_SIZE = 32;

    private final Screen parent;
    private final ServerData data;
    private FaviconTexture icon;
    private boolean iconAvailable;

    public ServerInfoScreen(Screen parent, ServerData data) {
        super(Component.literal(I18n.get("mc_launcher.server_info.title")));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.server_info.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx - 50, this.height - 44, 100, 20).build());
    }

    @Override
    public void added() {
        super.added();
        try {
            icon = FaviconTexture.forServer(this.minecraft.getTextureManager(), data.ip);
            byte[] bytes = data.getIconBytes();
            if (bytes != null) {
                icon.upload(NativeImage.read(bytes));
                iconAvailable = true;
            }
        } catch (IOException | RuntimeException ignored) {
            iconAvailable = false;
            if (icon != null) {
                icon.close();
                icon = null;
            }
        }
    }

    @Override
    public void removed() {
        if (icon != null) {
            icon.close();
            icon = null;
        }
        iconAvailable = false;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);
        int left = cx - 150;
        int wrapWidth = 300;

        g.centeredText(this.font, I18n.get("mc_launcher.server_info.title"), cx, top - 16, 0xFFFFFFFF);

        int y = top + 8;
        y = drawRow(g, left, y, "name", text(data.name));
        y = drawRow(g, left, y, "address", text(data.ip));
        y = drawRow(g, left, y, "state", stateLabel(data.state()));

        String versionText = data.version != null ? data.version.getString() : unknown();
        y = drawRow(g, left, y, "version", versionText + "  (" + I18n.get("mc_launcher.server_info.protocol")
                + " " + data.protocol + ")");
        y = drawRow(g, left, y, "ping", I18n.get("mc_launcher.server_info.ms", String.valueOf(data.ping)));
        y = drawRow(g, left, y, "players", playersText());
        y += 4;

        g.text(this.font, I18n.get("mc_launcher.server_info.motd") + ":", left, y, 0xFFAAAAAA);
        y += this.font.lineHeight + 2;
        Component motd = data.motd != null ? data.motd : Component.literal(unknown());
        g.textWithWordWrap(this.font, motd, left + 8, y, wrapWidth, 0xFFFFFFFF);
        y += this.font.wordWrapHeight(motd, wrapWidth) + 6;

        if (iconAvailable && icon != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, icon.textureLocation(), left, y, 0F, 0F,
                    ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            g.text(this.font, I18n.get("mc_launcher.server_info.icon"), left + ICON_SIZE + 8, y + 12, 0xFFAAAAAA);
            y += ICON_SIZE + 6;
        } else {
            g.text(this.font, I18n.get("mc_launcher.server_info.icon_unavailable"), left, y, 0xFF888888);
            y += this.font.lineHeight + 6;
        }

        List<Component> players = data.playerList;
        if (players != null && !players.isEmpty()) {
            g.text(this.font, I18n.get("mc_launcher.server_info.player_list") + ":", left, y, 0xFFAAAAAA);
            y += this.font.lineHeight + 2;
            int shown = Math.min(MAX_SHOWN_PLAYERS, players.size());
            for (int i = 0; i < shown; i++) {
                g.text(this.font, players.get(i), left + 8, y, 0xFFFFFFFF);
                y += this.font.lineHeight;
            }
            if (players.size() > shown) {
                g.text(this.font, Component.literal(I18n.get("mc_launcher.server_info.player_more", String.valueOf(players.size()))),
                        left + 8, y, 0xFFAAAAAA);
            }
        }

        g.centeredText(this.font, I18n.get("mc_launcher.server_info.readonly_hint"), cx, this.height - 58, 0xFF888888);

        Toasts.draw(g, delta);
    }

    private int drawRow(GuiGraphicsExtractor g, int x, int y, String key, String value) {
        String label = I18n.get("mc_launcher.server_info." + key);
        g.text(this.font, label + ":", x, y, 0xFFAAAAAA);
        g.text(this.font, value, x + 70, y, 0xFFFFFFFF);
        return y + this.font.lineHeight + 3;
    }

    private static String text(String s) {
        return s == null || s.isEmpty() ? unknown() : s;
    }

    private static String unknown() {
        return I18n.get("mc_launcher.server_info.unknown");
    }

    private String playersText() {
        ServerStatus.Players p = data.players;
        if (p != null) {
            return p.online() + " / " + p.max();
        }
        return data.status != null ? data.status.getString() : unknown();
    }

    private static String stateLabel(ServerData.State state) {
        if (state == null) {
            return unknown();
        }
        return switch (state) {
            case INITIAL -> I18n.get("mc_launcher.server_info.state_initial");
            case PINGING -> I18n.get("mc_launcher.server_info.state_pinging");
            case UNREACHABLE -> I18n.get("mc_launcher.server_info.state_unreachable");
            case INCOMPATIBLE -> I18n.get("mc_launcher.server_info.state_incompatible");
            case SUCCESSFUL -> I18n.get("mc_launcher.server_info.state_successful");
        };
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
