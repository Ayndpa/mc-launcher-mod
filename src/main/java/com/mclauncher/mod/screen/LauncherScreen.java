package com.mclauncher.mod.screen;

import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherState;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 恶搞启动器主界面：用 Minecraft 做的“Minecraft 启动器”。
 * 版本选择（子界面 + 分类列表）、Java 管理、语言设置、微软正版登录、实时玩家模型。
 * “开始游戏”会真实拉起所选版本的新 java 进程；启动当前版本则播放彩蛋进度条。 */
public class LauncherScreen extends Screen {
    private static final String[] FAKE_STEPS = {
            "正在连接 BMCLAPI 镜像…",
            "下载 net.fabricmc:fabric-loader:0.19.5…",
            "解压 LWJGL natives…",
            "下载资源文件 4750/4750…",
            "启动 net.minecraft.client.main.Main…"
    };
    private static final int TICKS_PER_STEP = 35;

    private EditBox usernameBox;
    private Button versionButton;
    private Button playButton;
    private Button loginButton;
    private PlayerSkinWidget skinWidget;
    private Component status;
    private String launchedVersion;

    private int fakeProgress = -1;
    private int tickCounter = 0;
    private boolean done = false;
    /** 登录/续期完成后重建皮肤组件（PlayerSkinWidget 的供应器是构造时捕获的） */
    private volatile boolean skinRefreshPending = false;

    public LauncherScreen() {
        super(Component.literal("MC Launcher"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        usernameBox = new EditBox(this.font, cx - 100, top + 30, 200, 20, Component.literal("用户名"));
        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        usernameBox.setValue(acc != null ? acc.name() : Minecraft.getInstance().getUser().getName());
        usernameBox.setMaxLength(16);
        addRenderableWidget(usernameBox);

        launchedVersion = Minecraft.getInstance().getLaunchedVersion();
        if (launchedVersion == null || launchedVersion.isBlank()) {
            // Fabric 环境下可能拿不到运行版本，兜底扫描本地版本或用固定值
            var installed = LauncherCore.scanInstalled();
            launchedVersion = installed.isEmpty() ? "26.1.2" : installed.get(installed.size() - 1).id();
        }
        if (LauncherState.getSelectedVersion() == null || LauncherState.getSelectedVersion().isBlank()) {
            LauncherState.setSelectedVersion(launchedVersion);
        }

        versionButton = Button.builder(versionLabel(), b -> this.minecraft
                        .setScreen(new LocalVersionsScreen(this, launchedVersion)))
                .bounds(cx - 100, top + 60, 200, 20).build();
        addRenderableWidget(versionButton);

        playButton = Button.builder(Component.literal("开始游戏！"), b -> startGame())
                .bounds(cx - 100, top + 90, 200, 20).build();
        addRenderableWidget(playButton);

        // 第二行：Java 管理 + 语言设置
        addRenderableWidget(Button.builder(Component.literal("Java 管理"), b -> this.minecraft
                        .setScreen(new JavaManagerScreen(this, LauncherState.getChosenJava())))
                .bounds(cx - 100, top + 118, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("语言设置"), b -> openLanguageScreen())
                .bounds(cx + 2, top + 118, 98, 20).build());

        // 左侧：实时玩家模型（跟随当前登录账号的皮肤，懒加载刷新）
        int widgetX = Math.max(6, cx - 100 - 118);
        skinWidget = buildSkinWidget(widgetX, top + 8);
        addRenderableWidget(skinWidget);

        // 微软登录入口（持久化凭据，自动续期）
        loginButton = Button.builder(loginLabel(), b -> {
                    if (MicrosoftAuth.getAccount() != null) {
                        // 已登录：再点一次弹出登录界面可切换账号；这里长按?简化：直接进登录界面
                        this.minecraft.setScreen(new LoginScreen(this));
                    } else {
                        this.minecraft.setScreen(new LoginScreen(this));
                    }
                })
                .bounds(cx - 100, top + 170, 200, 20).build();
        addRenderableWidget(loginButton);

        // 手动改用户名意味着想用离线模式
        usernameBox.setResponder(s -> {
            MicrosoftAuth.Account a = MicrosoftAuth.getAccount();
            if (a != null && !s.equals(a.name())) {
                loginButton.setMessage(Component.literal("微软正版登录 (未使用)"));
            } else {
                loginButton.setMessage(loginLabel());
            }
        });

        // 第三行：打开目录 + 退出
        addRenderableWidget(Button.builder(Component.literal("打开游戏目录"), b -> openGameDir())
                .bounds(cx - 100, top + 196, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("退出启动器"), b -> Minecraft.getInstance().stop())
                .bounds(cx + 2, top + 196, 98, 20).build());

        // 第四行：游戏原版选项 + 代理设置
        addRenderableWidget(Button.builder(Component.literal("选项…"), b -> this.minecraft
                        .setScreen(new net.minecraft.client.gui.screens.options.OptionsScreen(
                                this, this.minecraft.options, true)))
                .bounds(cx - 100, top + 222, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("代理设置"), b -> this.minecraft
                        .setScreen(new ProxySettingsScreen(this)))
                .bounds(cx + 2, top + 222, 98, 20).build());
    }

    /** 主界面打开时若存有凭据，静默续期；登录后刷新皮肤与账号显示 */
    @Override
    public void added() {
        super.added();
        Thread t = new Thread(() -> {
            if (MicrosoftAuth.refreshIfNeeded()) {
                skinRefreshPending = true;
            }
        }, "mc-launcher-mod-msa-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** 皮肤供应器：正版账号登录后用其档案皮肤（触发下载/离线缓存），否则用当前游戏档案 */
    private PlayerSkinWidget buildSkinWidget(int x, int y) {
        PlayerSkinWidget w = new PlayerSkinWidget(88, 132, this.minecraft.getEntityModels(), this::currentSkinSupplier);
        w.setX(x);
        w.setY(y);
        return w;
    }

    private net.minecraft.world.entity.player.PlayerSkin currentSkinSupplier() {
        Minecraft mc = Minecraft.getInstance();
        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        if (acc != null && !acc.uuid().isEmpty()) {
            com.mojang.authlib.GameProfile profile =
                    new com.mojang.authlib.GameProfile(parseUuidShort(acc.uuid()), acc.name());
            return mc.getSkinManager().createLookup(profile, false).get();
        }
        return mc.getSkinManager().createLookup(mc.getGameProfile(), false).get();
    }

    private static java.util.UUID parseUuidShort(String shortUuid) {
        String s = shortUuid.replace("-", "");
        return java.util.UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20, 32));
    }

    private Component loginLabel() {
        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        if (acc != null) {
            return Component.literal("账号: " + acc.name() + " (正版)");
        }
        return Component.literal("微软正版登录");
    }

    private Component versionLabel() {
        String v = LauncherState.getSelectedVersion();
        if (v.equals(launchedVersion)) {
            return Component.literal("版本: " + v + " (运行中)");
        }
        return Component.literal("版本: " + v);
    }

    private void startGame() {
        String target = LauncherState.getSelectedVersion();
        if (target.equals(launchedVersion)) {
            // 启动的就是当前正在运行的版本 —— 彩蛋进度条
            if (fakeProgress >= 0) {
                return;
            }
            fakeProgress = 0;
            tickCounter = 0;
            done = false;
            playButton.active = false;
            status = Component.literal(FAKE_STEPS[0]);
            return;
        }
        // 真实启动其它版本：进入仿原版的启动加载页（失败会回到本界面并弹错误提示）
        this.minecraft.setScreen(new VersionLaunchScreen(this, target));
    }

    private void openLanguageScreen() {
        // 直接复用游戏原版语言选择界面（语言列表已被 Mixin 限定为 中/英）
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new net.minecraft.client.gui.screens.options.LanguageSelectScreen(
                this, mc.options, mc.getLanguageManager()));
    }

    private void openGameDir() {
        try {
            new ProcessBuilder("explorer.exe", Minecraft.getInstance().gameDirectory.getAbsolutePath()).start();
        } catch (IOException ignored) {
            Toasts.error("打不开游戏目录（开玩笑的，这可有点尴尬）");
        }
    }

    @Override
    public void tick() {
        if (skinRefreshPending && skinWidget != null) {
            skinRefreshPending = false;
            // 重建皮肤组件并同步更新登录按钮文案（子线程完成登录后走这里）
            int x = skinWidget.getX();
            int y = skinWidget.getY();
            removeWidget(skinWidget);
            skinWidget = buildSkinWidget(x, y);
            addRenderableWidget(skinWidget);
            loginButton.setMessage(loginLabel());
        }
        if (fakeProgress >= 0 && !done) {
            tickCounter++;
            if (tickCounter % TICKS_PER_STEP == 0) {
                fakeProgress++;
                if (fakeProgress >= FAKE_STEPS.length) {
                    done = true;
                    status = Component.literal("启动完成！……等等，你本来就在游戏里啊。");
                } else {
                    status = Component.literal(FAKE_STEPS[fakeProgress]);
                }
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // 画主菜单全景背景（26.x 默认 Screen 不自动画）
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, "MC 启动器", cx, top - 22, 0xFFFFFFFF);
        g.centeredText(this.font, "—— 用 Minecraft 做的 Minecraft 启动器 ——", cx, top - 10, 0xFFAAAAAA);

        if (status != null) {
            g.text(this.font, status, cx - 100, top + 174, done ? 0xFF55FF55 : 0xFFFFFFFF);
        }
        g.text(this.font, Component.literal("玩家模型会实时反映当前登录账号的皮肤"),
                cx - 100, top + 222, 0xFF888888);

        Toasts.draw(g, delta);

        if (fakeProgress >= 0) {
            int barW = 200;
            int barH = 6;
            int bx = cx - barW / 2;
            int by = top + 188;
            int total = FAKE_STEPS.length * TICKS_PER_STEP;
            int elapsed = Math.min(total, fakeProgress * TICKS_PER_STEP + tickCounter % TICKS_PER_STEP);
            float frac = (float) elapsed / total;
            int filled = (int) (barW * frac);
            g.fill(bx, by, bx + barW, by + barH, 0xFF222222);
            g.fill(bx, by, bx + filled, by + barH, done ? 0xFF55FF55 : 0xFF3B82F6);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
