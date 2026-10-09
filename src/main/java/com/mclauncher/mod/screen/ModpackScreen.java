package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.Instances;
import com.mclauncher.mod.ModpackInstaller;
import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.ModpackInstaller.ModpackMeta;
import com.mclauncher.mod.VersionDownloader;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 整合包安装界面：输入 .mrpack 路径与目标实例，解析 → 解压 overrides →
 * 下载 mods → 安装 Fabric profile → 下载 client/libraries/assets（复用 VersionDownloader）。
 */
public class ModpackScreen extends Screen {
    private static final int BAR_W = 300;
    private static final int BAR_H = 8;

    private final Screen parent;

    private EditBox pathBox;
    private EditBox instanceBox;
    private Button installBtn;
    private Button cancelBtn;

    /** null = 尚未开始；非空表示流程已启动 */
    private volatile Thread worker;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile VersionDownloader downloader;

    private String status = I18n.get("mc_launcher.modpack.idle");
    private float progress;
    private VersionDownloader.State state = VersionDownloader.State.IDLE;
    private String installedVersionId;

    public ModpackScreen(Screen parent) {
        super(Component.literal(I18n.get("mc_launcher.modpack.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(40, this.height / 4);

        pathBox = new EditBox(this.font, cx - 150, top + 8, 300, 18,
                Component.literal(I18n.get("mc_launcher.modpack.path_hint")));
        pathBox.setHint(Component.literal(I18n.get("mc_launcher.modpack.path_placeholder")));
        pathBox.setMaxLength(500);
        addRenderableWidget(pathBox);
        this.setInitialFocus(pathBox);

        instanceBox = new EditBox(this.font, cx - 150, top + 34, 200, 18,
                Component.literal(I18n.get("mc_launcher.instance.box_hint")));
        instanceBox.setHint(Component.literal(I18n.get("mc_launcher.instance.hint")));
        instanceBox.setMaxLength(48);
        addRenderableWidget(instanceBox);

        installBtn = Button.builder(Component.literal(I18n.get("mc_launcher.modpack.install")), b -> startInstall())
                .bounds(cx - 104, top + 62, 100, 20).build();
        addRenderableWidget(installBtn);

        cancelBtn = Button.builder(Component.literal(I18n.get("mc_launcher.download.cancel")), b -> cancelInstall())
                .bounds(cx + 4, top + 62, 100, 20).build();
        cancelBtn.active = false;
        addRenderableWidget(cancelBtn);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.download.back")),
                        b -> this.minecraft.setScreen(parent))
                .bounds(cx - 50, this.height - 20, 100, 20).build());
    }

    private void startInstall() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        String raw = pathBox.getValue().trim();
        // 去掉资源管理器复制的引号
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        Path mrpack = Path.of(raw);
        if (!Files.isRegularFile(mrpack)) {
            Toasts.error(I18n.get("mc_launcher.modpack.file_missing", raw));
            return;
        }

        String instanceName = instanceBox.getValue().trim();
        Instances.Instance inst;
        if (instanceName.isEmpty()) {
            inst = Instances.scan().get(0);
        } else {
            instanceName = instanceName.replace('/', '_').replace('\\', '_');
            inst = new Instances.Instance(instanceName,
                    Instances.instancesRoot().resolve("instances").resolve(instanceName));
        }
        final Instances.Instance target = inst;
        final Path pack = mrpack;

        cancelled.set(false);
        installBtn.active = false;
        cancelBtn.active = true;
        state = VersionDownloader.State.RUNNING;
        status = I18n.get("mc_launcher.modpack.parsing");
        progress = 0f;

        worker = new Thread(() -> runInstall(pack, target), "mc-launcher-mod-modpack");
        worker.setDaemon(true);
        worker.start();
    }

    private void runInstall(Path mrpack, Instances.Instance inst) {
        try {
            ModpackMeta meta = ModpackInstaller.parse(mrpack);
            if (!"fabric".equals(meta.loaderType()) && !"quilt".equals(meta.loaderType())) {
                fail(I18n.get("mc_launcher.modpack.unsupported_loader", meta.loaderType()));
                return;
            }
            status = I18n.get("mc_launcher.modpack.parsed", meta.name(), meta.gameVersion(), meta.loaderVersion());
            progress = 0.02f;

            Files.createDirectories(inst.dir());
            ModpackInstaller.ProgressListener bridge = new ProgressListenerBridge();
            ModpackInstaller.extractOverrides(mrpack, inst.dir(), bridge);
            progress = 0.05f;

            VersionDownloader.State[] stateRef = { VersionDownloader.State.RUNNING };
            if (!ModpackInstaller.downloadPackFiles(meta, inst.dir(), bridge, stateRef)) {
                state = stateRef[0];
                installBtn.active = true;
                cancelBtn.active = false;
                return;
            }

            // 版本目录名冲突处理：packDirectoryName 与 Instances.freeVersionDir 同思路
            String dirName = ModpackInstaller.packDirectoryName(meta);
            Path versionBase = inst.dir().resolve("versions").resolve(dirName);
            if (Files.exists(versionBase)) {
                for (int i = 1; i < 1000; i++) {
                    String candidate = dirName + "(" + i + ")";
                    if (!Files.exists(inst.dir().resolve("versions").resolve(candidate))) {
                        dirName = candidate;
                        break;
                    }
                }
            }

            String versionId = ModpackInstaller.installLoaderProfile(meta, inst.dir(), dirName, bridge);
            installedVersionId = versionId;

            // 复用 VersionDownloader 下载 client jar / libraries / assets
            progress = 0.33f;
            status = I18n.get("mc_launcher.modpack.phase_version");
            LauncherCore.InstalledVersion v = ModpackInstaller.toInstalledVersion(inst.dir(), versionId);
            downloader = new VersionDownloader(inst.dir());
            downloader.prepare(v);
            downloader.start(new VersionDownloader.ProgressListener() {
                @Override
                public void onProgress(long done, long total, int fDone, int fTotal, String file) {
                    progress = ModpackInstaller.versionProgress(done, total);
                }

                @Override
                public void onStateChange(VersionDownloader.State s, String message) {
                    stateRef[0] = s;
                    state = s;
                    status = message;
                    if (s == VersionDownloader.State.DONE) {
                        ModpackScreen.this.finish();
                    } else if (s == VersionDownloader.State.FAILED) {
                        Toasts.error(I18n.get("mc_launcher.download.failed", message));
                    }
                    installBtn.active = true;
                    cancelBtn.active = false;
                }
            });
        } catch (Exception e) {
            fail(I18n.get("mc_launcher.modpack.install_failed", e.getMessage()));
        }
    }

    private void finish() {
        progress = 1f;
        status = I18n.get("mc_launcher.modpack.done", installedVersionId);
        Toasts.success(status);
        installBtn.active = true;
        cancelBtn.active = false;
    }

    private void fail(String message) {
        state = VersionDownloader.State.FAILED;
        status = message;
        Toasts.error(message);
        installBtn.active = true;
        cancelBtn.active = false;
    }

    private void cancelInstall() {
        cancelled.set(true);
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
        }
        if (downloader != null) {
            downloader.cancel();
        }
        state = VersionDownloader.State.CANCELLED;
        status = I18n.get("mc_launcher.download.cancelled");
        cancelBtn.active = false;
        installBtn.active = true;
    }

    @Override
    public void tick() {
        cancelBtn.active = worker != null && worker.isAlive() && state != VersionDownloader.State.DONE;
        installBtn.active = !(worker != null && worker.isAlive());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, I18n.get("mc_launcher.modpack.title"), cx, top - 16, 0xFFFFFFFF);

        int by = top + 96;
        float frac = Math.max(0f, Math.min(1f, progress));
        g.fill(cx - BAR_W / 2, by, cx + BAR_W / 2, by + BAR_H, 0xFF222222);
        g.fill(cx - BAR_W / 2, by, cx - BAR_W / 2 + (int) (BAR_W * frac), by + BAR_H, 0xFF3B82F6);

        String pct = String.format("%.1f%%", frac * 100);
        g.centeredText(this.font, pct, cx, by + 12, 0xFFFFFFFF);

        int stateColor = switch (state) {
            case DONE -> 0xFF55FF55;
            case FAILED -> 0xFFFF5555;
            case PAUSED -> 0xFFFFFF55;
            case CANCELLED -> 0xFFFFAA00;
            default -> 0xFFFFFFFF;
        };
        String st = status.length() > 80 ? status.substring(0, 80) + "…" : status;
        g.centeredText(this.font, "[" + stateLabel(state) + "] " + st, cx, by + 30, stateColor);

        Toasts.draw(g, delta);
    }

    private String stateLabel(VersionDownloader.State s) {
        return switch (s) {
            case IDLE -> I18n.get("mc_launcher.download.state_idle");
            case RUNNING -> I18n.get("mc_launcher.download.state_running");
            case PAUSED -> I18n.get("mc_launcher.download.state_paused");
            case DONE -> I18n.get("mc_launcher.download.state_done");
            case FAILED -> I18n.get("mc_launcher.download.state_failed");
            case CANCELLED -> I18n.get("mc_launcher.download.state_cancelled");
        };
    }

    /** ModpackInstaller 用的简单进度桥（onProgress 更新状态栏） */
    private class ProgressListenerBridge implements ModpackInstaller.ProgressListener {
        @Override
        public void onProgress(String phase, float percent) {
            status = phase;
            progress = Math.max(progress, percent);
        }

        @Override
        public void onStateChange(VersionDownloader.State s, String message) {
            state = s;
            status = message;
        }
    }

    @Override
    public void onClose() {
        cancelInstall();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
