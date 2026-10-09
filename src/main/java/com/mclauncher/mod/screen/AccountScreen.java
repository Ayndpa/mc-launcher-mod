package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * 账户管理子界面：展示当前微软正版账号状态（名称 / UUID / 皮肤模型），
 * 支持进入登录界面切换账号、退出登录。 */
public class AccountScreen extends Screen {
    private final Screen parent;
    private PlayerSkinWidget skinWidget;
    private Button logoutButton;

    /** 皮肤供应器捕获的账号名，登录状态变化时重建皮肤组件用 */
    private String skinAccountUuid = "";

    public AccountScreen(Screen parent) {
        super(Component.literal(I18n.get("mc_launcher.account.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        if (acc != null) {
            skinWidget = buildSkinWidget(Math.max(6, cx - 100 - 118), top + 8);
            addRenderableWidget(skinWidget);
        }

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.login_or_switch")), b ->
                        this.minecraft.setScreen(new LoginScreen(this)))
                .bounds(cx - 100, top + 140, 200, 20).build());

        logoutButton = Button.builder(Component.literal(I18n.get("mc_launcher.account.logout")), b -> logout())
                .bounds(cx - 100, top + 166, 200, 20).build();
        logoutButton.active = acc != null;
        addRenderableWidget(logoutButton);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, top + 192, 200, 20).build());
    }

    private PlayerSkinWidget buildSkinWidget(int x, int y) {
        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        skinAccountUuid = acc != null ? acc.uuid() : "";
        PlayerSkinWidget w = new PlayerSkinWidget(88, 132, this.minecraft.getEntityModels(), this::currentSkinSupplier);
        w.setX(x);
        w.setY(y);
        return w;
    }

    private PlayerSkin currentSkinSupplier() {
        Minecraft mc = Minecraft.getInstance();
        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        if (acc != null && !acc.uuid().isEmpty()) {
            com.mojang.authlib.GameProfile profile =
                    new com.mojang.authlib.GameProfile(parseUuidShort(acc.uuid()), acc.name());
            return mc.getSkinManager().createLookup(profile, false).get();
        }
        return mc.getSkinManager().createLookup(mc.getGameProfile(), false).get();
    }

    private static UUID parseUuidShort(String shortUuid) {
        String s = shortUuid.replace("-", "");
        return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20, 32));
    }

    /** 标准带连字符形式，供展示用 */
    private static String formatUuid(String shortUuid) {
        try {
            return parseUuidShort(shortUuid).toString();
        } catch (Exception e) {
            return shortUuid;
        }
    }

    private void logout() {
        MicrosoftAuth.logout();
        Toasts.success(I18n.get("mc_launcher.account.logged_out"));
        // 重建界面内容：移除皮肤组件、禁用退出按钮（均在主线程 tick/点击回调中执行）
        if (skinWidget != null) {
            removeWidget(skinWidget);
            skinWidget = null;
        }
        if (logoutButton != null) {
            logoutButton.active = false;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, I18n.get("mc_launcher.account.title"), cx, top - 20, 0xFFFFFFFF);

        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        if (acc != null) {
            g.text(this.font, I18n.get("mc_launcher.account.name", acc.name()), cx - 100, top + 32, 0xFFFFFFFF);
            g.text(this.font, I18n.get("mc_launcher.account.uuid", formatUuid(acc.uuid())), cx - 100, top + 48, 0xFFAAAAAA);
            g.text(this.font, I18n.get("mc_launcher.account.status_msa"), cx - 100, top + 64, 0xFF55FF55);
            g.text(this.font, I18n.get("mc_launcher.account.skin_hint"), cx - 100, top + 84, 0xFF888888);
        } else {
            g.text(this.font, I18n.get("mc_launcher.account.status_offline"), cx - 100, top + 32, 0xFFFF5555);
            g.text(this.font, I18n.get("mc_launcher.account.offline_hint"), cx - 100, top + 48, 0xFF888888);
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
