package com.mclauncher.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.mclauncher.mod.LauncherCore.InstalledVersion;

/**
 * 版本下载器：按版本 JSON 下载 client jar + libraries + assets。
 * 支持：断点续传（Range + .part 文件）、暂停/继续/取消、可设置并发数。
 */
public final class VersionDownloader {

    public enum State { IDLE, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes, int filesDone, int filesTotal, String currentFile);

        void onStateChange(State state, String message);
    }

    /** 单个下载任务 */
    private static class Task {
        final String url;
        final Path dest;
        final long size; // -1 未知
        final String sha1; // null 表示无校验
        final String label;

        Task(String url, Path dest, long size, String sha1, String label) {
            this.url = url;
            this.dest = dest;
            this.size = size;
            this.sha1 = sha1;
            this.label = label;
        }
    }

    private static final int MAX_RETRY = 3;

    private final Path gameDir;
    private final List<Task> tasks = new ArrayList<>();
    private final AtomicLong doneBytes = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicReference<String> currentFile = new AtomicReference<>("");
    private final Object pauseLock = new Object();
    private volatile State state = State.IDLE;
    private volatile int maxConcurrency = 4;
    private volatile String failMessage = "";
    private ExecutorService pool;
    private Thread orchestrator;

    public VersionDownloader(Path gameDir) {
        this.gameDir = gameDir;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int n) {
        this.maxConcurrency = Math.max(1, Math.min(16, n));
    }

    public State getState() {
        return state;
    }

    public String getFailMessage() {
        return failMessage;
    }

    /** 解析版本 JSON，收集全部下载任务（client jar / libraries / assets 索引及资源） */
    public void prepare(InstalledVersion v) throws Exception {
        tasks.clear();
        doneBytes.set(0);
        totalBytes.set(0);
        filesDone.set(0);
        currentFile.set("");
        failMessage = "";
        state = State.IDLE;

        Path versionsDir = gameDir.resolve("versions");
        JsonObject json = JsonParser.parseString(Files.readString(v.jsonPath())).getAsJsonObject();

        // 继承链上的所有版本 JSON
        List<JsonObject> jsons = new ArrayList<>();
        jsons.add(json);
        JsonObject cur = json;
        while (cur.has("inheritsFrom")) {
            Path parent = v.jsonPath().getParent()
                    .resolve(cur.get("inheritsFrom").getAsString() + ".json");
            if (!Files.exists(parent)) {
                break;
            }
            cur = JsonParser.parseString(Files.readString(parent)).getAsJsonObject();
            jsons.add(cur);
        }

        // client jar（取继承链最后一个有 downloads 的）
        for (int i = jsons.size() - 1; i >= 0; i--) {
            JsonObject j = jsons.get(i);
            if (j.has("downloads")) {
                JsonObject client = j.getAsJsonObject("downloads").getAsJsonObject("client");
                if (client != null) {
                    Path jar = v.jarPath() != null ? v.jarPath()
                            : versionsDir.resolve(v.id()).resolve(v.id() + ".jar");
                    addTaskFromDownload(client, jar, "client: " + v.id());
                    break;
                }
            }
        }

        // libraries（含 natives 平台构件）
        Path libsRoot = gameDir.resolve("libraries");
        String osName = System.getProperty("os.name").toLowerCase().contains("win") ? "windows" : "linux";
        for (JsonObject j : jsons) {
            if (!j.has("libraries")) {
                continue;
            }
            for (JsonElement el : j.getAsJsonArray("libraries")) {
                JsonObject lib = el.getAsJsonObject();
                // rules 过滤（allow/disallow os）
                if (lib.has("rules") && !rulesAllow(lib.getAsJsonArray("rules"))) {
                    continue;
                }
                if (!lib.has("downloads")) {
                    continue;
                }
                JsonObject dl = lib.getAsJsonObject("downloads");
                if (dl == null || !dl.has("artifact")) {
                    continue;
                }
                JsonObject artifact = dl.getAsJsonObject("artifact");
                String name = lib.has("name") ? lib.get("name").getAsString() : artifact.get("path").getAsString();
                addTaskFromDownload(artifact, libsRoot.resolve(artifact.get("path").getAsString()), "lib: " + name);
                // natives classifier（如 lwjgl-platform / jinput-platform）
                if (dl.has("classifiers") && lib.has("natives")
                        && lib.getAsJsonObject("natives").has(osName)) {
                    String classifier = lib.getAsJsonObject("natives").get(osName).getAsString();
                    if (dl.getAsJsonObject("classifiers").has(classifier)) {
                        JsonObject nat = dl.getAsJsonObject("classifiers").getAsJsonObject(classifier);
                        addTaskFromDownload(nat, libsRoot.resolve(nat.get("path").getAsString()),
                                "natives: " + name);
                    }
                }
            }
        }

        // assets
        String assetIndexId = null;
        JsonObject assetIndex = null;
        for (int i = jsons.size() - 1; i >= 0; i--) {
            JsonObject j = jsons.get(i);
            if (j.has("assetIndex")) {
                assetIndex = j.getAsJsonObject("assetIndex");
                assetIndexId = j.getAsJsonObject("assetIndex").get("id").getAsString();
                break;
            }
        }
        if (assetIndex == null) {
            assetIndexId = "30";
        }
        Path assetsDir = gameDir.resolve("assets");
        Path indexPath = assetsDir.resolve("indexes").resolve(assetIndexId + ".json");
        if (assetIndex != null) {
            addTask(assetIndex.get("url").getAsString(), indexPath,
                    assetIndex.has("size") ? assetIndex.get("size").getAsLong() : -1,
                    assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null,
                    "asset index: " + assetIndexId);
        }
        if (Files.exists(indexPath)) {
            collectAssetObjects(indexPath, assetsDir);
        }

        for (Task t : tasks) {
            long s = t.size >= 0 ? t.size : 0;
            // 已完成的文件不计入总量
            if (isTaskComplete(t)) {
                doneBytes.addAndGet(s);
                filesDone.incrementAndGet();
            } else {
                long already = partialSize(t);
                doneBytes.addAndGet(Math.min(already, s));
                totalBytes.addAndGet(s);
            }
        }
    }

    private void collectAssetObjects(Path indexPath, Path assetsDir) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        if (!root.has("objects")) {
            return;
        }
        Path objectsDir = assetsDir.resolve("objects");
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("objects").entrySet()) {
            JsonObject o = entry.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            Path dest = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            addTask(url, dest, o.get("size").getAsLong(), hash, "asset: " + entry.getKey());
        }
    }

    private void addTaskFromDownload(JsonObject dl, Path dest, String label) {
        if (dl == null || !dl.has("url")) {
            return;
        }
        long size = dl.has("size") ? dl.get("size").getAsLong() : -1;
        String sha1 = dl.has("sha1") ? dl.get("sha1").getAsString() : null;
        addTask(dl.get("url").getAsString(), dest, size, sha1, label);
    }

    /** rules allow/disallow（仅看 os.name，简化实现） */
    private static boolean rulesAllow(JsonArray rules) {
        boolean allowed = false;
        String os = System.getProperty("os.name").toLowerCase().contains("win") ? "windows" : "linux";
        for (JsonElement el : rules) {
            JsonObject r = el.getAsJsonObject();
            String action = r.has("action") ? r.get("action").getAsString() : "allow";
            boolean osMatch = r.has("os") && r.getAsJsonObject("os").has("name")
                    && os.equals(r.getAsJsonObject("os").get("name").getAsString());
            if (action.equals("allow")) {
                if (!r.has("os") || osMatch) {
                    allowed = true;
                }
            } else if (action.equals("disallow") && osMatch) {
                allowed = false;
            }
        }
        return allowed;
    }

    private void addTask(String url, Path dest, long size, String sha1, String label) {
        if (isTaskComplete(new Task(url, dest, size, sha1, label))) {
            return;
        }
        tasks.add(new Task(url, dest, size, sha1, label));
    }

    private boolean isTaskComplete(Task t) {
        try {
            if (!Files.exists(t.dest)) {
                return false;
            }
            if (t.size >= 0 && Files.size(t.dest) != t.size) {
                return false;
            }
            if (t.sha1 != null) {
                return t.sha1.equalsIgnoreCase(sha1Of(t.dest));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private long partialSize(Task t) {
        try {
            Path part = t.dest.resolveSibling(t.dest.getFileName() + ".part");
            return Files.exists(part) ? Files.size(part) : 0;
        } catch (Exception e) {
            return 0;
        }
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

    // ---------- 控制 ----------

    public synchronized void start(ProgressListener listener) {
        if (state == State.RUNNING) {
            return;
        }
        if (tasks.isEmpty()) {
            state = State.DONE;
            listener.onStateChange(State.DONE, I18n.get("mc_launcher.download.all_ready"));
            return;
        }
        state = State.RUNNING;
        pool = Executors.newFixedThreadPool(maxConcurrency);
        orchestrator = new Thread(() -> runDownload(listener), "mc-launcher-mod-download");
        orchestrator.setDaemon(true);
        orchestrator.start();
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
        }
    }

    public void resume(ProgressListener listener) {
        if (state == State.PAUSED) {
            state = State.RUNNING;
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        } else if (state == State.FAILED || state == State.CANCELLED) {
            // 清掉未完成的半成品统计后重来（.part 保留以续传）
            start(listener);
        }
    }

    public void cancel() {
        State prev = state;
        state = State.CANCELLED;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        if (prev == State.CANCELLED) {
            return;
        }
    }

    private void awaitIfPaused() throws InterruptedException {
        while (state == State.PAUSED && state != State.CANCELLED) {
            synchronized (pauseLock) {
                if (state == State.PAUSED) {
                    pauseLock.wait(500);
                }
            }
        }
    }

    private void runDownload(ProgressListener listener) {
        try {
            List<Future<?>> futures = new ArrayList<>();
            // 简单分片：把任务平均分给并发 worker，每个 worker 按序下载自己那份
            int n = Math.min(maxConcurrency, tasks.size());
            for (int i = 0; i < n; i++) {
                final int worker = i;
                final int workers = n;
                futures.add(pool.submit(() -> {
                    for (int t = worker; t < tasks.size(); t += workers) {
                        if (state == State.CANCELLED) {
                            return;
                        }
                        downloadTask(tasks.get(t));
                        filesDone.incrementAndGet();
                    }
                }));
            }
            // 进度上报循环
            while (true) {
                boolean allDone = futures.stream().allMatch(Future::isDone);
                listener.onProgress(doneBytes.get(), totalBytes.get(),
                        filesDone.get(), tasks.size(), currentFile.get());
                if (allDone) {
                    break;
                }
                Thread.sleep(300);
            }
            if (state == State.CANCELLED) {
                listener.onStateChange(State.CANCELLED, I18n.get("mc_launcher.download.cancelled_part_kept"));
            } else {
                state = State.DONE;
                listener.onStateChange(State.DONE, I18n.get("mc_launcher.download.completed"));
            }
        } catch (Exception e) {
            if (state != State.CANCELLED) {
                state = State.FAILED;
                listener.onStateChange(State.FAILED, I18n.get("mc_launcher.download.failed", e.getMessage()));
            }
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
    }

    private void downloadTask(Task t) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                awaitIfPaused();
                if (state == State.CANCELLED) {
                    return;
                }
                currentFile.set(t.label);
                Files.createDirectories(t.dest.getParent());
                Path part = t.dest.resolveSibling(t.dest.getFileName() + ".part");
                long already = Files.exists(part) ? Files.size(part) : 0;
                if (t.size >= 0 && already > t.size) {
                    Files.deleteIfExists(part);
                    already = 0;
                }

                HttpURLConnection conn = open(t.url);
                if (already > 0) {
                    conn.setRequestProperty("Range", "bytes=" + already + "-");
                }
                int code = conn.getResponseCode();
                if (code == 416) { // 范围越界，说明已下完
                    break;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code + " for " + t.url);
                }

                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[65536];
                    long pos = already;
                    int n;
                    try (var out = Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND)) {
                        while ((n = in.read(buf)) > 0) {
                            if (state == State.CANCELLED) {
                                return;
                            }
                            awaitIfPaused();
                            out.write(buf, 0, n);
                            pos += n;
                            doneBytes.addAndGet(n);
                            if (t.size >= 0 && pos >= t.size) {
                                break;
                            }
                        }
                    }
                }
                // 校验
                if (t.sha1 != null) {
                    String actual = sha1Of(part);
                    if (!actual.equalsIgnoreCase(t.sha1)) {
                        Files.deleteIfExists(part);
                        throw new IOException(I18n.get("mc_launcher.download.sha1_failed", t.label));
                    }
                }
                Files.move(part, t.dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                if (state == State.CANCELLED || state == State.PAUSED) {
                    return;
                }
                if (attempt == MAX_RETRY) {
                    failMessage = t.label + ": " + e.getMessage();
                    state = State.FAILED;
                    synchronized (pauseLock) {
                        pauseLock.notifyAll();
                    }
                    return;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie2) {
                    return;
                }
            }
        }
    }

    /** 按 用户配置的代理模式 尝试连接 */
    private static HttpURLConnection open(String url) throws IOException {
        IOException last = null;
        for (Proxy p : ProxyConfig.httpProxies()) {
            try {
                URLConnection c = URI.create(url).toURL().openConnection(p);
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "mc-launcher-mod/1.0");
                return (HttpURLConnection) c;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no route");
    }

    /** 供启动前校验 */
    public static boolean isVersionComplete(InstalledVersion v) {
        try {
            Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
            VersionDownloader d = new VersionDownloader(gameDir);
            d.prepare(v);
            return d.tasks.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
