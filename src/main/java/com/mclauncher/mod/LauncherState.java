package com.mclauncher.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    /** Java 优先级列表（越靠前优先级越高），启动时按游戏版本要求自动匹配 */
    private static List<Path> javaPriority = new ArrayList<>();

    /** 当前生效的档案：activeOfflineName 非空 → 离线档案；否则用 activeMsaUuid 指向的正版账号 */
    private static String activeMsaUuid;
    private static String activeOfflineName;

    private static final String OFFLINE_FILE = "launcher-cache/offline-profiles.json";

    private static volatile ProxyMode proxyMode = ProxyMode.SYSTEM;
    private static volatile String proxyHost = "127.0.0.1";
    private static volatile int proxyPort = 7897;

    /** 资源精简：主菜单只加载必需素材（方块/物品纹理、模型、大体积音效等不加载） */
    private static volatile boolean slimResources = true;

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
            String v = p.getProperty("selected.version", "");
            if (!v.isBlank()) {
                selectedVersion = v;
            }
            slimResources = Boolean.parseBoolean(p.getProperty("slim.resources", "true"));
            String jp = p.getProperty("java.priority", "");
            if (!jp.isBlank()) {
                List<Path> list = new ArrayList<>();
                for (String s : jp.split("\\|")) {
                    if (!s.isBlank() && Files.exists(Path.of(s))) {
                        list.add(Path.of(s));
                    }
                }
                javaPriority = list;
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
            p.setProperty("slim.resources", String.valueOf(slimResources));
            p.setProperty("java.priority", String.join("|",
                    javaPriority.stream().map(Path::toString).toList()));
            if (selectedVersion != null && !selectedVersion.isBlank()) {
                p.setProperty("selected.version", selectedVersion);
            }
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

    // ---------- 资源精简 ----------

    public static boolean isSlimResources() {
        return slimResources;
    }

    /** 切换后需要重启游戏（或触发一次资源重载）才生效 */
    public static void setSlimResources(boolean b) {
        slimResources = b;
        save();
    }

    // ---------- 版本 / Java ----------

    public static String getSelectedVersion() {
        return selectedVersion;
    }

    public static void setSelectedVersion(String v) {
        selectedVersion = v;
        save();
    }

    public static Path getChosenJava() {
        return javaPriority.isEmpty() ? null : javaPriority.get(0);
    }

    public static void setChosenJava(Path p) {
        if (p == null) {
            return;
        }
        javaPriority.remove(p);
        javaPriority.add(0, p);
        save();
    }

    // ---------- Java 优先级 ----------

    /** 优先级列表（索引越小优先级越高） */
    public static List<Path> getJavaPriority() {
        return new ArrayList<>(javaPriority);
    }

    public static void setJavaPriority(List<Path> list) {
        javaPriority = new ArrayList<>(list);
        save();
    }

    /** 把 exe 上移一位；已在顶部则忽略 */
    public static boolean moveJavaUp(Path exe) {
        int i = javaPriority.indexOf(exe);
        if (i <= 0) {
            return false;
        }
        javaPriority.remove(i);
        javaPriority.add(i - 1, exe);
        save();
        return true;
    }

    /** 把 exe 下移一位；已在底部则忽略 */
    public static boolean moveJavaDown(Path exe) {
        int i = javaPriority.indexOf(exe);
        if (i < 0 || i >= javaPriority.size() - 1) {
            return false;
        }
        javaPriority.remove(i);
        javaPriority.add(i + 1, exe);
        save();
        return true;
    }

    // ---------- 档案 ----------

    public static String getActiveMsaUuid() {
        return activeMsaUuid;
    }

    public static void setActiveMsaUuid(String uuid) {
        activeMsaUuid = uuid;
        activeOfflineName = null;
    }

    public static String getActiveOfflineName() {
        return activeOfflineName;
    }

    public static void setActiveOfflineName(String name) {
        activeOfflineName = name;
    }

    // ---------- 离线档案 ----------

    /** 离线档案 = 离线用户名。存到 launcher-cache/offline-profiles.json */
    public static List<String> loadOfflineProfiles() {
        try {
            Path f = offlineFile();
            if (!Files.exists(f)) {
                return new ArrayList<>();
            }
            List<String> out = new ArrayList<>();
            for (var el : com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonArray()) {
                out.add(el.getAsString());
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void saveOfflineProfiles(List<String> names) {
        try {
            Path f = offlineFile();
            Files.createDirectories(f.getParent());
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (String n : names) {
                arr.add(n);
            }
            Files.writeString(f, arr.toString());
        } catch (IOException ignored) {
        }
    }

    public static void addOfflineProfile(String name) {
        List<String> all = loadOfflineProfiles();
        all.remove(name);
        all.add(0, name);
        saveOfflineProfiles(all);
    }

    public static void removeOfflineProfile(String name) {
        List<String> all = loadOfflineProfiles();
        all.remove(name);
        saveOfflineProfiles(all);
        if (name.equals(activeOfflineName)) {
            activeOfflineName = null;
        }
    }

    private static Path offlineFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(OFFLINE_FILE);
    }
}
