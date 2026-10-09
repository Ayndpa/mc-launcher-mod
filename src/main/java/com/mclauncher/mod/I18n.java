package com.mclauncher.mod;

/**
 * 模组 i18n 工具：统一从 assets/mc-launcher-mod/lang/<语言代码>.json 取文案，自动跟随游戏语言。
 */
public final class I18n {
    private I18n() {
    }

    public static String get(String key) {
        return net.minecraft.client.resources.language.I18n.get(key);
    }

    public static String get(String key, Object... args) {
        return net.minecraft.client.resources.language.I18n.get(key, args);
    }
}
