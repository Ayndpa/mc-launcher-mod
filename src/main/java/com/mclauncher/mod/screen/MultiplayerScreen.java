package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 启动器的“多人游戏”页面：继承原版 JoinMultiplayerScreen，从而完整复用服务器列表、
 * 条目渲染、状态 ping、favicon 等观感，但只允许查看与增删服务器，不允许加入。
 * <p>
 * 阻断加入的关键是覆写 {@link #join(ServerData)}：原版 OnlineServerEntry / NetworkServerEntry
 * 的所有加入路径（双击条目、点条目右侧加入图标、列表内按 Enter、LAN 条目）最终都会调用它，
 * 因此在这里不调用 super / ConnectScreen 即可彻底禁止进入任何服务器。
 * 同理覆写 {@link #keyPressed(KeyEvent)}，避免 F5 刷新时重建出原版界面绕过限制。
 */
public class MultiplayerScreen extends JoinMultiplayerScreen {

    private final Screen parent;
    private final HeaderAndFooterLayout layout;
    private final ServerList servers;
    private final ServerStatusPinger pinger;

    private Button addButton;
    private Button editButton;
    private Button deleteButton;
    private Button infoButton;

    public MultiplayerScreen(Screen parent) {
        super(parent);
        this.parent = parent;
        this.layout = new HeaderAndFooterLayout(this, 33, 60);
        this.servers = new ServerList(Minecraft.getInstance());
        this.servers.load();
        this.pinger = new ServerStatusPinger();
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(Component.literal(I18n.get("mc_launcher.multiplayer.title")), this.font);
        this.serverSelectionList = this.layout.addToContents(new ServerSelectionList(this, this.minecraft,
                this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight(), 36));
        this.serverSelectionList.updateOnlineServers(this.servers);

        LinearLayout column = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
        column.defaultCellSetting().alignHorizontallyCenter();

        LinearLayout row1 = column.addChild(LinearLayout.horizontal().spacing(4));
        this.addButton = row1.addChild(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer.add")), b -> addServer())
                .width(74).build());
        this.editButton = row1.addChild(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer.edit")), b -> editServer())
                .width(74).build());
        this.deleteButton = row1.addChild(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer.delete")), b -> deleteServer())
                .width(74).build());

        LinearLayout row2 = column.addChild(LinearLayout.horizontal().spacing(4));
        this.infoButton = row2.addChild(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer.info")), b -> showInfo())
                .width(74).build());
        row2.addChild(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer.refresh")), b -> refresh())
                .width(74).build());
        row2.addChild(Button.builder(Component.literal(I18n.get("mc_launcher.multiplayer.back")), b -> this.minecraft.setScreen(this.parent))
                .width(74).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        this.onSelectedChange();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.serverSelectionList != null) {
            this.serverSelectionList.updateSize(this.width, this.layout);
        }
    }

    @Override
    public void tick() {
        this.pinger.tick();
    }

    @Override
    public void removed() {
        this.pinger.removeAll();
        if (this.serverSelectionList != null) {
            this.serverSelectionList.removed();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public ServerList getServers() {
        return this.servers;
    }

    @Override
    public ServerStatusPinger getPinger() {
        return this.pinger;
    }

    /** 列表选中变化时刷新按钮可用性：编辑/删除/详情仅在选中真实服务器条目时可用 */
    @Override
    protected void onSelectedChange() {
        this.addButton.active = true;
        this.editButton.active = false;
        this.deleteButton.active = false;
        this.infoButton.active = false;

        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (selected != null && !(selected instanceof ServerSelectionList.LANHeader)) {
            this.infoButton.active = true;
            if (selected instanceof ServerSelectionList.OnlineServerEntry) {
                this.editButton.active = true;
                this.deleteButton.active = true;
            }
        }
    }

    /**
     * 不调用 super.join / ConnectScreen.startConnecting —— 这是本页面禁止加入服务器的唯一防线。
     */
    @Override
    public void join(ServerData data) {
        Toasts.warn(I18n.get("mc_launcher.multiplayer.join_blocked"));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_F5) {
            this.minecraft.setScreen(new MultiplayerScreen(this.parent));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addServer() {
        ServerData editing = new ServerData("", "", ServerData.Type.OTHER);
        BooleanConsumer callback = confirmed -> {
            if (confirmed) {
                ServerData unhidden = this.servers.unhide(editing.ip);
                if (unhidden != null) {
                    unhidden.copyNameIconFrom(editing);
                    this.servers.save();
                } else {
                    this.servers.add(editing, false);
                    this.servers.save();
                }
                this.serverSelectionList.setSelected(null);
                this.serverSelectionList.updateOnlineServers(this.servers);
            }
            this.minecraft.setScreen(this);
        };
        this.minecraft.setScreen(new ManageServerScreen(this, Component.literal(I18n.get("mc_launcher.multiplayer.add_title")), callback, editing));
    }

    private void editServer() {
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (!(selected instanceof ServerSelectionList.OnlineServerEntry entry)) {
            Toasts.warn(I18n.get("mc_launcher.multiplayer.no_selection"));
            return;
        }
        ServerData original = entry.getServerData();
        ServerData editing = new ServerData(original.name, original.ip, ServerData.Type.OTHER);
        editing.copyFrom(original);
        BooleanConsumer callback = confirmed -> {
            if (confirmed) {
                original.name = editing.name;
                original.ip = editing.ip;
                original.copyFrom(editing);
                this.servers.save();
                this.serverSelectionList.updateOnlineServers(this.servers);
            }
            this.minecraft.setScreen(this);
        };
        this.minecraft.setScreen(new ManageServerScreen(this, Component.literal(I18n.get("mc_launcher.multiplayer.edit_title")), callback, editing));
    }

    private void deleteServer() {
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (!(selected instanceof ServerSelectionList.OnlineServerEntry entry)) {
            Toasts.warn(I18n.get("mc_launcher.multiplayer.no_selection"));
            return;
        }
        ServerData data = entry.getServerData();
        BooleanConsumer callback = confirmed -> {
            if (confirmed) {
                this.servers.remove(data);
                this.servers.save();
                this.serverSelectionList.setSelected(null);
                this.serverSelectionList.updateOnlineServers(this.servers);
            }
            this.minecraft.setScreen(this);
        };
        this.minecraft.setScreen(new ConfirmScreen(callback, Component.literal(I18n.get("mc_launcher.multiplayer.delete_title")),
                Component.literal(I18n.get("mc_launcher.multiplayer.delete_question", data.name)),
                Component.literal(I18n.get("mc_launcher.multiplayer.delete")), CommonComponents.GUI_CANCEL));
    }

    private void showInfo() {
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (!(selected instanceof ServerSelectionList.OnlineServerEntry entry)) {
            Toasts.warn(I18n.get("mc_launcher.multiplayer.no_selection"));
            return;
        }
        this.minecraft.setScreen(new ServerInfoScreen(this, entry.getServerData()));
    }

    private void refresh() {
        this.minecraft.setScreen(new MultiplayerScreen(this.parent));
    }
}
