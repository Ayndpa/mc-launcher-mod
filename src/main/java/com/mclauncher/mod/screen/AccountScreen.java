package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherState;
import com.mclauncher.mod.MicrosoftAuth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 账户管理子界面：以列表形式展示全部档案（微软正版 + 离线档案），
 * 支持新增微软登录 / 新增离线档案 / 切换激活档案 / 删除档案。
 * 皮肤模型实时反映当前选中的档案。 */
public class AccountScreen extends Screen {
    private final Screen parent;
    private ProfileList list;
    private PlayerSkinWidget skinWidget;
    private Button deleteButton;

    /** 列表条目：正版档案或离线档案 */
    private sealed interface Profile permits MsaProfile, OfflineProfile {
    }

    private record MsaProfile(MicrosoftAuth.Account acc) implements Profile {
    }

    private record OfflineProfile(String name) implements Profile {
    }

    /** 列表选中项；skinUuid 变化时重建皮肤组件 */
    private Profile selected;
    private String skinAccountUuid = "";
    private String skinOfflineName = "";

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
        list = new ProfileList(this.minecraft, 380, listBottom - listTop, listTop, 24);
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

        // 皮肤组件放列表右侧
        skinWidget = buildSkinWidget(Math.max(6, cx + 200 + 6), top + 24);
        addRenderableWidget(skinWidget);
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
        skinAccountUuid = "";
        skinOfflineName = "";
    }

    private PlayerSkinWidget buildSkinWidget(int x, int y) {
        PlayerSkinWidget w = new PlayerSkinWidget(88, 132, this.minecraft.getEntityModels(), this::currentSkinSupplier);
        w.setX(x);
        w.setY(y);
        return w;
    }

    private net.minecraft.world.entity.player.PlayerSkin currentSkinSupplier() {
        Minecraft mc = Minecraft.getInstance();
        if (!skinAccountUuid.isEmpty()) {
            com.mojang.authlib.GameProfile profile =
                    new com.mojang.authlib.GameProfile(parseUuidShort(skinAccountUuid), skinDisplayName());
            return mc.getSkinManager().createLookup(profile, false).get();
        }
        return mc.getSkinManager().createLookup(mc.getGameProfile(), false).get();
    }

    /** 选中档案的显示名（皮肤供应器用） */
    private String skinDisplayName() {
        if (selected instanceof OfflineProfile(String name)) {
            return name;
        }
        if (selected instanceof MsaProfile(MicrosoftAuth.Account acc)) {
            return acc.name();
        }
        return Minecraft.getInstance().getUser().getName();
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
    public void tick() {
        super.tick();
        // 选中项变化时重建皮肤组件（供应器捕获的是构造时的名称/uuid）
        String uuid = selected instanceof MsaProfile(MicrosoftAuth.Account acc) ? acc.uuid() : "";
        String offline = selected instanceof OfflineProfile(String name) ? name : "";
        if (!uuid.equals(skinAccountUuid) || !offline.equals(skinOfflineName)) {
            skinAccountUuid = uuid;
            skinOfflineName = offline;
            if (skinWidget != null) {
                int x = skinWidget.getX();
                int y = skinWidget.getY();
                removeWidget(skinWidget);
                skinWidget = buildSkinWidget(x, y);
                addRenderableWidget(skinWidget);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
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
                if (profile instanceof MsaProfile(MicrosoftAuth.Account acc)) {
                    String label = I18n.get("mc_launcher.account.entry_msa", acc.name())
                            + (isActive() ? I18n.get("mc_launcher.account.active_tag") : "");
                    g.text(AccountScreen.this.font, label, left + 4, top + 3, color);
                    g.text(AccountScreen.this.font, I18n.get("mc_launcher.account.uuid", formatUuid(acc.uuid())),
                            left + 4, top + 13, 0xFF888888);
                } else if (profile instanceof OfflineProfile(String name)) {
                    String label = I18n.get("mc_launcher.account.entry_offline", name)
                            + (isActive() ? I18n.get("mc_launcher.account.active_tag") : "");
                    g.text(AccountScreen.this.font, label, left + 4, top + 3, color);
                    g.text(AccountScreen.this.font, I18n.get("mc_launcher.account.offline_tag"),
                            left + 4, top + 13, 0xFF888888);
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
