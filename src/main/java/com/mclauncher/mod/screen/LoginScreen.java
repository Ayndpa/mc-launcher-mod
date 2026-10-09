package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;

/**
 * 微软正版登录子界面：Live OAuth 授权码流程 —— 打开浏览器完成登录，
 * 把登录完成后浏览器地址栏的完整 URL 粘贴回来即可自动完成登录。
 * 凭据持久化（refresh_token），下次启动自动续期。 */
public class LoginScreen extends Screen {
    private final Screen parent;
    private MicrosoftAuth.CodeLogin login;
    private EditBox urlBox;
    private boolean browserOpened = false;

    public LoginScreen(Screen parent) {
        super(Component.literal(I18n.get("mc_launcher.login.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        urlBox = new EditBox(this.font, cx - 150, top + 60, 300, 20, Component.literal(I18n.get("mc_launcher.login.url_box_hint")));
        urlBox.setHint(Component.literal(I18n.get("mc_launcher.login.url_placeholder")));
        addRenderableWidget(urlBox);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.login.open_page")), b -> openBrowser())
                .bounds(cx - 150, top + 12, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.login.confirm")), b -> submitCode())
                .bounds(cx + 5, top + 12, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.login.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, this.height - 60, 200, 20).build());
    }

    private void openBrowser() {
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(MicrosoftAuth.authorizeUrl()));
            browserOpened = true;
            Toasts.info(I18n.get("mc_launcher.login.browser_opened"));
        } catch (IOException | UnsupportedOperationException e) {
            // 打不开浏览器时退化为手动复制链接
            String url = MicrosoftAuth.authorizeUrl();
            this.minecraft.keyboardHandler.setClipboard(url);
            browserOpened = true;
            Toasts.info(I18n.get("mc_launcher.login.browser_failed"));
            Toasts.info(I18n.get("mc_launcher.login.url_in_log", url));
            com.mclauncher.mod.McLauncherMod.LOGGER.info("微软登录授权链接: {}", url);
        }
    }

    private void submitCode() {
        if (login != null && login.state == MicrosoftAuth.State.EXCHANGING) {
            return;
        }
        String url = urlBox.getValue().trim();
        String code = MicrosoftAuth.extractCode(url);
        if (code == null) {
            Toasts.error(I18n.get("mc_launcher.login.no_code"));
            return;
        }
        Toasts.info(I18n.get("mc_launcher.login.exchanging"));
        login = MicrosoftAuth.startCodeLogin(code);
    }

    @Override
    public void tick() {
        if (login != null) {
            if (login.state == MicrosoftAuth.State.SUCCESS) {
                Toasts.success(login.message);
                this.minecraft.setScreen(parent);
                return;
            }
            if (login.state == MicrosoftAuth.State.FAILED) {
                Toasts.error(login.message);
                login = null;
            }
        }
        super.tick();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, I18n.get("mc_launcher.login.title"), cx, top - 20, 0xFFFFFFFF);
        g.centeredText(this.font, I18n.get("mc_launcher.login.step1"), cx, top - 2, 0xFFAAAAAA);
        g.centeredText(this.font, I18n.get("mc_launcher.login.step2"), cx, top + 12, 0xFFAAAAAA);
        g.centeredText(this.font, I18n.get("mc_launcher.login.step3"), cx, top + 26, 0xFFAAAAAA);
        g.text(this.font, Component.literal(I18n.get("mc_launcher.login.url_label")), cx - 150, top + 48, 0xFFAAAAAA);

        if (login != null && login.state == MicrosoftAuth.State.EXCHANGING) {
            g.centeredText(this.font, login.message, cx, top + 96, 0xFF55FFFF);
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
