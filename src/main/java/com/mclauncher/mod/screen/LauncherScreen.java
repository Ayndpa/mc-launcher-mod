package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherState;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 恶搞启动器主界面：用 Minecraft 做的“Minecraft 启动器”。
 * 版本选择（子界面 + 分类列表）、Java 管理、语言设置、微软正版登录、实时玩家模型。
 * “开始游戏”会真实拉起所选版本的新 java 进程。 */
public class LauncherScreen extends Screen {
    private Button versionButton;
    private Button playButton;
    private PlayerSkinWidget skinWidget;
    private String launchedVersion;

    /** 登录/续期完成后重建皮肤组件（PlayerSkinWidget 的供应器是构造时捕获的） */
    private volatile boolean skinRefreshPending = false;

    public LauncherScreen() {
        super(Component.literal(I18n.get("mc_launcher.title")));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        launchedVersion = Minecraft.getInstance().getLaunchedVersion();

        var installed = LauncherCore.scanInstalled();
        // 版本选择逻辑：记住上次选择 → 上次选择仍存在 → 默认第一个版本；列表为空则禁用开始游戏
        String savedSel = LauncherState.getSelectedVersion();
        boolean savedExists = savedSel != null && !savedSel.isBlank()
                && installed.stream().anyMatch(v -> v.id().equals(savedSel));
        String sel;
        if (savedExists) {
            sel = savedSel;
        } else {
            // 无记录或记录的版本已被删除 → 默认第一个版本
            sel = installed.isEmpty() ? null : installed.get(0).id();
            LauncherState.setSelectedVersion(sel);
        }

        versionButton = Button.builder(versionLabel(), b -> this.minecraft
                        .setScreen(new LocalVersionsScreen(this)))
                .bounds(cx - 100, top + 30, 200, 20).build();
        addRenderableWidget(versionButton);

        playButton = Button.builder(Component.literal(I18n.get("mc_launcher.play")), b -> startGame())
                .bounds(cx - 100, top + 60, 200, 20).build();
        playButton.active = sel != null;
        addRenderableWidget(playButton);

        // 第二行：多人游戏（只可添加/查看服务器）
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer")), b -> this.minecraft
                        .setScreen(new MultiplayerScreen(this)))
                .bounds(cx - 100, top + 88, 200, 20).build());

        // 第三行：启动器设置（Java 管理在里面）
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.options")), b -> this.minecraft
                        .setScreen(new LauncherOptionsScreen(this, this.minecraft.options)))
                .bounds(cx - 100, top + 114, 200, 20).build());

        // 左侧：实时玩家模型（跟随当前登录账号的皮肤，懒加载刷新；点击进入账户管理）
        int widgetX = Math.max(6, cx - 100 - 118);
        skinWidget = buildSkinWidget(widgetX, top + 8);
        addRenderableWidget(skinWidget);
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
        PlayerSkinWidget w = new ClickableSkinWidget(88, 132, this.minecraft.getEntityModels(), this::currentSkinSupplier);
        w.setX(x);
        w.setY(y);
        return w;
    }

    /**
     * 点击玩家模型进入账户管理界面。
     * 自绘渲染：头部与视线跟随全屏鼠标指针，附带类似游戏内背包界面的轻微待机摆动。
     */
    private class ClickableSkinWidget extends PlayerSkinWidget {
        private static final float MODEL_HEIGHT = 2.125F;
        private static final float PIVOT_Y = -1.0625F;

        private final net.minecraft.client.model.player.PlayerModel wideModel;
        private final net.minecraft.client.model.player.PlayerModel slimModel;
        private float idleAge;

        ClickableSkinWidget(int width, int height, net.minecraft.client.model.geom.EntityModelSet modelSet,
                            java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> skin) {
            super(width, height, modelSet, skin);
            wideModel = new net.minecraft.client.model.player.PlayerModel(
                    modelSet.bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER), false);
            slimModel = new net.minecraft.client.model.player.PlayerModel(
                    modelSet.bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM), true);
        }

        @Override
        public void mouseMoved(double mouseX, double mouseY) {
            super.mouseMoved(mouseX, mouseY);
            // 26.x 的 Screen.mouseMoved 是空实现不向下分发，但渲染参数会带真实鼠标位置，
            // 这里仅记录“见过鼠标”用于初始朝向过渡
            mouseSeen = true;
        }

        private float headYawDeg = 30.0F;
        private float headPitchDeg;
        private boolean mouseSeen;

        @Override
        protected void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            // 全屏鼠标坐标 → 头部目标角度（模型有 Z 轴翻转，水平方向取反），限幅 ±75°/±40°
            float dx = mouseX - (this.getX() + this.getWidth() / 2.0F);
            float dy = mouseY - (this.getY() + this.getHeight() * 0.3F);
            float headTargetYaw = Mth.clamp(-dx * 0.25F, -75.0F, 75.0F);
            float headTargetPitch = Mth.clamp(dy * 0.25F, -40.0F, 40.0F);
            // 平滑插值让头部转动自然；鼠标从未动过时保持默认朝向
            float t = mouseSeen ? 0.3F : 1.0F;
            headYawDeg += (headTargetYaw - headYawDeg) * t;
            headPitchDeg += (headTargetPitch - headPitchDeg) * t;

            net.minecraft.world.entity.player.PlayerSkin skin = currentSkinSupplier();
            boolean slim = skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
            net.minecraft.client.model.player.PlayerModel model = slim ? slimModel : wideModel;

            // 对齐原版背包界面：身体/腿保持静止，手臂用原版 HumanoidModel
            // 的待机呼吸动画（AnimationUtils.bobModelPart，zRot 外张 + 微幅摆动）
            idleAge += delta;
            // delta 是部分 tick（0~1），原版 ageInTicks 每 tick +1，直接累加即可
            model.body.yRot = 0.0F;
            model.body.xRot = 0.0F;
            model.head.xRot = headPitchDeg * ((float) Math.PI / 180F);
            model.head.yRot = headYawDeg * ((float) Math.PI / 180F);
            // hat 是 head 的子节点，自动跟随头部旋转，手动再设会叠加双倍角度
            // 导致双层皮肤外层与内层不对称，保持默认即可
            model.rightArm.xRot = 0.0F;
            model.rightArm.zRot = 0.0F;
            model.leftArm.xRot = 0.0F;
            model.leftArm.zRot = 0.0F;
            net.minecraft.client.model.AnimationUtils.bobModelPart(model.rightArm, idleAge, 1.0F);
            net.minecraft.client.model.AnimationUtils.bobModelPart(model.leftArm, idleAge, -1.0F);
            model.rightLeg.xRot = 0.0F;
            model.leftLeg.xRot = 0.0F;

            float scale = 0.97F * this.getHeight() / MODEL_HEIGHT;
            Identifier texture = skin.body().texturePath();
            g.skin(model, texture, scale, 0.0F, 0.0F, PIVOT_Y,
                    this.getX(), this.getY(), this.getRight(), this.getBottom());
        }

        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
            LauncherScreen.this.minecraft.setScreen(new AccountScreen(LauncherScreen.this));
        }
    }

    private net.minecraft.world.entity.player.PlayerSkin currentSkinSupplier() {
        Minecraft mc = Minecraft.getInstance();
        String offlineName = LauncherState.getActiveOfflineName();
        if (offlineName != null) {
            // 离线档案：用当前游戏档案的皮肤（离线模式无独立皮肤档案）
            return mc.getSkinManager().createLookup(mc.getGameProfile(), false).get();
        }
        MicrosoftAuth.Account acc = MicrosoftAuth.getAccount();
        if (acc != null && !acc.uuid().isEmpty()) {
            // fetchProfile 补全 textures 属性后 SkinManager 才能加载皮肤与披风
            com.mojang.authlib.GameProfile profile = com.mclauncher.mod.SkinProfiles.withTextures(
                    parseUuidShort(acc.uuid()), acc.name());
            return mc.getSkinManager().createLookup(profile, false).get();
        }
        return mc.getSkinManager().createLookup(mc.getGameProfile(), false).get();
    }

    private static java.util.UUID parseUuidShort(String shortUuid) {
        String s = shortUuid.replace("-", "");
        return java.util.UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20, 32));
    }

    private Component versionLabel() {
        String v = LauncherState.getSelectedVersion();
        if (v == null || v.isBlank()) {
            return Component.literal(I18n.get("mc_launcher.version_none"));
        }
        if (v.equals(launchedVersion)) {
            return Component.literal(I18n.get("mc_launcher.version_running", v));
        }
        return Component.literal(I18n.get("mc_launcher.version", v));
    }

    private void startGame() {
        // 真实启动所选版本：进入仿原版的启动加载页（失败会回到本界面并弹错误提示）
        this.minecraft.setScreen(new VersionLaunchScreen(this, LauncherState.getSelectedVersion()));
    }

    @Override
    public void tick() {
        if (skinRefreshPending && skinWidget != null) {
            skinRefreshPending = false;
            // 重建皮肤组件（子线程完成登录/续期后走这里）
            int x = skinWidget.getX();
            int y = skinWidget.getY();
            removeWidget(skinWidget);
            skinWidget = buildSkinWidget(x, y);
            addRenderableWidget(skinWidget);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // 画主菜单全景背景（26.x 默认 Screen 不自动画）
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        g.centeredText(this.font, I18n.get("mc_launcher.title"), cx, top - 22, 0xFFFFFFFF);
        g.centeredText(this.font, I18n.get("mc_launcher.title_subtitle"), cx, top - 10, 0xFFAAAAAA);

        g.text(this.font, Component.literal(I18n.get("mc_launcher.click_model_hint")),
                Math.max(6, cx - 100 - 118), top + 116, 0xFF888888);

        Toasts.draw(g, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
