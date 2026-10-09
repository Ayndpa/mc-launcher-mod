package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherState;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 账户管理子界面：以列表形式展示全部档案（微软正版 + 离线档案），
 * 支持新增微软登录 / 新增离线档案 / 切换激活档案 / 删除档案。
 * 列表条目前显示玩家头像，悬停时在鼠标旁显示完整模型。 */
public class AccountScreen extends Screen {
    private final Screen parent;
    private ProfileList list;
    private Button deleteButton;

    /** 列表条目：正版档案或离线档案 */
    private sealed interface Profile permits MsaProfile, OfflineProfile {
    }

    private record MsaProfile(MicrosoftAuth.Account acc) implements Profile {
    }

    private record OfflineProfile(String name) implements Profile {
    }

    /** 列表选中项 */
    private Profile selected;

    public AccountScreen(Screen parent) {
        super(Component.literal(I18n.get("mc_launcher.account.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        int listTop = top + 24;
        int listBottom = this.height - 108;
        list = new ProfileList(this.minecraft, 380, listBottom - listTop, listTop, 28);
        list.setX((this.width - 380) / 2);
        addRenderableWidget(list);
        reloadProfiles();

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.add_msa")), b ->
                        this.minecraft.setScreen(new LoginScreen(this)))
                .bounds(cx - 154, this.height - 96, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.add_offline")), b ->
                        this.minecraft.setScreen(new OfflineNameInputScreen(this, name -> {
                            if (name != null && !name.isBlank()) {
                                LauncherState.addOfflineProfile(name);
                                Toasts.success(I18n.get("mc_launcher.account.offline_added", name));
                            }
                        })))
                .bounds(cx - 50, this.height - 96, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.use_this")), b -> useSelected())
                .bounds(cx + 54, this.height - 96, 100, 20).build());

        deleteButton = Button.builder(Component.literal(I18n.get("mc_launcher.account.delete")), b -> deleteSelected())
                .bounds(cx - 154, this.height - 68, 100, 20).build();
        deleteButton.active = false;
        addRenderableWidget(deleteButton);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.account.back")), b ->
                        this.minecraft.setScreen(parent))
                .bounds(cx - 50, this.height - 68, 100, 20).build());
    }

    /** 重新从磁盘读取全部档案并刷新列表（登录/返回后调用） */
    private void reloadProfiles() {
        List<Profile> profiles = new ArrayList<>();
        List<String> offlineNames = LauncherState.loadOfflineProfiles();
        // 先把离线输入界面刚添加但未保存过的名字补上（回到本界面时 LoginScreen 不走这里）
        for (MicrosoftAuth.Account acc : MicrosoftAuth.loadSavedAccounts()) {
            profiles.add(new MsaProfile(acc));
        }
        for (String n : offlineNames) {
            profiles.add(new OfflineProfile(n));
        }
        list.replaceAll(profiles);
        selected = null;
        if (deleteButton != null) {
            deleteButton.active = false;
        }
    }

    /** 档案对应的玩家皮肤（正版档案带 textures，离线档案用当前游戏档案皮肤） */
    private net.minecraft.world.entity.player.PlayerSkin profileSkin(Profile p) {
        Minecraft mc = Minecraft.getInstance();
        if (p instanceof MsaProfile(MicrosoftAuth.Account acc) && !acc.uuid().isEmpty()) {
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

    /** 标准带连字符形式，供展示用 */
    private static String formatUuid(String shortUuid) {
        try {
            return parseUuidShort(shortUuid).toString();
        } catch (Exception e) {
            return shortUuid;
        }
    }

    private void useSelected() {
        if (selected == null) {
            Toasts.warn(I18n.get("mc_launcher.account.select_first"));
            return;
        }
        if (selected instanceof MsaProfile(MicrosoftAuth.Account acc)) {
            MicrosoftAuth.switchAccount(acc.uuid());
            Toasts.success(I18n.get("mc_launcher.account.activated", acc.name()));
        } else if (selected instanceof OfflineProfile(String name)) {
            LauncherState.setActiveOfflineName(name);
            Toasts.success(I18n.get("mc_launcher.account.activated", name));
        }
    }

    private void deleteSelected() {
        if (selected == null) {
            Toasts.warn(I18n.get("mc_launcher.account.select_first"));
            return;
        }
        if (selected instanceof MsaProfile(MicrosoftAuth.Account acc)) {
            MicrosoftAuth.removeAccount(acc.uuid());
            Toasts.success(I18n.get("mc_launcher.account.logged_out"));
        } else if (selected instanceof OfflineProfile(String name)) {
            LauncherState.removeOfflineProfile(name);
            Toasts.success(I18n.get("mc_launcher.account.offline_removed", name));
        }
        reloadProfiles();
    }

    @Override
    public void added() {
        super.added();
        // 从 LoginScreen 登录成功返回后刷新列表
        if (list != null) {
            reloadProfiles();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, I18n.get("mc_launcher.account.title"), cx, top - 16, 0xFFFFFFFF);

        // 当前生效档案提示
        String activeDesc;
        MicrosoftAuth.Account active = MicrosoftAuth.getAccount();
        if (LauncherState.getActiveOfflineName() != null) {
            activeDesc = I18n.get("mc_launcher.account.active_offline", LauncherState.getActiveOfflineName());
        } else if (active != null) {
            activeDesc = I18n.get("mc_launcher.account.active_msa", active.name());
        } else {
            activeDesc = I18n.get("mc_launcher.account.active_none");
        }
        g.centeredText(this.font, activeDesc, cx, top - 2, 0xFFAAAAAA);

        // 悬停头像时，在鼠标旁渲染对应档案的完整玩家模型
        hoverEntry = null;
        super.extractRenderState(g, mouseX, mouseY, delta);
        ProfileList.Entry hover = hoverEntry;
        if (hover != null && this.minecraft != null) {
            net.minecraft.world.entity.player.PlayerSkin skin = profileSkin(hover.profile);
            boolean slim = skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
            net.minecraft.client.model.player.PlayerModel model = new net.minecraft.client.model.player.PlayerModel(
                    this.minecraft.getEntityModels().bakeLayer(slim
                            ? net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM
                            : net.minecraft.client.model.geom.ModelLayers.PLAYER), slim);
            float scale = 0.9F * HOVER_MODEL / 2.125F;
            int mx = Math.min(mouseX + 12, this.width - HOVER_MODEL - 2);
            int my = Math.min(mouseY - HOVER_MODEL / 2, this.height - HOVER_MODEL - 2);
            g.skin(model, skin.body().texturePath(), scale, 0.0F, 0.0F, -1.0625F,
                    mx, my, mx + HOVER_MODEL, my + HOVER_MODEL);
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

    // ---------- 档案列表 ----------

    /** 列表条目前头像的边长（像素） */
    private static final int AVATAR = 24;
    /** 悬停头像时鼠标旁模型的边长（像素） */
    private static final int HOVER_MODEL = 66;

    /** 当前帧悬停头像所在的条目（渲染时写入，帧末用于画模型） */
    private ProfileList.Entry hoverEntry;

    private class ProfileList extends ObjectSelectionList<ProfileList.Entry> {

        ProfileList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void replaceAll(List<Profile> profiles) {
            this.clearEntries();
            for (Profile p : profiles) {
                this.addEntry(new Entry(p));
            }
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final Profile profile;

            Entry(Profile profile) {
                this.profile = profile;
            }

            private boolean isActive() {
                if (profile instanceof MsaProfile(MicrosoftAuth.Account acc)) {
                    return LauncherState.getActiveOfflineName() == null && acc.uuid().equals(LauncherState.getActiveMsaUuid())
                            || LauncherState.getActiveOfflineName() == null
                                    && MicrosoftAuth.getAccount() != null
                                    && acc.uuid().equals(MicrosoftAuth.getAccount().uuid());
                }
                if (profile instanceof OfflineProfile(String name)) {
                    return name.equals(LauncherState.getActiveOfflineName());
                }
                return false;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            int color = ProfileList.this.getSelected() == this ? 0xFF55FF55 : (isActive() ? 0xFF7FDFFF : 0xFFFFFFFF);
            int left = this.getContentX();
            int top = this.getContentY();
            int textX = left + AVATAR + 6;
            if (profile instanceof MsaProfile(MicrosoftAuth.Account acc)) {
                String label = I18n.get("mc_launcher.account.entry_msa", acc.name())
                        + (isActive() ? I18n.get("mc_launcher.account.active_tag") : "");
                g.text(AccountScreen.this.font, label, textX, top + 3, color);
                g.text(AccountScreen.this.font, I18n.get("mc_launcher.account.uuid", formatUuid(acc.uuid())),
                        textX, top + 16, 0xFF888888);
            } else if (profile instanceof OfflineProfile(String name)) {
                String label = I18n.get("mc_launcher.account.entry_offline", name)
                        + (isActive() ? I18n.get("mc_launcher.account.active_tag") : "");
                g.text(AccountScreen.this.font, label, textX, top + 3, color);
                g.text(AccountScreen.this.font, I18n.get("mc_launcher.account.offline_tag"),
                        textX, top + 16, 0xFF888888);
            }
            // 条目前绘制玩家头像（皮肤贴图 8x8 头部区域放大到 AVATAR）
            Identifier faceTex = profileSkin(profile).body().texturePath();
                g.blit(RenderPipelines.GUI_TEXTURED, faceTex, left, top + 2, AVATAR, AVATAR,
                        8, 8, 8, 8, 64, 64);
            if (hovered) {
                hoverEntry = this;
            }
        }

            @Override
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
                ProfileList.this.setSelected(this);
                selected = this.profile;
                deleteButton.active = true;
                return true;
            }

            @Override
            public Component getNarration() {
                return profile instanceof MsaProfile(MicrosoftAuth.Account acc)
                        ? Component.literal(acc.name())
                        : Component.literal(((OfflineProfile) profile).name());
            }
        }
    }
}
