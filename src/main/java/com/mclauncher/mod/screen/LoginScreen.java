package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 微软正版登录子界面 —— 对齐 prismarine-auth 的 Device Code 流：
 * 申请设备码 → 自动打开 microsoft.com/link 并复制代码 → 用户输入代码确认 →
 * 后台轮询自动完成登录。无需浏览器回调、无需粘贴 URL。
 * 凭据持久化（refresh_token），下次启动自动续期。 */
public class LoginScreen extends Screen {
    private final Screen parent;
    private MicrosoftAuth.CodeLogin login;

    public LoginScreen(Screen parent) {
        super(Component.literal(I18n.get("mc_launcher.login.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.login.open_page")), b -> startLogin())
                .bounds(cx - 100, top + 42, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.login.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, this.height - 60, 200, 20).build());
    }

    /** 发起设备码登录：拿到设备码后自动打开验证页并复制代码到剪贴板 */
    private void startLogin() {
        if (login != null && (login.state == MicrosoftAuth.State.WAITING_CODE
                || login.state == MicrosoftAuth.State.EXCHANGING)) {
            return;
        }
        login = MicrosoftAuth.startDeviceCodeLogin();
    }

    @Override
    public void tick() {
        if (login != null) {
            // 拿到设备码后：自动打开 microsoft.com/link 页面，代码已复制到剪贴板
            if (!login.linkUrl.isEmpty() && login.deviceCodePageOpened == false) {
                login.deviceCodePageOpened = true;
                copyUserCode();
                openLinkPage();
            }
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

    private void copyUserCode() {
        if (login != null && !login.userCode.isEmpty()) {
            this.minecraft.keyboardHandler.setClipboard(login.userCode);
        }
    }

    private void openLinkPage() {
        String url = login.linkUrl;
        // 1) Desktop.browse（正常路径）
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            Toasts.info(I18n.get("mc_launcher.login.page_opened"));
            return;
        } catch (Throwable t) {
            com.mclauncher.mod.McLauncherMod.LOGGER.info("Desktop.browse 失败: {}", t.toString());
        }
        // 2) 退化：cmd start（ShellExecute，走默认浏览器关联）
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
            Toasts.info(I18n.get("mc_launcher.login.page_opened"));
            return;
        } catch (Exception e) {
            com.mclauncher.mod.McLauncherMod.LOGGER.info("cmd start 失败: {}", e.toString());
        }
        // 3) 最终兜底：提示用户手动访问，链接已在日志与界面代码区
        Toasts.warn(I18n.get("mc_launcher.login.page_open_failed", url));
        com.mclauncher.mod.McLauncherMod.LOGGER.info("微软登录验证页: {}", url);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, I18n.get("mc_launcher.login.title"), cx, top - 20, 0xFFFFFFFF);

        if (login != null && !login.userCode.isEmpty()) {
            g.centeredText(this.font, I18n.get("mc_launcher.login.device_code_label"), cx, top + 6, 0xFFAAAAAA);
            g.centeredText(this.font, login.userCode, cx, top + 22, 0xFF55FFFF);
            g.centeredText(this.font, I18n.get("mc_launcher.login.device_code_hint"), cx, top + 38, 0xFF888888);
        } else if (login == null) {
            g.centeredText(this.font, I18n.get("mc_launcher.login.device_intro"), cx, top + 6, 0xFFAAAAAA);
        }

        String msg = login != null ? login.message : "";
        if (msg != null && !msg.isEmpty()) {
            g.centeredText(this.font, msg, cx, top + 70, login.state == MicrosoftAuth.State.FAILED ? 0xFFFF5555 : 0xFF55FFFF);
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
