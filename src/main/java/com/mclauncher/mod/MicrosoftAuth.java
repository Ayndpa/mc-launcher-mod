package com.mclauncher.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Base64;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

/**
 * 微软正版验证（Minecraft 账号）—— 对齐 prismarine-auth 的 live 流程：
 * Device Code 流（login.live.com/oauth20_connect.srf 申请设备码 → 用户在
 * microsoft.com/link 输入代码 → 轮询 oauth20_token.srf 拿令牌）
 * → XBL → XSTS → Minecraft Services → Profile。
 * 无需浏览器回调/粘贴 URL。刷新令牌持久化，启动时自动续期。 */
public final class MicrosoftAuth {
    /** Minecraft for Nintendo Switch 的 client id（同 prismarine-auth Titles.MinecraftNintendoSwitch），
     *  支持设备码流；旧版 Java 版 client id (402b5328) 不支持 device_code */
    private static final String CLIENT_ID = "00000000441cc96b";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private static final String LIVE_DEVICE_CODE = "https://login.live.com/oauth20_connect.srf";
    private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    private static final String LINK_PAGE = "https://www.microsoft.com/link?otc=";

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

    /** 一次设备码登录会话 */
    public static final class CodeLogin {
        public volatile State state = State.IDLE;
        public volatile String message = "";
        /** 提示用户打开的链接（含 otc 参数） */
        public volatile String linkUrl = "";
        /** 提示用户在页面输入的设备码 */
        public volatile String userCode = "";
        /** 界面侧标记：是否已打开验证页（避免重复打开） */
        public volatile boolean deviceCodePageOpened = false;
    }

    // ---------- 设备码登录 ----------

    /**
     * 发起设备码登录：申请设备码后启动后台轮询线程。
     * 调用方从 session 里读 linkUrl / userCode 展示给用户；轮询拿到令牌后自动完成整条链。 */
    public static CodeLogin startDeviceCodeLogin() {
        CodeLogin session = new CodeLogin();
        Thread t = new Thread(() -> runDeviceCodeLogin(session), "mc-launcher-mod-msa");
        t.setDaemon(true);
        t.start();
        return session;
    }

    private static void runDeviceCodeLogin(CodeLogin s) {
        try {
            // 1. 申请设备码
            s.state = State.WAITING_CODE;
            s.message = "正在申请设备码…";
            Map<String, String> form = new LinkedHashMap<>();
            form.put("scope", SCOPE);
            form.put("client_id", CLIENT_ID);
            form.put("response_type", "device_code");
            JsonObject dc = postForm(LIVE_DEVICE_CODE, form);
            String deviceCode = dc.get("device_code").getAsString();
            String userCode = dc.get("user_code").getAsString();
            long intervalMs = (dc.has("interval") ? dc.get("interval").getAsInt() : 5) * 1000L;
            long deadline = System.currentTimeMillis()
                    + (dc.has("expires_in") ? dc.get("expires_in").getAsInt() : 900) * 1000L
                    - 1000;

            s.userCode = userCode;
            s.linkUrl = LINK_PAGE + URLEncoder.encode(userCode, StandardCharsets.UTF_8);
            s.message = "请在打开的页面输入代码完成登录";

            // 2. 轮询换令牌
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(intervalMs);
                if (s.state == State.FAILED) {
                    return; // 用户取消
                }
                Map<String, String> poll = new LinkedHashMap<>();
                poll.put("client_id", CLIENT_ID);
                poll.put("device_code", deviceCode);
                poll.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                JsonObject tok = postFormAllow400(LIVE_TOKEN, poll);
                if (tok.has("error")) {
                    String err = tok.get("error").getAsString();
                    if (err.equals("authorization_pending")) {
                        continue; // 用户还没确认
                    }
                    if (err.equals("slow_down")) {
                        intervalMs += 2000;
                        continue;
                    }
                    throw new IOException(err + ": "
                            + (tok.has("error_description") ? tok.get("error_description").getAsString() : ""));
                }
                // 3. 拿到令牌，走完登录链
                String accessToken = tok.get("access_token").getAsString();
                String refreshToken = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : "";
                completeChain(s, accessToken, refreshToken);
                return;
            }
            throw new IOException("设备码已过期，请重新登录");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.state = State.FAILED;
            s.message = "登录已取消";
        } catch (Exception e) {
            s.state = State.FAILED;
            s.message = "登录失败: " + e.getMessage();
        }
    }

    /** 用微软 access_token 走 XBL → XSTS → MC → Profile（对齐 prismarine-auth 的 RpsTicket "d=" 前缀） */
    private static void completeChain(CodeLogin s, String msaAccessToken, String refreshToken) throws Exception {
        s.state = State.EXCHANGING;
        s.message = "Xbox 认证中…";
        String[] xsts = xblChain(msaAccessToken); // [identityToken, uhs]
        s.message = "获取 Minecraft 会话…";
        String mcToken = loginWithXbox(xsts[0], xsts[1]);
        JsonObject profile = fetchProfile(mcToken);
        String name = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString();

        account = new Account(name, uuid, mcToken, refreshToken,
                Instant.now().plusSeconds(3600), Instant.now());
        saveAccount(account);
        LauncherState.setActiveMsaUuid(uuid);
        s.state = State.SUCCESS;
        s.message = "已登录: " + name;
    }

    // ---------- 令牌刷新与持久化（多账号） ----------

    /** 读取持久化账号；refresh_token 即将失效则不返回 */
    public static Account loadSavedAccount() {
        if (account != null) {
            return account;
        }
        // 有激活档案时优先加载该账号，否则加载列表第一个
        Account a = loadByUuid(LauncherState.getActiveMsaUuid());
        if (a == null) {
            List<Account> all = loadSavedAccounts();
            if (!all.isEmpty()) {
                a = all.get(0);
            }
        }
        if (a == null) {
            return null;
        }
        account = a;
        return a;
    }

    /** 按 UUID 从磁盘加载指定账号（refresh_token 过期则返回 null） */
    public static Account loadByUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }
        for (Account a : loadSavedAccounts()) {
            if (uuid.equals(a.uuid())) {
                return a;
            }
        }
        return null;
    }

    /** 读取磁盘上全部已保存账号（refresh_token 超过 80 天的剔除） */
    public static List<Account> loadSavedAccounts() {
        try {
            Path f = accountFile();
            if (!Files.exists(f)) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            List<Account> result = new ArrayList<>();
            if (root.has("accounts")) {
                for (JsonElement el : root.getAsJsonArray("accounts")) {
                    Account a = fromJson(el.getAsJsonObject());
                    if (a != null) {
                        result.add(a);
                    }
                }
            } else if (root.has("refresh_token")) {
                // 旧版单账号格式
                Account a = fromJson(root);
                if (a != null) {
                    result.add(a);
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Account fromJson(JsonObject j) {
        try {
            Account a = new Account(j.get("name").getAsString(), j.get("uuid").getAsString(),
                    "", j.get("refresh_token").getAsString(), null,
                    Instant.ofEpochSecond(j.get("refreshed_at").getAsLong()));
            // refresh_token 本身 90 天有效，超过 80 天视为过期
            if (a.refreshedAt().isBefore(Instant.now().minusSeconds(80L * 24 * 3600))) {
                return null;
            }
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 account 写入多账号列表（按 uuid 去重更新） */
    private static void upsertAccount(Account a) {
        List<Account> all = new ArrayList<>(loadSavedAccounts());
        all.removeIf(x -> x.uuid().equals(a.uuid()));
        all.add(0, a); // 最近登录的排最前
        saveAccounts(all);
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
            form.put("scope", SCOPE);
            form.put("client_id", CLIENT_ID);
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", account.refreshToken());
            JsonObject tok = postForm(LIVE_TOKEN, form);
            String refreshToken = tok.has("refresh_token")
                    ? tok.get("refresh_token").getAsString()
                    : account.refreshToken();
            long expiresIn = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;

            String[] xsts = xblChain(tok.get("access_token").getAsString());
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

    /** 切换激活的正版账号（按 uuid）；返回切换后的账号 */
    public static Account switchAccount(String uuid) {
        Account a = loadByUuid(uuid);
        if (a != null) {
            account = a;
            LauncherState.setActiveMsaUuid(uuid);
        }
        return account;
    }

    /** 删除指定账号（登出单个档案）；若删的是激活账号则清除激活状态 */
    public static void removeAccount(String uuid) {
        List<Account> all = new ArrayList<>(loadSavedAccounts());
        all.removeIf(x -> x.uuid().equals(uuid));
        saveAccounts(all);
        if (uuid.equals(LauncherState.getActiveMsaUuid())) {
            LauncherState.setActiveMsaUuid(null);
        }
        if (account != null && uuid.equals(account.uuid())) {
            account = null;
        }
    }

    public static void logout() {
        if (account != null) {
            removeAccount(account.uuid());
        } else {
            try {
                Files.deleteIfExists(accountFile());
            } catch (IOException ignored) {
            }
        }
    }

    private static Path accountFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("launcher-cache").resolve("msa-account.json");
    }

    private static void saveAccount(Account a) {
        upsertAccount(a);
    }

    private static void saveAccounts(List<Account> accounts) {
        try {
            Path f = accountFile();
            Files.createDirectories(f.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Account a : accounts) {
                JsonObject j = new JsonObject();
                j.addProperty("name", a.name());
                j.addProperty("uuid", a.uuid());
                j.addProperty("refresh_token", a.refreshToken());
                j.addProperty("access_expires_at", a.accessExpiresAt() == null ? 0 : a.accessExpiresAt().getEpochSecond());
                j.addProperty("refreshed_at", a.refreshedAt().getEpochSecond());
                arr.add(j);
            }
            root.add("accounts", arr);
            // 先写临时文件再原子替换，避免中途退出损坏凭据
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
        }
    }


    // ---------- HTTP helpers ----------

    private static JsonObject postForm(String url, Map<String, String> form) throws Exception {
        JsonObject j = postFormQuiet(url, form);
        if (j.has("error")) {
            String desc = j.has("error_description") ? j.get("error_description").getAsString() : "";
            throw new IOException(j.get("error").getAsString() + ": " + desc);
        }
        return j;
    }

    /** 同 postForm，但容忍 HTTP 400（Live 端点轮询时用 400 状态码返回 authorization_pending 等 JSON 错误） */
    private static JsonObject postFormAllow400(String url, Map<String, String> form) throws Exception {
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
        Exception last = null;
        for (ProxySelector sel : ProxyConfig.httpClientSelectors()) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8));
            if (sel != null) {
                b.proxy(sel);
            }
            try {
                HttpResponse<String> resp = b.build().send(req, HttpResponse.BodyHandlers.ofString());
                // 400 也带 JSON 错误体（authorization_pending 等），交给调用方判断
                if (resp.statusCode() / 100 != 2 && resp.statusCode() != 400) {
                    throw new IOException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
                }
                return JsonParser.parseString(resp.body()).getAsJsonObject();
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no route");
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
    // ---------- MSA → XBL → XSTS → MC（对齐 prismarine-auth live 流程：proof key + 签名头 + device/title token） ----------

    private static final String XBL_DEVICE_AUTH = "https://device.auth.xboxlive.com/device/authenticate";
    private static final String XBL_TITLE_AUTH = "https://title.auth.xboxlive.com/title/authenticate";

    /** 本次运行的 ES256 proof key（prismarine-auth 每个会话生成一次） */
    private static KeyPair proofKeyPair;
    private static JsonObject proofKeyJwk;

    private static synchronized void ensureProofKey() throws Exception {
        if (proofKeyPair != null) {
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        proofKeyPair = g.generateKeyPair();
        proofKeyJwk = jwkFromPublic((ECPublicKey) proofKeyPair.getPublic());
    }

    /** P-256 公钥 → JWK（同 prismarine-auth：{kty,crv,x,y,alg:ES256,use:sig}，base64url 无填充） */
    private static JsonObject jwkFromPublic(ECPublicKey pub) {
        ECPoint w = pub.getW();
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "EC");
        jwk.addProperty("crv", "P-256");
        jwk.addProperty("x", b64u(left32(w.getAffineX())));
        jwk.addProperty("y", b64u(left32(w.getAffineY())));
        jwk.addProperty("alg", "ES256");
        jwk.addProperty("use", "sig");
        return jwk;
    }

    private static byte[] left32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        // 取低 32 字节（大端）
        for (int i = 0; i < 32; i++) {
            out[i] = b[b.length - 32 + i];
        }
        return out;
    }

    private static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 对 XBL 请求做 ProofKey 签名，返回 base64 的 Signature 头（对齐 XboxTokenManager.sign） */
    private static String signXbl(String url, String authorizationToken, String payload) throws Exception {
        // Windows epoch：100ns 单位，自 1601-01-01
        long unix = System.currentTimeMillis() / 1000;
        BigInteger winTs = BigInteger.valueOf(unix).add(BigInteger.valueOf(11644473600L))
                .multiply(BigInteger.valueOf(10_000_000L));
        byte[] ts = winTs.toByteArray();
        byte[] ts8 = new byte[8];
        for (int i = 0; i < 8; i++) {
            ts8[i] = ts[ts.length - 8 + i];
        }
        String pathAndQuery = URI.create(url).getPath();

        // 签名缓冲：PolicyVersion(4) + 0 + TS(8) + 0 + POST + path + auth + payload（NUL 结尾字符串）
        byte[] post = new byte[]{'P', 'O', 'S', 'T', 0};
        byte[] path = (pathAndQuery + '\0').getBytes(StandardCharsets.UTF_8);
        byte[] auth = (authorizationToken + '\0').getBytes(StandardCharsets.UTF_8);
        byte[] body = (payload + '\0').getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.write(new byte[]{0, 0, 0, 1}); // Policy Version 1
        buf.write(0);
        buf.write(ts8);
        buf.write(0);
        buf.write(post);
        buf.write(path);
        buf.write(auth);
        buf.write(body);
        byte[] data = buf.toByteArray();

        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initSign(proofKeyPair.getPrivate());
        sig.update(data);
        byte[] r = sig.sign(); // P1363: r||s，各 32 字节

        // 头：PolicyVersion(4) + TS(8) + signature
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        head.write(new byte[]{0, 0, 0, 1});
        head.write(ts8);
        head.write(r);
        return Base64.getEncoder().encodeToString(head.toByteArray());
    }

    /** 带 Signature 头的 XBL JSON POST（返回错误时带 Xbox 错误码解释） */
    private static JsonObject postXbl(String url, JsonObject payload) throws Exception {
        String body = payload.toString();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store, must-revalidate, no-cache")
                .header("x-xbl-contract-version", "2")
                .header("Signature", signXbl(url, "", body))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        Exception last = null;
        for (ProxySelector sel : ProxyConfig.httpClientSelectors()) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8));
            if (sel != null) {
                b.proxy(sel);
            }
            try {
                HttpResponse<String> resp = b.build().send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw newXblError(resp);
                }
                return JsonParser.parseString(resp.body()).getAsJsonObject();
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no route");
    }

    /** Xbox 错误响应 → 带可读信息的 IOException */
    private static IOException newXblError(HttpResponse<String> resp) {
        String errHeader = resp.headers().firstValue("x-err").orElse("");
        String codeStr = errHeader;
        try {
            JsonObject j = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (j.has("XErr")) {
                codeStr = j.get("XErr").getAsString();
            }
        } catch (Exception ignored) {
        }
        String msg = switch (codeStr) {
            case "2148916233" -> "此微软账号没有 Xbox 档案，请先到 xbox.com 创建";
            case "2148916234" -> "此账号未接受 Xbox 服务条款";
            case "2148916238" -> "此账号为未成年账号，需由成年人添加到家庭组";
            case "2148916229" -> "此账号受家长限制，暂无法登录";
            default -> "Xbox 认证失败 (HTTP " + resp.statusCode() + ", XErr=" + codeStr + ")";
        };
        return new IOException(msg);
    }

    /** Xbox 认证：user token (t=) → device token → title token → XSTS（全部带 ProofKey + Signature） */
    private static String[] xblChain(String msaAccessToken) throws Exception {
        ensureProofKey();

        // 1. user token
        JsonObject userPayload = new JsonObject();
        JsonObject up = new JsonObject();
        up.addProperty("AuthMethod", "RPS");
        up.addProperty("SiteName", "user.auth.xboxlive.com");
        up.addProperty("RpsTicket", "t=" + msaAccessToken);
        userPayload.add("Properties", up);
        userPayload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        userPayload.addProperty("TokenType", "JWT");
        userPayload.add("ProofKey", proofKeyJwk);
        JsonObject userResp = postXbl(XBL_AUTH, userPayload);
        String userToken = userResp.get("Token").getAsString();

        // 2. device token
        JsonObject devPayload = new JsonObject();
        JsonObject dp = new JsonObject();
        dp.addProperty("AuthMethod", "ProofOfPossession");
        dp.addProperty("Id", "{" + java.util.UUID.randomUUID() + "}");
        dp.addProperty("DeviceType", "Nintendo");
        dp.addProperty("SerialNumber", "{" + java.util.UUID.randomUUID() + "}");
        dp.addProperty("Version", "0.0.0");
        dp.add("ProofKey", proofKeyJwk);
        devPayload.add("Properties", dp);
        devPayload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        devPayload.addProperty("TokenType", "JWT");
        JsonObject devResp = postXbl(XBL_DEVICE_AUTH, devPayload);
        String deviceToken = devResp.get("Token").getAsString();

        // 3. title token
        JsonObject titlePayload = new JsonObject();
        JsonObject tp = new JsonObject();
        tp.addProperty("AuthMethod", "RPS");
        tp.addProperty("DeviceToken", deviceToken);
        tp.addProperty("RpsTicket", "t=" + msaAccessToken);
        tp.addProperty("SiteName", "user.auth.xboxlive.com");
        tp.add("ProofKey", proofKeyJwk);
        titlePayload.add("Properties", tp);
        titlePayload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        titlePayload.addProperty("TokenType", "JWT");
        JsonObject titleResp = postXbl(XBL_TITLE_AUTH, titlePayload);
        String titleToken = titleResp.get("Token").getAsString();

        // 4. XSTS（refresh 流程里同样的请求结构）
        String[] xsts = xstsAuthorize(userToken, deviceToken, titleToken);
        return xsts;
    }

    /** 返回 [identityToken("xbl3.0 x=uhs;token"), userHash] */
    private static String[] xstsAuthorize(String userToken, String deviceToken, String titleToken) throws Exception {
        JsonObject payload = new JsonObject();
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(userToken);
        props.add("UserTokens", userTokens);
        props.addProperty("DeviceToken", deviceToken);
        props.addProperty("TitleToken", titleToken);
        props.add("ProofKey", proofKeyJwk);
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");
        JsonObject resp = postXbl(XSTS_AUTH, payload);
        if (resp.has("XErr")) {
            throw new IOException("XSTS 错误 " + resp.get("XErr").getAsLong()
                    + (resp.has("Message") ? ": " + resp.get("Message").getAsString() : ""));
        }
        String token = resp.get("Token").getAsString();
        String uhs = resp.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0)
                .getAsJsonObject().get("uhs").getAsString();
        return new String[]{"xbl3.0 x=" + uhs + ";" + token, uhs};
    }

    private static String loginWithXbox(String identityToken, String uhs) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_LOGIN))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "MinecraftLauncher/2.2.10675")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"identityToken\":\"" + identityToken + "\"}", StandardCharsets.UTF_8))
                .build();
        JsonObject resp = sendJson(req);
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
}
