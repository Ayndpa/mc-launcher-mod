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

import net.minecraft.client.Minecraft;

/**
 * 微软正版验证（Minecraft 账号）：
 * Live OAuth 授权码流程（浏览器登录后把最终地址栏 URL 粘贴回来）→ XBL → XSTS → Minecraft Services → Profile。
 * 刷新令牌持久化到 launcher-cache/msa-account.json，启动时自动续期，无需重复登录。
 */
public final class MicrosoftAuth {
    /** 官方 Minecraft 启动器 client id（旧版 Live OAuth 端点通用） */
    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private static final String LIVE_AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
    private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";
    private static final String LIVE_REDIRECT = "https://login.live.com/oauth20_desktop.srf";
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
        IDLE, WAITING_CODE, EXCHANGING, SUCCESS, FAILED
    }

    public record Account(String name, String uuid, String accessToken, String refreshToken,
                          Instant accessExpiresAt, Instant refreshedAt) {
    }

    /** 一次授权码登录会话 */
    public static final class CodeLogin {
        public volatile State state = State.IDLE;
        public volatile String message = "";
    }

    // ---------- 授权码登录 ----------

    /** 浏览器打开的授权地址 */
    public static String authorizeUrl() {
        return LIVE_AUTHORIZE + "?client_id=" + CLIENT_ID
                + "&response_type=code"
                + "&scope=" + SCOPE
                + "&redirect_uri=" + LIVE_REDIRECT
                + "&cobrandid=8058f65d-ce06-4c30-9559-473c9275a396"
                + "&prompt=select_account";
    }

    /** 从登录完成后浏览器地址栏的完整 URL 中提取授权码 */
    public static String extractCode(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            if (pair.startsWith("code=")) {
                String code = pair.substring(5);
                return code.isEmpty() ? null : code;
            }
        }
        return null;
    }

    /** 后台线程用授权码换取令牌并完成整条登录链 */
    public static CodeLogin startCodeLogin(String authCode) {
        CodeLogin session = new CodeLogin();
        Thread t = new Thread(() -> runCodeLogin(session, authCode), "mc-launcher-mod-msa");
        t.setDaemon(true);
        t.start();
        return session;
    }

    private static void runCodeLogin(CodeLogin s, String authCode) {
        try {
            s.state = State.EXCHANGING;
            s.message = "正在换取令牌…";

            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("code", authCode);
            form.put("grant_type", "authorization_code");
            form.put("redirect_uri", LIVE_REDIRECT);
            form.put("scope", SCOPE);
            JsonObject tok = postForm(LIVE_TOKEN, form);
            String accessToken = tok.get("access_token").getAsString();
            String refreshToken = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : "";

            completeChain(s, accessToken, refreshToken);
        } catch (Exception e) {
            s.state = State.FAILED;
            s.message = "登录失败: " + e.getMessage();
        }
    }

    /** 用微软 access_token 走 XBL → XSTS → MC → Profile */
    private static void completeChain(CodeLogin s, String msaAccessToken, String refreshToken) throws Exception {
        s.message = "Xbox 认证中…";
        // 旧版 Live 端点发的令牌直接作 RpsTicket，不带 "d=" 前缀
        String xblToken = xblAuthenticate(msaAccessToken);
        String[] xsts = xstsAuthorize(xblToken); // [identityToken, uhs]
        s.message = "获取 Minecraft 会话…";
        String mcToken = loginWithXbox(xsts[0], xsts[1]);
        JsonObject profile = fetchProfile(mcToken);
        String name = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString();

        account = new Account(name, uuid, mcToken, refreshToken,
                Instant.now().plusSeconds(3600), Instant.now());
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
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("refresh_token", account.refreshToken());
            form.put("grant_type", "refresh_token");
            form.put("redirect_uri", LIVE_REDIRECT);
            form.put("scope", SCOPE);
            JsonObject tok = postForm(LIVE_TOKEN, form);
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
        props.addProperty("RpsTicket", msaAccessToken);
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
