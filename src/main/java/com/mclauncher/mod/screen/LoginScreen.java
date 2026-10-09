package com.mclauncher.mod.screen;

import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 微软正版登录子界面：设备码流程 —— 打开 microsoft.com/link 输入码，
 * 成功后凭据持久化（refresh_token），下次启动自动续期。 */
public class LoginScreen extends Screen {
    private final Screen parent;
    private MicrosoftAuth.DeviceCodeLogin login;
    private String code = "";

    public LoginScreen(Screen parent) {
        super(Component.literal("微软登录"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        addRenderableWidget(Button.builder(Component.literal("开始登录"), b -> startLogin())
                .bounds(cx - 100, this.height / 2 + 10, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, this.height / 2 + 36, 200, 20).build());
    }

    private void startLogin() {
        if (login != null && login.state == MicrosoftAuth.State.POLLING) {
            return;
        }
        Toasts.info("正在获取设备码…");
        login = MicrosoftAuth.startDeviceCodeLogin(msg -> {
            MicrosoftAuth.State s = login != null ? login.state : null;
            if (s == MicrosoftAuth.State.SUCCESS) {
                Toasts.success(msg);
            } else {
                Toasts.info(msg);
            }
        });
    }

    @Override
    public void tick() {
        if (login != null) {
            code = login.userCode;
        }
        super.tick();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, "微软正版登录", cx, top - 20, 0xFFFFFFFF);
        if (login == null) {
            g.centeredText(this.font, "使用微软账号登录 Minecraft", cx, top + 20, 0xFFAAAAAA);
            g.centeredText(this.font, "点击「开始登录」获取设备码", cx, top + 34, 0xFFAAAAAA);
        } else {
            if (!code.isEmpty()) {
                g.centeredText(this.font, "1. 浏览器打开:", cx, top + 10, 0xFFAAAAAA);
                g.centeredText(this.font, login.verificationUri, cx, top + 24, 0xFF55FFFF);
                g.centeredText(this.font, "2. 输入设备码:", cx, top + 44, 0xFFAAAAAA);
                g.centeredText(this.font, code, cx, top + 58, 0xFF55FF55);
            }
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
