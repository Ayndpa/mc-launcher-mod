package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherState;

import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 启动器专用的精简版原版选项界面。
 * <p>
 * 原版 OptionsScreen 的那些选项大多依赖真实游戏会话（FOV 影响渲染、Online 是多人联机相关、
 * Skin Customization 改玩家模型、Controls 改操作键位、Chat 改聊天、Telemetry 采集世界数据），
 * 而启动器界面只在主菜单存在，进去也无从体现，因此这里只保留与主菜单场景相关的项：
 * 音乐与声音、语言、资源包、辅助功能、制作人员名单。
 * <p>
 * 这里不继承 OptionsScreen：它的 init() 一次性把 FOV/Online 等按钮全部塞进布局，
 * 且 inWorld=true 时会 requireNonNull(minecraft.level)，主菜单下必然 NPE。
 */
public class LauncherOptionsScreen extends Screen {

    private final Screen parent;
    private final Options options;
    private final HeaderAndFooterLayout layout;

    public LauncherOptionsScreen(Screen parent, Options options) {
        super(Component.literal(I18n.get("mc_launcher.options_screen.title")));
        this.parent = parent;
        this.options = options;
        this.layout = new HeaderAndFooterLayout(this, 61, 33);
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(this.title, this.font);

        GridLayout grid = this.layout.addToContents(new GridLayout());
        grid.defaultCellSetting().alignHorizontallyCenter().paddingHorizontal(4).paddingBottom(4);
        GridLayout.RowHelper rows = grid.createRowHelper(2);

        rows.addChild(openScreenButton(Component.translatable("options.sounds"),
                () -> new net.minecraft.client.gui.screens.options.SoundOptionsScreen(this, this.options)));
        rows.addChild(openScreenButton(Component.translatable("options.language"),
                () -> new net.minecraft.client.gui.screens.options.LanguageSelectScreen(
                        this, this.options, this.minecraft.getLanguageManager())));
        rows.addChild(openScreenButton(Component.translatable("options.resourcepack"),
                () -> new net.minecraft.client.gui.screens.packs.PackSelectionScreen(
                        this.minecraft.getResourcePackRepository(), this::applyPacks,
                        this.minecraft.getResourcePackDirectory(), Component.translatable("resourcePack.title"))));
        rows.addChild(openScreenButton(Component.translatable("options.accessibility"),
                () -> new LauncherAccessibilityScreen(this, this.options)));
        rows.addChild(openScreenButton(Component.translatable("options.credits_and_attribution"),
                () -> new net.minecraft.client.gui.screens.CreditsAndAttributionScreen(this)));
        rows.addChild(openScreenButton(Component.literal(I18n.get("mc_launcher.java_manager")),
                () -> new JavaManagerScreen(this, LauncherState.getChosenJava())));

        this.layout.addToFooter(Button.builder(Component.literal(I18n.get("mc_launcher.options_screen.done")),
                        b -> this.onClose())
                .width(200).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private void applyPacks(net.minecraft.server.packs.repository.PackRepository repository) {
        this.options.updateResourcePacks(repository);
        this.minecraft.setScreen(this);
    }

    private Button openScreenButton(Component label, Supplier<Screen> target) {
        return Button.builder(label, b -> this.minecraft.setScreen(target.get())).build();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        Toasts.draw(g, delta);
    }

    @Override
    public void removed() {
        this.options.save();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
