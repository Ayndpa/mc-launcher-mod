package com.mclauncher.mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;

/**
 * 拉取带 textures 属性的正版档案。SkinManager 只从 GameProfile 的 textures
 * 属性里读皮肤/披风 URL，裸 GameProfile 永远拿不到皮肤，必须先经过
 * sessionService.fetchProfile 补全属性。带缓存与失败退避（供应器每帧被调用）。 */
public final class SkinProfiles {
    private record Entry(com.mojang.authlib.GameProfile profile, long fetchedAt, boolean ok) {
    }

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 5 * 60 * 1000L;

    private SkinProfiles() {
    }

    /** 返回带 textures 属性的档案；尚未拉到时返回裸档案并后台补拉，下次渲染帧自动生效 */
    public static com.mojang.authlib.GameProfile withTextures(UUID uuid, String name) {
        Entry e = CACHE.get(uuid);
        long now = System.currentTimeMillis();
        if (e == null || (!e.ok() && now - e.fetchedAt() > RETRY_MS)) {
            CACHE.put(uuid, new Entry(new com.mojang.authlib.GameProfile(uuid, name), now, false));
            fetchAsync(uuid, name);
            e = CACHE.get(uuid);
        }
        return e.profile();
    }

    private static void fetchAsync(UUID uuid, String name) {
        Thread t = new Thread(() -> {
            com.mojang.authlib.GameProfile fallback =
                    new com.mojang.authlib.GameProfile(uuid, name);
            try {
                var services = Minecraft.getInstance().services();
                var result = services.sessionService().fetchProfile(uuid, false);
                com.mojang.authlib.GameProfile p = result != null ? result.profile() : null;
                if (p != null && p.properties().containsKey("textures")) {
                    CACHE.put(uuid, new Entry(p, System.currentTimeMillis(), true));
                } else {
                    CACHE.put(uuid, new Entry(fallback, System.currentTimeMillis(), false));
                }
            } catch (Exception ex) {
                CACHE.put(uuid, new Entry(fallback, System.currentTimeMillis(), false));
            }
        }, "mc-launcher-mod-skin-profile");
        t.setDaemon(true);
        t.start();
    }
}
