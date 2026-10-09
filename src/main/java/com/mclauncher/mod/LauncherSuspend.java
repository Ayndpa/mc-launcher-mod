package com.mclauncher.mod;

/**
 * 启动器挂起标志的独立持有类（Mixin 类不能有 public 字段）。
 * VersionLaunchScreen 在隐藏宿主窗口时置位，MinecraftSuspendMixin 每帧读取。 */
public final class LauncherSuspend {
    /** true = 宿主 MC 的 runTick 被冻结（真实游戏运行中） */
    public static volatile boolean suspended;

    private LauncherSuspend() {
    }
}
