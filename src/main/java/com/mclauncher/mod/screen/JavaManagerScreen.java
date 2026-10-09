package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherCore.JavaInstall;
import com.mclauncher.mod.LauncherState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 版本管理子界面：自动搜索系统中的 Java（常见目录 + JAVA_HOME + 游戏目录）。
 * 列表按优先级排序（仿资源包设置页）：越靠上优先级越高，启动时按游戏版本要求
 * 自动从上到下选第一个满足 majorVersion 的 Java。选中条目后用 ↑/↓ 按钮调整顺序。 */
public class JavaManagerScreen extends Screen {
    private final Screen parent;
    private JavaList list;
    private boolean searching = false;
    private Path selected;
    private Button upBtn;
    private Button downBtn;
    /** 搜索结果里的版本/厂商信息，用于渲染（key = exe 路径） */
    private final List<JavaInstall> infos = new ArrayList<>();

    public JavaManagerScreen(Screen parent, Path currentChosen) {
        super(Component.literal(I18n.get("mc_launcher.java.title")));
        this.parent = parent;
        if (currentChosen != null && LauncherState.getJavaPriority().isEmpty()) {
            // 兼容旧配置：原有单一选择迁移为优先级列表首位
            LauncherState.setChosenJava(currentChosen);
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        int listTop = top + 24;
        int listBottom = this.height - 84;
        list = new JavaList(this.minecraft, 380, listBottom - listTop, listTop, 24);
        list.setX((this.width - 380) / 2);
        addRenderableWidget(list);

        // 底部一行四个按钮：搜索 / 添加 / ↑ / ↓（↑↓ 与列表同宽右对齐，不再悬空）
        int rowY = this.height - 72;
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java.auto_search")), b -> startSearch())
                .bounds(cx - 190, rowY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java.add_path")), b -> addByInputDialog())
                .bounds(cx - 86, rowY, 100, 20).build());
        upBtn = Button.builder(Component.literal("↑"), b -> moveSelected(true))
                .bounds(cx + 18, rowY, 80, 20).build();
        addRenderableWidget(upBtn);
        downBtn = Button.builder(Component.literal("↓"), b -> moveSelected(false))
                .bounds(cx + 102, rowY, 80, 20).build();
        addRenderableWidget(downBtn);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java.done")), b -> this.onClose())
                .bounds(cx - 50, this.height - 44, 100, 20).build());

        // 先用上次搜索缓存填充版本/厂商信息（立即显示），再按需自动搜索
        infos.addAll(LauncherCore.loadCachedJavas());
        if (LauncherState.getJavaPriority().isEmpty()) {
            startSearch();
        } else {
            reload();
        }
    }

    /** 用优先级列表 + 缓存的搜索信息重建列表；优先级里没有的 Java 追加到末尾 */
    private void reload() {
        List<Path> prio = LauncherState.getJavaPriority();
        List<JavaInstall> all = new ArrayList<>(infos);
        for (Path exe : prio) {
            if (all.stream().noneMatch(j -> j.exe().equals(exe))) {
                all.add(new JavaInstall(exe, "?", ""));
            }
        }
        list.replaceAll(prio, all);
        updateButtons();
    }

    private void startSearch() {
        if (searching) {
            return;
        }
        searching = true;
        Thread t = new Thread(() -> {
            List<JavaInstall> found = LauncherCore.searchJavas();
            infos.clear();
            infos.addAll(found);
            LauncherCore.saveJavas(found);
            // 搜索结果并入优先级列表（已有条目保持原位，新发现的追加到末尾）
            List<Path> prio = LauncherState.getJavaPriority();
            boolean changed = false;
            for (JavaInstall j : found) {
                if (!prio.contains(j.exe())) {
                    prio.add(j.exe());
                    changed = true;
                }
            }
            if (changed) {
                LauncherState.setJavaPriority(prio);
            }
            searching = false;
            Minecraft.getInstance().execute(this::reload);
        }, "mc-launcher-mod-javasearch");
        t.setDaemon(true);
        t.start();
    }

    private void addByInputDialog() {
        this.minecraft.setScreen(new JavaPathInputScreen(this, path -> {
            if (path != null && Files.exists(path)) {
                LauncherCore.addJavaDir(path);
                Toasts.success(I18n.get("mc_launcher.java.added", path));
                Path exe = Files.isRegularFile(path) ? path : path.resolve("bin").resolve("java.exe");
                if (Files.exists(exe) && LauncherState.getJavaPriority().stream().noneMatch(exe::equals)) {
                    List<Path> prio = new ArrayList<>(LauncherState.getJavaPriority());
                    prio.add(exe);
                    LauncherState.setJavaPriority(prio);
                }
                startSearch();
            } else {
                Toasts.error(I18n.get("mc_launcher.java.path_not_exists"));
            }
        }));
    }

    private void moveSelected(boolean up) {
        if (selected == null) {
            Toasts.warn(I18n.get("mc_launcher.java.select_first"));
            return;
        }
        boolean moved = up ? LauncherState.moveJavaUp(selected) : LauncherState.moveJavaDown(selected);
        if (moved) {
            reload();
        }
    }

    private void updateButtons() {
        if (upBtn == null || downBtn == null) {
            return;
        }
        List<Path> prio = LauncherState.getJavaPriority();
        int i = selected == null ? -1 : prio.indexOf(selected);
        upBtn.active = i > 0;
        downBtn.active = i >= 0 && i < prio.size() - 1;
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, I18n.get("mc_launcher.java.title"), cx, top - 16, 0xFFFFFFFF);
        g.centeredText(this.font, I18n.get("mc_launcher.java.priority_hint"), cx, top - 2, 0xFFAAAAAA);

        if (searching) {
            g.centeredText(this.font, I18n.get("mc_launcher.java.searching"), cx, top + 60, 0xFFAAAAAA);
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

    private class JavaList extends ObjectSelectionList<JavaList.Entry> {

        JavaList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        /** 按优先级顺序显示；prio 之外的 Java（新搜索到的）追加在后面 */
        void replaceAll(List<Path> prio, List<JavaInstall> all) {
            this.clearEntries();
            for (Path exe : prio) {
                JavaInstall info = all.stream().filter(j -> j.exe().equals(exe)).findFirst()
                        .orElse(new JavaInstall(exe, "?", ""));
                this.addEntry(new Entry(info));
            }
            for (JavaInstall j : all) {
                if (!prio.contains(j.exe())) {
                    this.addEntry(new Entry(j));
                }
            }
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final JavaInstall java;

            Entry(JavaInstall java) {
                this.java = java;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
                int color = JavaList.this.getSelected() == this ? 0xFF55FF55 : 0xFFFFFFFF;
                int left = this.getContentX();
                int top = this.getContentY();
                String label = I18n.get("mc_launcher.java.label", java.version())
                        + (java.vendor().isEmpty() ? "" : " (" + java.vendor() + ")");
                g.text(JavaManagerScreen.this.font, label, left + 4, top + 3, color);
                String path = java.exe().toString();
                if (path.length() > 60) {
                    path = "…" + path.substring(path.length() - 60);
                }
                g.text(JavaManagerScreen.this.font, path, left + 4, top + 13, 0xFF888888);
            }

            @Override
            public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
                JavaList.this.setSelected(this);
                selected = this.java.exe();
                updateButtons();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(java.exe().toString());
            }
        }
    }
}
