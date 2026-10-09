package com.mclauncher.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

/**
 * 微软正版验证（Minecraft 账号）：
 * 设备码登录（Device Authorization Flow）→ XBL → XSTS → Minecraft Services → Profile。
 * 刷新令牌持久化到 launcher-cache/msa-account.json，启动时自动续期，无需重复登录。
 */
public final class MicrosoftAuth {
    /** 官方 Minecraft 启动器 client id，开源社区通用 */
    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final String MSA_DEVICECODE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String MSA_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    private static volatile Account account;

    private MicrosoftAuth() {
    }

    /** 已保存（或本轮登录成功）的账号 */
    public static Account getAccount() {
        return account;
    }

    /** 登录状态 */
    public enum State {
        IDLE, POLLING, SUCCESS, FAILED
    }

    public record Account(String name, String uuid, String accessToken, String refreshToken,
                          Instant accessExpiresAt, Instant refreshedAt) {
    }

    /** 一次设备码登录会话 */
    public static final class DeviceCodeLogin {
        public volatile State state = State.IDLE;
        public volatile String message = "";
        public volatile String userCode = "";
        public volatile String verificationUri = "";
    }

    // ---------- 设备码登录 ----------

    /** 后台线程启动设备码登录；userCode 显示给玩家，轮询完成 / 失败通过 state 通知 */
    public static DeviceCodeLogin startDeviceCodeLogin(Consumer<String> uiUpdater) {
        DeviceCodeLogin session = new DeviceCodeLogin();
        Thread t = new Thread(() -> runDeviceCodeLogin(session, uiUpdater), "mc-launcher-mod-msa");
        t.setDaemon(true);
        t.start();
        return session;
    }

    private static void runDeviceCodeLogin(DeviceCodeLogin s, Consumer<String> ui) {
        try {
            s.state = State.POLLING;
            s.message = "正在获取设备码…";
            ui.accept(s.message);

            Map<String, String> form = baseForm();
            form.put("scope", SCOPE);
            JsonObject dc = postForm(MSA_DEVICECODE, form);
            s.userCode = dc.get("user_code").getAsString();
            s.verificationUri = dc.has("verification_uri") ? dc.get("verification_uri").getAsString()
                    : "https://www.microsoft.com/link";
            long interval = dc.has("interval") ? dc.get("interval").getAsLong() : 5;
            String deviceCode = dc.get("device_code").getAsString();

            s.message = "打开 " + s.verificationUri + " 并输入: " + s.userCode;
            ui.accept(s.message);

            long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Map<String, String> poll = baseForm();
                poll.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                poll.put("device_code", deviceCode);
                JsonObject tok = postFormQuiet(MSA_TOKEN, poll);
                String error = tok.has("error") ? tok.get("error").getAsString() : null;
                if (error != null) {
                    switch (error) {
                        case "authorization_pending" -> {
                            Thread.sleep(interval * 1000);
                            continue;
                        }
                        case "slow_down" -> {
                            Thread.sleep(interval * 1000 + 3000);
                            continue;
                        }
                        default -> throw new IOException("登录被拒绝: " + error);
                    }
                }
                finishWithTokens(tok, s);
                return;
            }
            throw new IOException("登录超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.state = State.FAILED;
            s.message = "登录已取消";
        } catch (Exception e) {
            s.state = State.FAILED;
            s.message = "登录失败: " + e.getMessage();
        }
        ui.accept(s.message);
    }

    private static void finishWithTokens(JsonObject tok, DeviceCodeLogin s) throws Exception {
        String refreshToken = tok.get("refresh_token").getAsString();
        String accessToken = tok.get("access_token").getAsString();
        long expiresIn = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);

        s.message = "Xbox 认证中…";
        String xblToken = xblAuthenticate(accessToken);
        String[] xsts = xstsAuthorize(xblToken); // [identityToken, uhs]
        s.message = "获取 Minecraft 会话…";
        String mcToken = loginWithXbox(xsts[0], xsts[1]);
        JsonObject profile = fetchProfile(mcToken);
        String name = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString();

        account = new Account(name, uuid, mcToken, refreshToken, expiresAt, Instant.now());
        saveAccount(account);
        s.state = State.SUCCESS;
        s.message = "已登录: " + name;
    }

    // ---------- 令牌刷新与持久化 ----------

    /** 读取持久化账号；refresh_token 即将失效则不返回 */
    public static Account loadSavedAccount() {
        if (account != null) {
            return account;
        }
        try {
            Path f = accountFile();
            if (!Files.exists(f)) {
                return null;
            }
            JsonObject j = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            String rt = j.get("refresh_token").getAsString();
            Account a = new Account(j.get("name").getAsString(), j.get("uuid").getAsString(),
                    "", rt, null, Instant.ofEpochSecond(j.get("refreshed_at").getAsLong()));
            // refresh_token 本身 90 天有效，超过 80 天视为过期
            if (a.refreshedAt().isBefore(Instant.now().minusSeconds(80L * 24 * 3600))) {
                return null;
            }
            account = a;
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /** 用 refresh_token 换新的 MC 会话；成功返回 true */
    public static boolean refreshIfNeeded() {
        Account a = loadSavedAccount();
        if (a == null) {
            return false;
        }
        boolean needRefresh = account.accessToken().isEmpty()
                || account.accessExpiresAt() == null
                || Instant.now().isAfter(account.accessExpiresAt().minusSeconds(300));
        if (!needRefresh) {
            return true;
        }
        try {
            Map<String, String> form = baseForm();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", account.refreshToken());
            form.put("scope", SCOPE);
            JsonObject tok = postForm(MSA_TOKEN, form);
            String refreshToken = tok.has("refresh_token")
                    ? tok.get("refresh_token").getAsString()
                    : account.refreshToken();
            long expiresIn = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;

            String xblToken = xblAuthenticate(tok.get("access_token").getAsString());
            String[] xsts = xstsAuthorize(xblToken);
            String mcToken = loginWithXbox(xsts[0], xsts[1]);
            JsonObject profile = fetchProfile(mcToken);

            account = new Account(profile.get("name").getAsString(), profile.get("id").getAsString(),
                    mcToken, refreshToken, Instant.now().plusSeconds(expiresIn), Instant.now());
            saveAccount(account);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void logout() {
        account = null;
        try {
            Files.deleteIfExists(accountFile());
        } catch (IOException ignored) {
        }
    }

    private static Path accountFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("launcher-cache").resolve("msa-account.json");
    }

    private static void saveAccount(Account a) {
        try {
            Path f = accountFile();
            Files.createDirectories(f.getParent());
            JsonObject j = new JsonObject();
            j.addProperty("name", a.name());
            j.addProperty("uuid", a.uuid());
            j.addProperty("refresh_token", a.refreshToken());
            j.addProperty("access_expires_at", a.accessExpiresAt() == null ? 0 : a.accessExpiresAt().getEpochSecond());
            j.addProperty("refreshed_at", a.refreshedAt().getEpochSecond());
            // 先写临时文件再原子替换，避免中途退出损坏凭据
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, j.toString());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
        }
    }

    // ---------- MSA → XBL → XSTS → MC ----------

    private static Map<String, String> baseForm() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("response_mode", "json");
        return form;
    }

    private static JsonObject postForm(String url, Map<String, String> form) throws Exception {
        JsonObject j = postFormQuiet(url, form);
        if (j.has("error")) {
            String desc = j.has("error_description") ? j.get("error_description").getAsString() : "";
            throw new IOException(j.get("error").getAsString() + ": " + desc);
        }
        return j;
    }

    private static JsonObject postFormQuiet(String url, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return sendJson(req);
    }

    private static JsonObject postJson(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return sendJson(req);
    }

    private static JsonObject sendJson(HttpRequest req) throws Exception {
        Exception last = null;
        for (ProxySelector sel : ProxyConfig.httpClientSelectors()) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8));
            if (sel != null) {
                b.proxy(sel);
            }
            try {
                HttpResponse<String> resp = b.build().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
                }
                return JsonParser.parseString(resp.body()).getAsJsonObject();
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no route");
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String xblAuthenticate(String msaAccessToken) throws Exception {
        JsonObject payload = new JsonObject();
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaAccessToken);
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");
        JsonObject resp = postJson(XBL_AUTH, payload.toString());
        return resp.get("Token").getAsString();
    }

    /** 返回 [identityToken("xbl3.0 x=uhs;token"), userHash] */
    private static String[] xstsAuthorize(String xblToken) throws Exception {
        JsonObject payload = new JsonObject();
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        props.add("UserTokens", userTokens);
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");
        JsonObject resp = postJson(XSTS_AUTH, payload.toString());
        String token = resp.get("Token").getAsString();
        String uhs = resp.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0)
                .getAsJsonObject().get("uhs").getAsString();
        return new String[]{"xbl3.0 x=" + uhs + ";" + token, uhs};
    }

    private static String loginWithXbox(String identityToken, String uhs) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", identityToken);
        JsonObject resp = postJson(MC_LOGIN, payload.toString());
        if (resp.has("error")) {
            throw new IOException("Minecraft 登录失败: " + truncate(resp.toString()));
        }
        return resp.get("access_token").getAsString();
    }

    private static JsonObject fetchProfile(String mcToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_PROFILE))
                .header("Authorization", "Bearer " + mcToken)
                .GET()
                .build();
        JsonObject profile = sendJson(req);
        if (!profile.has("id") || !profile.has("name")) {
            throw new IOException("该账号没有 Minecraft 正版档案（未购买？）");
        }
        return profile;
    }

    /** UUID 无连字符形式 → 标准形式 */
    @SuppressWarnings("unused")
    private static UUID fromShortUuid(String shortUuid) {
        String s = shortUuid.replace("-", "");
        return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20, 32));
    }
}
