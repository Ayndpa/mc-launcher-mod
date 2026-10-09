package com.mclauncher.mod.screen;

import com.mclauncher.mod.I18n;
import com.mclauncher.mod.LauncherCore;
import com.mclauncher.mod.LauncherState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * 仿原版存档加载页（LevelLoadingScreen）的启动过渡界面：
 * 居中标题 + 200px 黑底绿色进度条，阶段化推进进度。
 * 启动失败时回到主菜单并弹出错误 Toast；成功后隐藏启动器窗口，
 * 等目标 Minecraft 进程退出后再恢复显示。 */
public class VersionLaunchScreen extends Screen {
    /** 进度条几何（对齐原版 LevelLoadingScreen.drawProgressBar） */
    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 2;
    private static final int COLOR_BACK = 0xFF000000;
    private static final int COLOR_FILL = 0xFF00FF00;

    private final Screen parent;
    private final String targetVersion;

    private volatile String stageText = I18n.get("mc_launcher.launch.preparing");
    private volatile float progress = 0f;
    private volatile java.lang.Process process;

    public VersionLaunchScreen(Screen parent, String targetVersion) {
        super(Component.literal(I18n.get("mc_launcher.launch.title")));
        this.parent = parent;
        this.targetVersion = targetVersion;
    }

    @Override
    protected void init() {
        Thread t = new Thread(this::runLaunch, "mc-launcher-mod-launch");
        t.setDaemon(true);
        t.start();
    }

    private void step(float p, String text) {
        progress = p;
        stageText = text;
    }

    private void runLaunch() {
        Minecraft mc = Minecraft.getInstance();
        try {
            // 1. 版本存在性
            step(0.10f, I18n.get("mc_launcher.launch.check_version", targetVersion));
            var opt = LauncherCore.findInstalled(targetVersion);
            if (opt.isEmpty()) {
                fail(I18n.get("mc_launcher.launch.version_not_installed", targetVersion));
                return;
            }
            LauncherCore.InstalledVersion v = opt.get();

            // 2. client jar
            step(0.25f, I18n.get("mc_launcher.launch.check_client_jar"));
            if (v.jarPath() == null) {
                fail(I18n.get("mc_launcher.launch.missing_client_jar", targetVersion));
                return;
            }
            sleep(200);

            // 3. Java 检查
            step(0.40f, I18n.get("mc_launcher.launch.check_java"));
            Path java = LauncherState.getChosenJava();
            if (java == null) {
                java = findDefaultJava();
            }
            if (java == null || !Files.exists(java)) {
                fail(I18n.get("mc_launcher.launch.no_java"));
                return;
            }
            sleep(200);

            // 4. 组装启动参数并拉起进程
            step(0.60f, I18n.get("mc_launcher.launch.build_args"));
            String username = currentUsername(mc);
            LauncherCore.LaunchResult r = LauncherCore.launch(targetVersion, java, username);
            if (!r.ok()) {
                fail(r.message());
                return;
            }
            process = r.process();

            // 5. 监控进程：远古版本等不兼容环境会让新进程几秒内崩溃，
            //    只有存活确认通过才算成功，否则回主菜单报错
            step(0.80f, I18n.get("mc_launcher.launch.confirm_alive"));
            if (process != null) {
                String err = LauncherCore.confirmAlive(process, 6000);
                if (err != null) {
                    String detail = LauncherCore.processOutput(process);
                    fail(detail.isEmpty() ? err : err + "\n" + detail);
                    return;
                }
            }

            // 6. 完成：隐藏当前启动器窗口并等待目标进程退出，
            //    玩家退出 Minecraft 进程后再恢复显示启动器（不再自动退出）
            step(1.00f, I18n.get("mc_launcher.launch.launched", targetVersion));
            sleep(800);
            if (process != null) {
                hideAndWatch(process, targetVersion);
            } else {
                fail(I18n.get("mc_launcher.launch.failed", "process == null"));
            }
        } catch (Exception e) {
            fail(I18n.get("mc_launcher.launch.failed", e.getMessage()));
        }
    }

    /**
     * 隐藏启动器窗口，后台线程等待目标 Minecraft 进程结束，
     * 进程退出后在渲染线程恢复窗口并回到启动器主界面。
     */
    private void hideAndWatch(java.lang.Process proc, String version) {
        Minecraft mc = Minecraft.getInstance();
        // 在渲染线程最小化窗口（GLFW 调用需在主线程），
        // 并暂停声音系统，避免音乐在后台继续播放
        mc.execute(() -> {
            org.lwjgl.glfw.GLFW.glfwIconifyWindow(mc.getWindow().handle());
            mc.getSoundManager().stop();
        });

        Thread watcher = new Thread(() -> {
            try {
                proc.waitFor();
            } catch (InterruptedException ignored) {
            }
            mc.execute(() -> {
                long handle = mc.getWindow().handle();
                org.lwjgl.glfw.GLFW.glfwRestoreWindow(handle);
                org.lwjgl.glfw.GLFW.glfwShowWindow(handle);
                org.lwjgl.glfw.GLFW.glfwFocusWindow(handle);
                mc.getSoundManager().resume();
                Toasts.info(I18n.get("mc_launcher.launch.game_exited", version));
                mc.setScreen(parent);
            });
        }, "mc-launcher-mod-process-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private String currentUsername(Minecraft mc) {
        String offlineName = com.mclauncher.mod.LauncherState.getActiveOfflineName();
        if (offlineName != null) {
            return offlineName;
        }
        com.mclauncher.mod.MicrosoftAuth.Account acc = com.mclauncher.mod.MicrosoftAuth.getAccount();
        if (acc != null) {
            return acc.name();
        }
        return mc.getUser().getName();
    }

    private Path findDefaultJava() {
        // 未手动选择时：优先游戏目录 java/ 下的 JDK，其次系统 java
        Path bundled = Minecraft.getInstance().gameDirectory.toPath().resolve("java");
        if (Files.isDirectory(bundled)) {
            try (var s = Files.list(bundled)) {
                List<Path> dirs = s.filter(Files::isDirectory).toList();
                for (Path dir : dirs) {
                    Path exe = dir.resolve("bin").resolve("java.exe");
                    if (Files.exists(exe)) {
                        return exe;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        Path sys = Path.of("C:\\Windows\\System32\\java.exe");
        return Files.exists(sys) ? sys : Path.of("java");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 失败处理：回主菜单 + 错误 Toast，绝不退出游戏 */
    private void fail(String message) {
        progress = 0f;
        Minecraft.getInstance().execute(() -> {
            Toasts.error(message);
            Minecraft.getInstance().setScreen(parent);
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // 原版加载页是纯色/模糊背景，不画全景
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int cy = this.height / 2;
        int textTop = cy - 50;

        g.centeredText(this.font, Component.literal(I18n.get("mc_launcher.launch.starting", targetVersion)), cx, textTop, 0xFFFFFFFF);
        g.centeredText(this.font, Component.literal(stageText), cx, textTop + 22, 0xFFAAAAAA);

        // 进度条：与原版 LevelLoadingScreen.drawProgressBar 一致（黑底 + 绿色填充）
        float smoothed = Mth.clamp(progress, 0f, 1f);
        int left = cx - BAR_WIDTH / 2;
        int top = textTop + this.font.lineHeight + 12;
        g.fill(left, top, left + BAR_WIDTH, top + BAR_HEIGHT, COLOR_BACK);
        g.fill(left, top, left + Math.round(smoothed * (float) BAR_WIDTH), top + BAR_HEIGHT, COLOR_FILL);

        g.centeredText(this.font, (int) (smoothed * 100) + "%", cx, top + 10, 0xFFAAAAAA);

        Toasts.draw(g, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
