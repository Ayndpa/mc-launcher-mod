package com.mclauncher.mod;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McLauncherMod implements ClientModInitializer {
    public static final String MOD_ID = "mc-launcher-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LauncherState.load();
        LOGGER.info("[MC启动器] 恶搞模式加载完毕——主菜单已被劫持为启动器界面");
    }
}
