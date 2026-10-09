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
import java.util.List;

/**
 * Java 版本管理子界面：自动搜索系统中的 Java（常见目录 + JAVA_HOME + 游戏目录），
 * 列表展示路径/版本/厂商，选中后设为启动用的 Java。 */
public class JavaManagerScreen extends Screen {
    private final Screen parent;
    private JavaList list;
    private boolean searching = false;
    private JavaInstall selected;
    private Path chosenJava; // 启动时优先使用的 java.exe

    public JavaManagerScreen(Screen parent, Path currentChosen) {
        super(Component.literal(I18n.get("mc_launcher.java.title")));
        this.parent = parent;
        this.chosenJava = currentChosen;
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

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java.auto_search")), b -> startSearch())
                .bounds(cx - 154, this.height - 72, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java.add_path")), b -> addByInputDialog())
                .bounds(cx - 50, this.height - 72, 100, 20).build());
        Button choose = Button.builder(Component.literal(I18n.get("mc_launcher.java.use_this")), b -> useSelected())
                .bounds(cx + 54, this.height - 72, 100, 20).build();
        addRenderableWidget(choose);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.java.done")), b -> this.onClose())
                .bounds(cx - 50, this.height - 44, 100, 20).build());

        // 优先用上次持久化的结果，缓存为空才自动搜索
        List<JavaInstall> cached = LauncherCore.loadCachedJavas();
        if (!cached.isEmpty()) {
            list.replaceAll(cached);
        } else {
            startSearch();
        }
    }

    private void startSearch() {
        if (searching) {
            return;
        }
        searching = true;
        list.replaceAll(List.of());
        Thread t = new Thread(() -> {
            List<JavaInstall> found = LauncherCore.searchJavas();
            list.replaceAll(found);
            LauncherCore.saveJavas(found);
            searching = false;
        }, "mc-launcher-mod-javasearch");
        t.setDaemon(true);
        t.start();
    }

    private void addByInputDialog() {
        // 简化: 弹出系统输入框不可用（headless），用内置输入行
        this.minecraft.setScreen(new JavaPathInputScreen(this, path -> {
            if (path != null && Files.exists(path)) {
                LauncherCore.addJavaDir(path);
                Toasts.success(I18n.get("mc_launcher.java.added", path));
                startSearch();
            } else {
                Toasts.error(I18n.get("mc_launcher.java.path_not_exists"));
            }
        }));
    }

    private void useSelected() {
        if (selected == null) {
            Toasts.warn(I18n.get("mc_launcher.java.select_first"));
            return;
        }
        chosenJava = selected.exe();
        LauncherState.setChosenJava(chosenJava);
        Toasts.success(I18n.get("mc_launcher.java.chosen", selected.exe()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, I18n.get("mc_launcher.java.title"), cx, top - 16, 0xFFFFFFFF);
        String cur = chosenJava != null ? chosenJava.toString()
                : I18n.get("mc_launcher.java.current_default");
        g.centeredText(this.font, I18n.get("mc_launcher.java.current", cur), cx, top - 2, 0xFFAAAAAA);

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

        void replaceAll(List<JavaInstall> javas) {
            this.clearEntries();
            for (JavaInstall j : javas) {
                this.addEntry(new Entry(j));
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
                boolean chosen = chosenJava != null && chosenJava.equals(java.exe());
                int color = JavaList.this.getSelected() == this ? 0xFF55FF55 : (chosen ? 0xFF7FDFFF : 0xFFFFFFFF);
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
                selected = this.java;
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(java.exe().toString());
            }
        }
    }
}
