package com.mclauncher.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modrinth 整合包（.mrpack）安装器。
 * <p>
 * .mrpack 本质是 zip：modrinth.index.json 描述依赖文件（远程下载），
 * overrides/ 目录整体解压到实例根目录（无 overrides/ 时读 overrides.zip）。
 * 加载器支持 Fabric（含 Quilt 兼容路径），Forge 不支持。
 * <p>
 * 安装步骤：
 * <ol>
 *   <li>解析 modrinth.index.json（名称 / 版本 / MC 版本 / loader 版本 / 文件清单）</li>
 *   <li>解压 overrides 到实例目录</li>
 *   <li>下载 index.files 中 client 端必需的文件（mods/config/... 相对实例根）</li>
 *   <li>从 Fabric meta 拉 profile JSON 写入 versions/&lt;id&gt;/&lt;id&gt;.json</li>
 *   <li>交由 {@link VersionDownloader} 下载 client jar / libraries / assets</li>
 * </ol>
 */
public final class ModpackInstaller {

    /** index.files 里的一个依赖文件 */
    public record PackFile(String path, String downloadUrl, String sha1, long size) {
    }

    /** 解析出的整合包元数据 */
    public record ModpackMeta(String name, String versionId, String gameVersion,
                              String loaderType, String loaderVersion, List<PackFile> files) {
    }

    /** 安装进度回调（phase 为已本地化文案；percent 0~1） */
    public interface ProgressListener {
        void onProgress(String phase, float percent);

        default void onStateChange(VersionDownloader.State state, String message) {
        }
    }

    private static final String INDEX_ENTRY = "modrinth.index.json";

    /** i18n 安全封装：语言系统未就绪（如早期启动）时回退到 key 本身 */
    private static String text(String key) {
        try {
            return I18n.get(key);
        } catch (Throwable t) {
            return key;
        }
    }

    private static String text(String key, Object... args) {
        try {
            return I18n.get(key, args);
        } catch (Throwable t) {
            return key;
        }
    }

    private ModpackInstaller() {
    }

    /** 解析 .mrpack 内的 modrinth.index.json；loader 仅支持 fabric */
    public static ModpackMeta parse(Path mrpack) throws IOException {
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry entry = zip.getEntry(INDEX_ENTRY);
            if (entry == null) {
                throw new IOException(text("mc_launcher.modpack.not_mrpack"));
            }
            JsonObject root;
            try (InputStream in = zip.getInputStream(entry)) {
                root = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
            if (root.has("game") && !"minecraft".equals(root.get("game").getAsString())) {
                throw new IOException(text("mc_launcher.modpack.not_mrpack"));
            }

            String name = root.has("name") ? root.get("name").getAsString() : mrpack.getFileName().toString();
            String versionId = root.has("versionId") ? root.get("versionId").getAsString() : "1.0";
            String gameVersion = "";
            String loaderType = "";
            String loaderVersion = "";
            if (root.has("dependencies")) {
                JsonObject deps = root.getAsJsonObject("dependencies");
                for (var e : deps.entrySet()) {
                    switch (e.getKey()) {
                        case "minecraft" -> gameVersion = e.getValue().getAsString();
                        case "fabric-loader" -> {
                            loaderType = "fabric";
                            loaderVersion = e.getValue().getAsString();
                        }
                        case "quilt-loader" -> {
                            loaderType = "quilt";
                            loaderVersion = e.getValue().getAsString();
                        }
                        default -> {
                            // forge / neoforge 等暂不支持
                        }
                    }
                }
            }

            List<PackFile> files = new ArrayList<>();
            if (root.has("files") && root.get("files").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("files")) {
                    JsonObject f = el.getAsJsonObject();
                    // client 侧不需要的文件（server-only）跳过
                    if (f.has("env")) {
                        JsonObject env = f.getAsJsonObject("env");
                        if (env.has("client") && "unsupported".equals(env.get("client").getAsString())) {
                            continue;
                        }
                    }
                    if (!f.has("path") || !f.has("downloads")) {
                        continue;
                    }
                    String url = f.getAsJsonArray("downloads").size() > 0
                            ? f.getAsJsonArray("downloads").get(0).getAsString() : null;
                    if (url == null || url.isEmpty()) {
                        continue;
                    }
                    String sha1 = f.has("hashes") && f.getAsJsonObject("hashes").has("sha1")
                            ? f.getAsJsonObject("hashes").get("sha1").getAsString() : null;
                    long size = f.has("fileSize") ? f.get("fileSize").getAsLong() : -1;
                    files.add(new PackFile(f.get("path").getAsString(), url, sha1, size));
                }
            }
            return new ModpackMeta(name, versionId, gameVersion, loaderType, loaderVersion, files);
        }
    }

    /** 实例内的版本目录名（用于 versions/<名>/ 与启动选择），冲突自动加 (1) (2)… */
    public static String packDirectoryName(ModpackMeta meta) {
        String raw = sanitize(meta.name()) + "-" + sanitize(meta.versionId());
        // 目录名会出现在版本 id 里，启动参数按空白拆分，空格必须去掉
        return raw.replaceAll("\\s+", "_");
    }

    private static String sanitize(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return out.isEmpty() ? "modpack" : out;
    }

    /** 解压 overrides/（或 overrides.zip）到实例根目录 */
    public static void extractOverrides(Path mrpack, Path instanceDir, ProgressListener listener) throws IOException {
        listener.onProgress(text("mc_launcher.modpack.phase_overrides"), 0.05f);
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            // 单文件 overrides.zip 形式（CurseForge 风格转包常见）
            ZipEntry nested = zip.getEntry("overrides.zip");
            if (nested != null) {
                Path tmp = Files.createTempFile("mrpack-overrides", ".zip");
                try (InputStream in = zip.getInputStream(nested)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                try (ZipFile overrides = new ZipFile(tmp.toFile())) {
                    extractZipInto(overrides, instanceDir);
                }
                Files.deleteIfExists(tmp);
                return;
            }
            // overrides/ 目录形式
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().startsWith("overrides/")) {
                    continue;
                }
                String rel = e.getName().substring("overrides/".length());
                if (rel.isEmpty()) {
                    continue;
                }
                Path target = instanceDir.resolve(rel).normalize();
                if (!target.startsWith(instanceDir.normalize())) {
                    continue; // 防路径穿越
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void extractZipInto(ZipFile zip, Path dest) throws IOException {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            Path target = dest.resolve(e.getName()).normalize();
            if (!target.startsWith(dest.normalize())) {
                continue;
            }
            if (e.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 下载 index.files 中尚不存在的文件（带 sha1 校验），返回成功与否 */
    public static boolean downloadPackFiles(ModpackMeta meta, Path instanceDir,
                                            ProgressListener listener,
                                            VersionDownloader.State[] outState) {
        List<PackFile> todo = new ArrayList<>();
        for (PackFile f : meta.files()) {
            Path dest = instanceDir.resolve(f.path()).normalize();
            if (isFileComplete(dest, f.sha1(), f.size())) {
                continue;
            }
            todo.add(f);
        }
        int total = todo.size();
        for (int i = 0; i < total; i++) {
            PackFile f = todo.get(i);
            listener.onProgress(text("mc_launcher.modpack.phase_mods", i + 1, total,
                            f.path()), 0.05f + 0.25f * (i / (float) Math.max(1, total)));
            try {
                downloadOne(f, instanceDir.resolve(f.path()));
            } catch (Exception e) {
                outState[0] = VersionDownloader.State.FAILED;
                listener.onStateChange(VersionDownloader.State.FAILED,
                        text("mc_launcher.modpack.file_failed", f.path(), e.getMessage()));
                return false;
            }
            if (Thread.currentThread().isInterrupted()) {
                outState[0] = VersionDownloader.State.CANCELLED;
                listener.onStateChange(VersionDownloader.State.CANCELLED,
                        text("mc_launcher.download.cancelled"));
                return false;
            }
        }
        return true;
    }

    private static boolean isFileComplete(Path dest, String sha1, long size) {
        try {
            if (!Files.exists(dest)) {
                return false;
            }
            if (size >= 0 && Files.size(dest) != size) {
                return false;
            }
            return sha1 == null || sha1.equalsIgnoreCase(sha1Of(dest));
        } catch (Exception e) {
            return false;
        }
    }

    private static void downloadOne(PackFile f, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Files.deleteIfExists(part);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpURLConnection conn = open(f.downloadUrl());
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                }
                if (f.sha1() != null && !f.sha1().equalsIgnoreCase(sha1Of(part))) {
                    Files.deleteIfExists(part);
                    throw new IOException("sha1 mismatch");
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception e) {
                if (attempt == 3) {
                    throw e;
                }
                Thread.sleep(1000L * attempt);
            }
        }
    }

    /** 拉 Fabric profile JSON 并写入 versions/<dirName>/<dirName>.json，返回版本 id */
    public static String installLoaderProfile(ModpackMeta meta, Path instanceDir, String dirName,
                                              ProgressListener listener) throws Exception {
        listener.onProgress(text("mc_launcher.modpack.phase_profile"), 0.32f);
        String loaderEndpoint = "quilt".equals(meta.loaderType()) ? "quilt" : "fabric";
        String profileUrl = "https://meta." + loaderEndpoint + "mc.net/v2/versions/loader/"
                + meta.gameVersion() + "/" + meta.loaderVersion() + "/profile/json";
        String body = LauncherCore.fetchVersionJson(profileUrl);
        JsonObject profile = JsonParser.parseString(body).getAsJsonObject();

        Path versionDir = instanceDir.resolve("versions").resolve(dirName);
        Files.createDirectories(versionDir);
        // 目录名（可能带 (1) 后缀）才是启动器真正索引的 id
        profile.addProperty("id", dirName);
        Path jsonPath = versionDir.resolve(dirName + ".json");
        Files.writeString(jsonPath, LauncherCore.GSON.toJson(profile));
        return dirName;
    }

    /** 安装完成后的版本实例，交给 VersionDownloader 补齐 client jar / libraries / assets */
    public static LauncherCore.InstalledVersion toInstalledVersion(Path instanceDir, String dirName) {
        Path jsonPath = instanceDir.resolve("versions").resolve(dirName).resolve(dirName + ".json");
        Path jarPath = instanceDir.resolve("versions").resolve(dirName).resolve(dirName + ".jar");
        return new LauncherCore.InstalledVersion(dirName, jsonPath, jarPath);
    }

    /** 由 VersionDownloader 回调桥接（下载器负责 33%~100% 段） */
    public static float versionProgress(long doneBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return 0.33f;
        }
        return 0.33f + 0.67f * Math.min(1f, (float) ((double) doneBytes / totalBytes));
    }

    private static String sha1Of(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 与 VersionDownloader.open 相同的代理策略 */
    private static HttpURLConnection open(String url) throws IOException {
        IOException last = null;
        for (Proxy p : ProxyConfig.httpProxies()) {
            try {
                URLConnection c = URI.create(url).toURL().openConnection(p);
                c.setConnectTimeout(8000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "mc-launcher-mod/1.0");
                return (HttpURLConnection) c;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no route");
    }
}
