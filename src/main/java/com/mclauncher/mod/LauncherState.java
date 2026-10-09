package com.mclauncher.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.minecraft.client.Minecraft;

/** 各子界面与主界面之间共享的选择状态；代理等配置持久化到游戏目录 launcher-cache/config.properties */
public final class LauncherState {
    /** 代理模式：DIRECT 直连 / SYSTEM 跟随系统代理 / CUSTOM 自定义代理 */
    public enum ProxyMode {
        DIRECT("直连"),
        SYSTEM("系统代理"),
        CUSTOM("自定义代理");

        public final String label;

        ProxyMode(String label) {
            this.label = label;
        }

        static ProxyMode of(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return SYSTEM;
            }
        }
    }

    private static String selectedVersion;
    private static Path chosenJava;

    private static volatile ProxyMode proxyMode = ProxyMode.SYSTEM;
    private static volatile String proxyHost = "127.0.0.1";
    private static volatile int proxyPort = 7897;

    private static final String CONFIG_FILE = "launcher-cache/config.properties";

    private LauncherState() {
    }

    // ---------- 持久化 ----------

    public static void load() {
        try {
            Path f = configPath();
            if (!Files.exists(f)) {
                return;
            }
            Properties p = new Properties();
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            }
            proxyMode = ProxyMode.of(p.getProperty("proxy.mode", ProxyMode.SYSTEM.name()));
            proxyHost = p.getProperty("proxy.host", proxyHost);
            try {
                proxyPort = Integer.parseInt(p.getProperty("proxy.port", String.valueOf(proxyPort)));
            } catch (NumberFormatException ignored) {
            }
        } catch (IOException ignored) {
        }
    }

    public static void save() {
        try {
            Path f = configPath();
            Files.createDirectories(f.getParent());
            Properties p = new Properties();
            p.setProperty("proxy.mode", proxyMode.name());
            p.setProperty("proxy.host", proxyHost);
            p.setProperty("proxy.port", String.valueOf(proxyPort));
            try (var out = Files.newOutputStream(f)) {
                p.store(out, "mc-launcher-mod config");
            }
        } catch (IOException ignored) {
        }
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CONFIG_FILE);
    }

    // ---------- 代理 ----------

    public static ProxyMode getProxyMode() {
        return proxyMode;
    }

    public static void setProxyMode(ProxyMode m) {
        proxyMode = m;
    }

    public static String getProxyHost() {
        return proxyHost;
    }

    public static void setProxyHost(String h) {
        proxyHost = h;
    }

    public static int getProxyPort() {
        return proxyPort;
    }

    public static void setProxyPort(int port) {
        proxyPort = port;
    }

    // ---------- 版本 / Java ----------

    public static String getSelectedVersion() {
        return selectedVersion;
    }

    public static void setSelectedVersion(String v) {
        selectedVersion = v;
    }

    public static Path getChosenJava() {
        return chosenJava;
    }

    public static void setChosenJava(Path p) {
        chosenJava = p;
    }
}
