package com.mclauncher.mod;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;

/** 根据用户配置返回各网络请求应使用的代理 */
public final class ProxyConfig {
    private ProxyConfig() {
    }

    /**
     * java.net.http.HttpClient 用的 ProxySelector 列表（按优先级）。
     * DIRECT -> [null]；SYSTEM -> [default, null]；CUSTOM -> [custom, null]
     */
    public static ProxySelector[] httpClientSelectors() {
        return switch (LauncherState.getProxyMode()) {
            case DIRECT -> new ProxySelector[]{null};
            case SYSTEM -> new ProxySelector[]{ProxySelector.getDefault(), null};
            case CUSTOM -> new ProxySelector[]{
                    ProxySelector.of(InetSocketAddress.createUnresolved(
                            LauncherState.getProxyHost(), LauncherState.getProxyPort())),
                    null};
        };
    }

    /** HttpURLConnection 用的 Proxy 列表（按优先级） */
    public static Proxy[] httpProxies() {
        return switch (LauncherState.getProxyMode()) {
            case DIRECT -> new Proxy[]{Proxy.NO_PROXY};
            case SYSTEM -> new Proxy[]{null, Proxy.NO_PROXY};
            case CUSTOM -> new Proxy[]{
                    new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(
                            LauncherState.getProxyHost(), LauncherState.getProxyPort())),
                    Proxy.NO_PROXY};
        };
    }
}
