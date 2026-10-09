package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.Instances;
import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherCore.ManifestEntry;
import com.mclauncher.mod.VersionDownloader;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 版本下载界面：并发数设置 + 暂停/继续/取消，实时进度显示。
 * 下载 client jar、libraries、asset index 及全部资源（断点续传）。
 * 目标实例由 InstanceNameInputScreen 输入（null = 当前实例）；
 * 实例内目录名冲突时自动追加 (1) (2) …
 */
public class DownloadScreen extends Screen {
    private final Screen parent;
    private final ManifestEntry entry;
    /** 目标实例名；null = 当前实例 */
    private final String instanceName;

    private VersionDownloader downloader;
    private Instances.Instance targetInstance;
    private String status = I18n.get("mc_launcher.download.preparing");
    private VersionDownloader.State state = VersionDownloader.State.IDLE;
    private long doneBytes;
    private long totalBytes;
    private int filesDone;
    private int filesTotal;
    private String currentFile = "";

    private EditBox concurrencyBox;
    private Button pauseBtn;
    private Button resumeBtn;
    private Button cancelBtn;
    private boolean prepared = false;
    private boolean prepareFailed = false;

    public DownloadScreen(Screen parent, ManifestEntry entry, String instanceName) {
        super(Component.literal(I18n.get("mc_launcher.download.title", entry.id())));
        this.parent = parent;
        this.entry = entry;
        this.instanceName = instanceName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        concurrencyBox = new EditBox(this.font, cx + 40, top + 8, 60, 18, Component.literal(I18n.get("mc_launcher.download.concurrency")));
        concurrencyBox.setValue("4");
        concurrencyBox.setMaxLength(2);
        concurrencyBox.setResponder(s -> {
            try {
                int n = Integer.parseInt(s.trim());
                if (downloader != null) {
                    downloader.setMaxConcurrency(n);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(concurrencyBox);

        pauseBtn = Button.builder(Component.literal(I18n.get("mc_launcher.download.pause")), b -> {
                    if (downloader != null) {
                        downloader.pause();
                    }
                })
                .bounds(cx - 154, this.height - 44, 100, 20).build();
        addRenderableWidget(pauseBtn);

        resumeBtn = Button.builder(Component.literal(I18n.get("mc_launcher.download.resume")), b -> {
                    if (downloader != null) {
                        downloader.resume(listener);
                    }
                })
                .bounds(cx - 50, this.height - 44, 100, 20).build();
        addRenderableWidget(resumeBtn);

        cancelBtn = Button.builder(Component.literal(I18n.get("mc_launcher.download.cancel")), b -> {
                    if (downloader != null) {
                        downloader.cancel();
                    }
                })
                .bounds(cx + 54, this.height - 44, 100, 20).build();
        addRenderableWidget(cancelBtn);

        addRenderableWidget(Button.builder(Component.literal(I18n.get("mc_launcher.download.back")), b -> this.minecraft.setScreen(parent))
                .bounds(cx - 50, this.height - 20, 100, 20).build());

        if (!prepared && !prepareFailed) {
            prepared = true;
            Thread t = new Thread(() -> {
                try {
                    // 实例名 null = 当前实例；否则 instancesRoot()/<实例名>/（不存在则自动创建）
                    Instances.Instance inst = instanceName == null
                            ? Instances.scan().get(0)
                            : new Instances.Instance(instanceName,
                                    Instances.instancesRoot().resolve(instanceName));
                    targetInstance = inst;
                    // 冲突时自动 26.3 → 26.3(1) → 26.3(2)…
                    Path versionDir = Instances.freeVersionDir(inst, entry.id());
                    Files.createDirectories(versionDir);
                    String dirName = versionDir.getFileName().toString();
                    downloader = new VersionDownloader(inst.dir());
                    String url = entry.url();
                    String body = LauncherCore.fetchVersionJson(url);
                    Path jsonPath = versionDir.resolve(dirName + ".json");
                    java.nio.file.Files.writeString(jsonPath, body);
                    LauncherCore.InstalledVersion v = new LauncherCore.InstalledVersion(
                            dirName, jsonPath,
                            jsonPath.getParent().resolve(dirName + ".jar"));
                    downloader.prepare(v);
                    downloader.setMaxConcurrency(parseConcurrency());
                    downloader.start(listener);
                } catch (Exception e) {
                    prepareFailed = true;
                    Toasts.error(I18n.get("mc_launcher.download.prepare_failed", e.getMessage()));
                }
            }, "mc-launcher-mod-dlprepare");
            t.setDaemon(true);
            t.start();
        }
    }

    private int parseConcurrency() {
        try {
            return Integer.parseInt(concurrencyBox.getValue().trim());
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    private final VersionDownloader.ProgressListener listener = new VersionDownloader.ProgressListener() {
        @Override
        public void onProgress(long done, long total, int fDone, int fTotal, String file) {
            doneBytes = done;
            totalBytes = total;
            filesDone = fDone;
            filesTotal = fTotal;
            currentFile = file;
        }

        @Override
        public void onStateChange(VersionDownloader.State s, String message) {
            state = s;
            status = message;
            if (s == VersionDownloader.State.FAILED) {
                Toasts.error(I18n.get("mc_launcher.download.failed", message));
            } else if (s == VersionDownloader.State.DONE) {
                Toasts.success(I18n.get("mc_launcher.download.done", entry.id()));
            } else if (s == VersionDownloader.State.CANCELLED) {
                Toasts.warn(I18n.get("mc_launcher.download.cancelled"));
            }
        }
    };

    @Override
    public void tick() {
        pauseBtn.active = state == VersionDownloader.State.RUNNING;
        resumeBtn.active = state == VersionDownloader.State.PAUSED
                || state == VersionDownloader.State.FAILED
                || state == VersionDownloader.State.CANCELLED;
        cancelBtn.active = state == VersionDownloader.State.RUNNING
                || state == VersionDownloader.State.PAUSED;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.extractPanorama(g, delta);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(36, this.height / 6);

        g.centeredText(this.font, I18n.get("mc_launcher.download.title", entry.id()), cx, top - 16, 0xFFFFFFFF);

        // 并发数标签
        g.text(this.font, I18n.get("mc_launcher.download.concurrency_label"), cx - 8, top + 13, 0xFFAAAAAA);

        // 进度
        int barW = 300;
        int barH = 8;
        int bx = cx - barW / 2;
        int by = top + 60;
        float frac = totalBytes > 0 ? (float) doneBytes / totalBytes : 0f;
        g.fill(bx, by, bx + barW, by + barH, 0xFF222222);
        g.fill(bx, by, bx + (int) (barW * frac), by + barH, 0xFF3B82F6);

        String pct = String.format("%.1f%%", frac * 100);
        String sizeLine = human(doneBytes) + " / " + human(totalBytes) + "  " + pct;
        g.centeredText(this.font, sizeLine, cx, by + 14, 0xFFFFFFFF);
        g.centeredText(this.font, I18n.get("mc_launcher.download.files", filesDone, filesTotal), cx, by + 28, 0xFFAAAAAA);
        if (!currentFile.isEmpty()) {
            String f = currentFile.length() > 60 ? "…" + currentFile.substring(currentFile.length() - 60) : currentFile;
            g.centeredText(this.font, f, cx, by + 42, 0xFF888888);
        }

        int stateColor = switch (state) {
            case DONE -> 0xFF55FF55;
            case FAILED -> 0xFFFF5555;
            case PAUSED -> 0xFFFFFF55;
            case CANCELLED -> 0xFFFFAA00;
            default -> 0xFFFFFFFF;
        };
        g.centeredText(this.font, "[" + stateLabel(state) + "] " + status, cx, by + 60, stateColor);

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

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1fMB", bytes / 1024.0 / 1024);
        }
        return String.format("%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }

    @Override
    public void onClose() {
        if (downloader != null && (state == VersionDownloader.State.RUNNING
                || state == VersionDownloader.State.PAUSED)) {
            downloader.cancel();
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
