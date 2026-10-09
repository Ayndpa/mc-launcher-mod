package com.mclauncher.mod;

import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * 版本清单 + 本地版本扫描 + Java 搜索 + 真实启动的底层逻辑。
 */
public final class LauncherCore {
    public static final Gson GSON = new Gson();

    /** BMCLAPI 版本清单（国内镜像），与 Rust bootstrap 同源 */
    private static final String MANIFEST_URL = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json";

    public record ManifestEntry(String id, String type, String url) {
    }

    public enum Category {
        ALL("全部"),
        RELEASE("正式版"),
        SNAPSHOT("快照"),
        OLD("远古版本");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** 已安装的本地版本（versions/<id>/<id>.json 存在） */
    public record InstalledVersion(String id, Path jsonPath, Path jarPath) {
    }

    private static volatile String manifestCacheJson;
    private static volatile List<ManifestEntry> manifestCache;

    private LauncherCore() {
    }

    // ---------- 版本清单 ----------

    /** 按 用户配置的代理模式 依次尝试 */
    private static String httpGet(String url) throws Exception {
        Exception last = null;
        for (ProxySelector sel : ProxyConfig.httpClientSelectors()) {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.ALWAYS);
            if (sel != null) {
                b.proxy(sel);
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> resp = b.build().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                last = new IOException("HTTP " + resp.statusCode());
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no route");
    }
    /** 拉取远程版本清单（失败时回退到本地缓存文件） */
    public static List<ManifestEntry> fetchManifest() throws IOException, InterruptedException {
        List<ManifestEntry> cached = manifestCache;
        if (cached != null) {
            return cached;
        }
        Minecraft mc = Minecraft.getInstance();
        Path cacheFile = mc.gameDirectory.toPath().resolve("launcher-cache").resolve("manifest.json");
        String body;
        try {
            body = httpGet(MANIFEST_URL);
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, body);
        } catch (Exception e) {
            // 网络失败：用上次缓存
            if (Files.exists(cacheFile)) {
                body = Files.readString(cacheFile);
            } else {
                throw new IOException("无法获取版本清单", e);
            }
        }
        manifestCacheJson = body;
        manifestCache = parseManifest(body);
        return manifestCache;
    }

    /** 拉取单个版本的 JSON（供下载器使用） */
    public static String fetchVersionJson(String url) throws Exception {
        return httpGet(url);
    }

    static List<ManifestEntry> parseManifest(String body) {
        List<ManifestEntry> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray versions = root.getAsJsonArray("versions");
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            out.add(new ManifestEntry(
                    v.get("id").getAsString(),
                    v.has("type") ? v.get("type").getAsString() : "release",
                    v.get("url").getAsString()));
        }
        return out;
    }

    public static List<ManifestEntry> filterManifest(List<ManifestEntry> all, Category cat) {
        return all.stream()
                .filter(e -> switch (cat) {
                    case ALL -> true;
                    case RELEASE -> e.type().equals("release");
                    case SNAPSHOT -> e.type().equals("snapshot");
                    case OLD -> e.type().equals("old_beta") || e.type().equals("old_alpha");
                })
                .toList();
    }

    // ---------- 本地已安装版本 ----------

    public static List<InstalledVersion> scanInstalled() {
        List<InstalledVersion> out = new ArrayList<>();
        Path versionsDir = Minecraft.getInstance().gameDirectory.toPath().resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return out;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String id = dir.getFileName().toString();
                Path json = dir.resolve(id + ".json");
                Path jar = dir.resolve(id + ".jar");
                if (Files.exists(json)) {
                    out.add(new InstalledVersion(id, json, Files.exists(jar) ? jar : null));
                }
            });
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(InstalledVersion::id));
        return out;
    }

    public static Optional<InstalledVersion> findInstalled(String id) {
        return scanInstalled().stream().filter(v -> v.id().equals(id)).findFirst();
    }

    /** 从版本 JSON 读取 assetIndex id（继承链向上查） */
    public static String readAssetIndex(InstalledVersion v) {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(v.jsonPath())).getAsJsonObject();
            JsonObject cur = json;
            while (cur != null) {
                if (cur.has("assetIndex")) {
                    return cur.getAsJsonObject("assetIndex").get("id").getAsString();
                }
                if (cur.has("inheritsFrom")) {
                    Path parent = v.jsonPath().getParent().resolve(cur.get("inheritsFrom").getAsString() + ".json");
                    if (!Files.exists(parent)) {
                        break;
                    }
                    cur = JsonParser.parseString(Files.readString(parent)).getAsJsonObject();
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return "30";
    }

    // ---------- Java 管理 ----------

    public record JavaInstall(Path exe, String version, String vendor) {
    }

    private static final List<Path> javaExtraDirs = new ArrayList<>();
    private static volatile boolean javaCacheLoaded = false;
    private static final Object javaCacheLock = new Object();

    private static Path javaCacheFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("launcher-cache").resolve("javas.json");
    }

    /** 从 launcher-cache/javas.json 读回上次搜索结果与手动添加的目录 */
    private static void ensureJavaCacheLoaded() {
        synchronized (javaCacheLock) {
            if (javaCacheLoaded) {
                return;
            }
            javaCacheLoaded = true;
            Path file = javaCacheFile();
            if (!Files.exists(file)) {
                return;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.has("extraDirs")) {
                    for (JsonElement el : root.getAsJsonArray("extraDirs")) {
                        Path dir = Path.of(el.getAsString());
                        if (!javaExtraDirs.contains(dir)) {
                            javaExtraDirs.add(dir);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 上次搜索结果（缓存不存在时返回空列表）；过滤掉已不存在的 java */
    public static List<JavaInstall> loadCachedJavas() {
        ensureJavaCacheLoaded();
        List<JavaInstall> out = new ArrayList<>();
        Path file = javaCacheFile();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("javas")) {
                return out;
            }
            for (JsonElement el : root.getAsJsonArray("javas")) {
                JsonObject o = el.getAsJsonObject();
                Path exe = Path.of(o.get("exe").getAsString());
                if (!Files.exists(exe)) {
                    continue;
                }
                out.add(new JavaInstall(exe,
                        o.has("version") ? o.get("version").getAsString() : "?",
                        o.has("vendor") ? o.get("vendor").getAsString() : ""));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 搜索结果 + 手动添加目录持久化到 launcher-cache/javas.json */
    public static void saveJavas(List<JavaInstall> javas) {
        ensureJavaCacheLoaded();
        try {
            JsonObject root = new JsonObject();
            JsonArray dirs = new JsonArray();
            synchronized (javaExtraDirs) {
                for (Path d : javaExtraDirs) {
                    dirs.add(d.toString());
                }
            }
            root.add("extraDirs", dirs);
            JsonArray arr = new JsonArray();
            for (JavaInstall j : javas) {
                JsonObject o = new JsonObject();
                o.addProperty("exe", j.exe().toString());
                o.addProperty("version", j.version());
                o.addProperty("vendor", j.vendor());
                arr.add(o);
            }
            root.add("javas", arr);
            Path file = javaCacheFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (Exception ignored) {
        }
    }

    public static void addJavaDir(Path dir) {
        ensureJavaCacheLoaded();
        synchronized (javaExtraDirs) {
            if (!javaExtraDirs.contains(dir)) {
                javaExtraDirs.add(dir);
            }
        }
    }

    public static List<Path> javaExtraDirs() {
        return javaExtraDirs;
    }

    /** 自动搜索系统 Java：USN 全盘扫描（NTFS MFT），失败时回退到常用目录 */
    public static List<JavaInstall> searchJavas() {
        List<JavaInstall> out = new ArrayList<>();
        List<String> candidates;
        try {
            candidates = UsnJavaScanner.scan(null);
        } catch (Throwable t) {
            candidates = new ArrayList<>();
        }
        // USN 扫描失败或为空时，兜底常用安装目录
        if (candidates.isEmpty()) {
            candidates = fallbackCandidates();
        }
        for (String path : candidates) {
            Path exe = Path.of(path);
            String[] probe = probeJava(exe);
            if (!probe[0].equals("?")) {
                addUnique(out, exe, probe);
            }
        }
        // 系统 PATH 里的 java
        try {
            Path sysJava = Path.of("C:\\Windows\\System32\\java.exe");
            if (Files.exists(sysJava)) {
                addUnique(out, sysJava, probeJava(sysJava));
            }
        } catch (Exception ignored) {
        }
        out.sort((a, b) -> b.version().compareTo(a.version()));
        return out;
    }

    private static List<String> fallbackCandidates() {
        List<String> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        roots.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
        roots.add(Path.of("C:\\Program Files\\Java"));
        roots.add(Path.of("C:\\Program Files\\Microsoft"));
        roots.add(Path.of("C:\\Program Files\\Zulu"));
        roots.add(Path.of("C:\\Program Files\\Amazon Corretto"));
        roots.add(Path.of("C:\\Program Files\\BellSoft"));
        roots.add(Path.of(home, ".jdks"));
        roots.add(Minecraft.getInstance().gameDirectory.toPath().resolve("java"));
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) {
            roots.add(Path.of(jh).getParent() == null ? Path.of(jh) : Path.of(jh).getParent());
        }
        synchronized (javaExtraDirs) {
            roots.addAll(javaExtraDirs);
        }
        for (Path root : roots) {
            collectJavas(root, out, 0);
        }
        return out;
    }

    private static void collectJavas(Path dir, List<String> out, int depth) {
        if (depth > 3 || dir == null || !Files.isDirectory(dir)) {
            return;
        }
        Path exe = dir.resolve("bin").resolve("java.exe");
        if (Files.exists(exe)) {
            out.add(exe.toString());
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(d -> collectJavas(d, out, depth + 1));
        } catch (IOException ignored) {
        }
    }

    private static void addUnique(List<JavaInstall> list, Path exe, String[] probe) {
        if (list.stream().anyMatch(j -> j.exe().equals(exe))) {
            return;
        }
        list.add(new JavaInstall(exe, probe[0], probe[1]));
    }

    /** 运行 java -version 解析版本号与 vendor */
    private static String[] probeJava(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            String version = "?";
            String vendor = "";
            for (String line : out.split("\n")) {
                if (line.contains("version \"")) {
                    int s = line.indexOf("version \"") + 9;
                    int e = line.indexOf('"', s);
                    if (e > s) {
                        version = line.substring(s, e);
                    }
                }
                if (line.contains("Temurin")) vendor = "Temurin";
                else if (line.contains("Zulu")) vendor = "Zulu";
                else if (line.contains("Corretto")) vendor = "Corretto";
                else if (line.contains("GraalVM")) vendor = "GraalVM";
                else if (line.contains("Microsoft")) vendor = "Microsoft";
            }
            return new String[]{version, vendor};
        } catch (Exception e) {
            return new String[]{"?", ""};
        }
    }
    /** 26.1.2 及以上需要 Java >= 25，粗略按主版本比较 */
    public static boolean isCompatible(String version) {
        try {
            int major = Integer.parseInt(version.split("\\.")[0]);
            return major >= 25;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 真实启动 ----------

    /** 启动结果 */
    public record LaunchResult(boolean ok, String message, Process process) {
        public LaunchResult(boolean ok, String message) {
            this(ok, message, null);
        }
    }

    /**
     * 真实启动所选版本：Spawn 新的 java 进程运行 net.minecraft.client.main.Main。
     * libraries / assets 与当前安装共享；版本未安装 client jar 时提示。
     * 登录了微软正版账号则使用真实 uuid/accessToken，否则回退离线参数。
     */
    public static LaunchResult launch(String versionId, Path javaExe, String username) {
        Optional<InstalledVersion> opt = findInstalled(versionId);
        if (opt.isEmpty()) {
            return new LaunchResult(false, I18n.get("mc_launcher.launch.version_not_installed", versionId));
        }
        InstalledVersion v = opt.get();
        if (v.jarPath() == null) {
            return new LaunchResult(false, I18n.get("mc_launcher.launch.missing_client_jar", versionId));
        }
        // 正版账号：后台线程调用的 launch 中做一次阻塞刷新，保证 token 新鲜
        MicrosoftAuth.Account msa = MicrosoftAuth.getAccount();
        boolean authed = msa != null && MicrosoftAuth.refreshIfNeeded() && MicrosoftAuth.getAccount() != null;
        if (authed) {
            msa = MicrosoftAuth.getAccount();
            username = msa.name();
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Path gameDir = mc.gameDirectory.toPath();
            Path root = gameDir; // bootstrap 结构: gameDir 即 mc/，libraries/assets 都在下面

            // classpath: client.jar + 版本 json 的所有 libraries（继承链）
            List<String> cp = new ArrayList<>();
            cp.add(v.jarPath().toAbsolutePath().toString());
            JsonObject json = JsonParser.parseString(Files.readString(v.jsonPath())).getAsJsonObject();
            addLibraries(json, cp);
            // 继承父版本
            while (json.has("inheritsFrom")) {
                Path parent = v.jsonPath().getParent().resolve(json.get("inheritsFrom").getAsString() + ".json");
                if (!Files.exists(parent)) {
                    break;
                }
                json = JsonParser.parseString(Files.readString(parent)).getAsJsonObject();
                addLibraries(json, cp);
            }
            String sep = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
            String classpath = String.join(sep, cp);

            String assetIndex = readAssetIndex(v);
            String natives = v.jsonPath().getParent().resolve("natives").toString();
            extractNatives(json, v, Path.of(natives));
            Files.createDirectories(Path.of(natives));

            // mainClass 与参数模板来自版本 JSON：远古版本（rd-xxx）的 mainClass 是
            // com.mojang.rubydung.RubyDung 且只有 ${auth_player_name} ${auth_session}，
            // 固定用 net.minecraft.client.main.Main 会 ClassNotFoundException
            String mainClass = json.has("mainClass")
                    ? json.get("mainClass").getAsString()
                    : "net.minecraft.client.main.Main";
            String argsTemplate = json.has("minecraftArguments")
                    ? json.get("minecraftArguments").getAsString()
                    : "--username ${auth_player_name} --version ${version_name} "
                      + "--gameDir ${game_directory} --assetsDir ${assets_root} "
                      + "--assetIndex ${assets_index_name} --uuid ${auth_uuid} "
                      + "--accessToken ${auth_access_token} --clientId ${clientid} "
                      + "--xuid ${xuid} --userType ${user_type} --versionType ${version_name}";

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toAbsolutePath().toString());
            cmd.add("-Djava.library.path=" + natives);
            cmd.add("-Dminecraft.launcher.brand=mc-launcher-mod");
            cmd.add("-Dminecraft.launcher.version=1.0");
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(mainClass);
            String accessToken = authed && msa != null ? msa.accessToken() : "0";
            String uuid = authed && msa != null ? msa.uuid() : "00000000000000000000000000004660";
            String userType = authed && msa != null ? "msa" : "legacy";
            String resolved = argsTemplate
                    .replace("${auth_player_name}", username)
                    .replace("${version_name}", versionId)
                    .replace("${game_directory}", gameDir.toString())
                    .replace("${assets_root}", gameDir.resolve("assets").toString())
                    .replace("${game_assets}", v.jsonPath().getParent().resolve("assets").toString())
                    .replace("${assets_index_name}", assetIndex)
                    .replace("${auth_uuid}", uuid)
                    .replace("${auth_access_token}", accessToken)
                    .replace("${auth_session}", "token:" + accessToken + ":" + uuid)
                    .replace("${auth_xuid}", "0")
                    .replace("${clientid}", "0")
                    .replace("${xuid}", "0")
                    .replace("${user_type}", userType)
                    .replace("${version_type}", "release");
            for (String a : resolved.split("\\s+")) {
                if (!a.isEmpty()) {
                    cmd.add(a);
                }
            }

            Process proc = new ProcessBuilder(cmd).directory(gameDir.toFile()).start();
            return new LaunchResult(true, I18n.get("mc_launcher.launch.launched", versionId), proc);
        } catch (Exception e) {
            return new LaunchResult(false, I18n.get("mc_launcher.launch.failed", e.getMessage()));
        }
    }

    /**
     * 启动后监控新进程是否存活。远古版本等不兼容环境会让新进程几秒内崩溃，
     * 仅凭 ProcessBuilder.start() 成功不能算启动成功。
     * 返回 null 表示存活确认通过；否则返回错误消息。
     */
    public static String confirmAlive(Process proc, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive()) {
                    int code = proc.exitValue();
                    return I18n.get("mc_launcher.launch.exited_early", code);
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** 读取新进程崩溃时的输出（供错误提示） */
    public static String processOutput(Process proc) {
        try {
            byte[] out = proc.getInputStream().readAllBytes();
            String s = new String(out).trim();
            if (s.length() > 300) {
                s = "…" + s.substring(s.length() - 300);
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 versions/<id>/natives/ 下的 natives-*.jar 解出 dll（跳过 META-INF），幂等 */
    private static void extractNatives(JsonObject versionJson, InstalledVersion v, Path nativesDir) {
        try {
            Files.createDirectories(nativesDir);
            Path libsRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("libraries");
            if (!versionJson.has("libraries")) {
                return;
            }
            String osName = System.getProperty("os.name").toLowerCase().contains("win") ? "windows" : "linux";
            for (JsonElement el : versionJson.getAsJsonArray("libraries")) {
                JsonObject lib = el.getAsJsonObject();
                if (!lib.has("natives") || !lib.getAsJsonObject("natives").has(osName)) {
                    continue;
                }
                if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("classifiers")) {
                    continue;
                }
                String classifier = lib.getAsJsonObject("natives").get(osName).getAsString();
                JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                if (!classifiers.has(classifier)) {
                    continue;
                }
                String rel = classifiers.getAsJsonObject(classifier).get("path").getAsString();
                Path jar = libsRoot.resolve(rel);
                if (!Files.exists(jar)) {
                    continue;
                }
                try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var e = entries.nextElement();
                        if (e.isDirectory() || e.getName().contains("META-INF")) {
                            continue;
                        }
                        String fileName = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                        Path target = nativesDir.resolve(fileName);
                        if (Files.exists(target)) {
                            continue;
                        }
                        try (var in = zip.getInputStream(e)) {
                            Files.copy(in, target);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void addLibraries(JsonObject versionJson, List<String> cp) {
        Path libsRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("libraries");
        for (JsonElement el : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            // 简化: 不检查 rules（本地安装时 bootstrap 已下载过对应平台库）
            String name = lib.has("name") ? lib.get("name").getAsString() : null;
            if (name == null) {
                continue;
            }
            // maven 坐标转路径
            String[] parts = name.split(":");
            if (parts.length < 3) {
                continue;
            }
            String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                    + "/" + parts[1] + "-" + parts[2] + ".jar";
            Path f = libsRoot.resolve(path);
            if (Files.exists(f)) {
                String abs = f.toAbsolutePath().toString();
                if (!cp.contains(abs)) {
                    cp.add(abs);
                }
            }
        }
        // natives 目录加到 -Djava.library.path 由调用方处理
    }
}