package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherCore.InstalledVersion;
import com.mclauncher.mod.LauncherState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 本地版本页面：列出 versions/ 下已安装的版本，选中即设为启动版本。
 * “获取版本”进入远程清单（下载）子页面。
 */
public class LocalVersionsScreen extends Screen {
    private final Screen parent;
    private VersionList list;
    private InstalledVersion selected;

    public LocalVersionsScreen(Screen parent) {
        super(Component.literal(I18n.get("mc_launcher.local_versions.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        int listTop = top + 12;
        int listBottom = this.height - 56;
        list = new VersionList(this.minecraft, 300, listBottom - listTop, listTop, 24);
        list.setX((this.width - 300) / 2);
        addRenderableWidget(list);
        reload();

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.local_versions.get_versions")), b -> this.minecraft
                        .setScreen(new VersionSelectScreen(this)))
                .bounds(cx - 154, this.height - 44, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.local_versions.ok")), b -> confirm())
                .bounds(cx - 50, this.height - 44, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.local_versions.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx + 54, this.height - 44, 100, 20).build());
    }

    private void reload() {
        list.replaceAll(LauncherCore.scanInstalled());
    }

    private void confirm() {
        if (selected != null) {
            LauncherState.setSelectedVersion(selected.id());
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, I18n.get("mc_launcher.local_versions.title"), cx, top - 16, 0xFFFFFFFF);

    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- 列表 ----------

    private class VersionList extends ObjectSelectionList<VersionList.Entry> {

        VersionList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void replaceAll(List<InstalledVersion> versions) {
            this.clearEntries();
            for (InstalledVersion v : versions) {
                this.addEntry(new Entry(v));
            }
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final InstalledVersion version;

            Entry(InstalledVersion version) {
                this.version = version;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
                boolean isSelected = LauncherState.getSelectedVersion() != null
                        && LauncherState.getSelectedVersion().equals(version.id());
                int color = VersionList.this.getSelected() == this ? 0xFF55FF55
                        : (isSelected ? 0xFF7FDFFF : 0xFFFFFFFF);
                int left = this.getContentX();
                int top = this.getContentY();
                String suffix = isSelected ? I18n.get("mc_launcher.local_versions.pending") : "";
                g.text(LocalVersionsScreen.this.font, version.id() + suffix, left + 4, top + 3, color);
                String detail = version.jarPath() != null ? I18n.get("mc_launcher.local_versions.installed")
                        : I18n.get("mc_launcher.local_versions.missing_jar");
                g.text(LocalVersionsScreen.this.font, detail, left + 4, top + 13, 0xFF888888);
            }

            @Override
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
                VersionList.this.setSelected(this);
                selected = this.version;
                if (doubled) {
                    confirm();
                }
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(version.id());
            }
        }
    }
}
