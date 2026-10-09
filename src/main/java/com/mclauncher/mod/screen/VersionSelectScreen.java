package com.mclauncher.mod.screen;

import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherCore.Category;
import com.mclauncher.mod.LauncherCore.ManifestEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 获取版本页面（本地版本 → 获取版本）：展示 BMCLAPI 远程清单，
 * 分类过滤，选中后进入下载界面。
 */
public class VersionSelectScreen extends Screen {
    private final Screen parent;
    private final String runningVersion;
    private Category category = Category.RELEASE;
    private VersionList list;
    private final List<Button> categoryButtons = new ArrayList<>();
    private List<ManifestEntry> allManifest = List.of();
    private boolean loading = true;
    private ManifestEntry selected;

    public VersionSelectScreen(Screen parent, String runningVersion) {
        super(Component.literal("获取版本"));
        this.parent = parent;
        this.runningVersion = runningVersion;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        // 分类按钮行
        Category[] cats = Category.values();
        int btnW = 86;
        int gap = 6;
        int totalW = cats.length * btnW + (cats.length - 1) * gap;
        int startX = cx - totalW / 2;
        for (int i = 0; i < cats.length; i++) {
            Category c = cats[i];
            Button b = Button.builder(Component.literal(c.label), btn -> {
                        category = c;
                        selected = null;
                        updateCategoryButtons();
                        refreshList();
                    })
                    .bounds(startX + i * (btnW + gap), top, btnW, 20)
                    .build();
            categoryButtons.add(b);
            addRenderableWidget(b);
        }
        updateCategoryButtons();

        // 版本列表
        int listTop = top + 28;
        int listBottom = this.height - 56;
        list = new VersionList(this.minecraft, 260, listBottom - listTop, listTop, 24);
        list.setX((this.width - 260) / 2);
        addRenderableWidget(list);

        // 底部按钮
        addRenderableWidget(Button.builder(Component.literal("下载"), b -> openDownload())
                .bounds(cx - 154, this.height - 44, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新列表"), b -> {
                    loading = true;
                    allManifest = List.of();
                    refreshList();
                })
                .bounds(cx - 50, this.height - 44, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.minecraft.setScreen(parent))
                .bounds(cx + 54, this.height - 44, 100, 20).build());

        // 后台拉取清单
        if (allManifest.isEmpty() && loading) {
            Thread t = new Thread(() -> {
                try {
                    allManifest = LauncherCore.fetchManifest();
                } catch (Exception e) {
                    Toasts.error("清单获取失败: " + e.getMessage());
                }
                loading = false;
            }, "mc-launcher-mod-manifest");
            t.setDaemon(true);
            t.start();
        }
        refreshList();
    }

    private void openDownload() {
        if (selected != null) {
            this.minecraft.setScreen(new DownloadScreen(this, selected, runningVersion));
        }
    }

    private void updateCategoryButtons() {
        for (Button b : categoryButtons) {
            b.active = !b.getMessage().getString().equals(category.label);
        }
    }

    private void refreshList() {
        if (list == null) {
            return;
        }
        List<ManifestEntry> entries = LauncherCore.filterManifest(allManifest, category);
        list.replaceEntries(entries);
    }

    @Override
    public void tick() {
        if (!loading && list != null && list.itemCount() == 0 && allManifest != null && !allManifest.isEmpty()) {
            refreshList();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, "获取版本", cx, top - 16, 0xFFFFFFFF);

        if (loading) {
            g.centeredText(this.font, "正在获取版本清单…", cx, top + 60, 0xFFAAAAAA);
        }

        if (selected != null) {
            g.centeredText(this.font, "已选择: " + selected.id(), cx, this.height - 58, 0xFF55FF55);
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

    // ---------- 列表 ----------

    private class VersionList extends ObjectSelectionList<VersionList.Entry> {

        VersionList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void replaceEntries(List<ManifestEntry> entries) {
            this.clearEntries();
            List<Entry> list = new ArrayList<>();
            List<String> installedIds = LauncherCore.scanInstalled().stream()
                    .map(LauncherCore.InstalledVersion::id).toList();
            for (ManifestEntry e : entries) {
                list.add(new Entry(e, installedIds.contains(e.id())));
            }
            for (Entry e : list) {
                this.addEntry(e);
            }
        }

        int itemCount() {
            return this.children().size();
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final ManifestEntry entry;
            final boolean installed;

            Entry(ManifestEntry entry, boolean installed) {
                this.entry = entry;
                this.installed = installed;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
                int color = VersionList.this.getSelected() == this ? 0xFF55FF55
                        : (entry.id().equals(runningVersion) ? 0xFFFFAA00
                                : (installed ? 0xFF7FDFFF : 0xFFFFFFFF));
                int left = this.getContentX();
                int top = this.getContentY();
                String suffix = entry.id().equals(runningVersion) ? "  [运行中]" : (installed ? "  [已安装]" : "");
                g.text(VersionSelectScreen.this.font,
                        entry.id() + suffix,
                        left + 4, top + 3,
                        color);
                g.text(VersionSelectScreen.this.font,
                        typeLabel(entry.type()),
                        left + 4, top + 13,
                        0xFF888888);
            }

            private String typeLabel(String type) {
                return switch (type) {
                    case "release" -> "正式版";
                    case "snapshot" -> "快照";
                    case "old_beta" -> "远古 Beta";
                    case "old_alpha" -> "远古 Alpha";
                    default -> type;
                };
            }

            @Override
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
                VersionList.this.setSelected(this);
                selected = this.entry;
                if (doubled) {
                    openDownload();
                }
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(entry.id());
            }
        }
    }
}
