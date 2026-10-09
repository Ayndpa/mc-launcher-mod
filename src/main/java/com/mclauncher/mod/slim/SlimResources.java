package com.mclauncher.mod.slim;

import com.mclauncher.mod.LauncherState;

import net.minecraft.resources.Identifier;

/**
 * 资源精简模式：启动器实例永远不进入真实世界（“开始游戏”是拉起独立 Java 进程），
 * 本实例唯一需要的资源就是主菜单 UI——GUI 图集、字体、着色器、全景图、玩家皮肤。
 *
 * 通过 {@link SlimFilterPack} 包裹资源包，把方块/物品纹理与模型、实体纹理、
 * 画、粒子、状态效果、非 UI 音效、多余语言文件全部从资源视图中隐藏。
 * 这样初始资源重载时：
 *  - AtlasManager 的 blocks / items / particle 等图集几乎拼不到任何精灵；
 *  - ModelManager 没有模型可烘焙；
 *  - SoundManager 的 soundCache 枚举不到大体积 .ogg；
 *  - 语言只加载 en_us / zh_cn。
 * 原版在这些位置本来就有“缺失资源”兜底（缺纹理→紫黑格，缺模型→missing model），
 * 因此过滤是安全的，不会导致崩溃。
 */
public final class SlimResources {
    private SlimResources() {
    }

    public static boolean isEnabled() {
        return LauncherState.isSlimResources();
    }

    /** 判断一个资源路径（不带 namespace，如 textures/block/stone.png）是否应被隐藏 */
    public static boolean isBlocked(Identifier id) {
        String p = id.getPath();

        // 模型/方块状态的 JSON 不能清空：ModelBakery/ModelManager 假定
        // blockStateModels 是可变 Map，空枚举会返回不可变 Map 导致重载崩溃
        // （java.lang.UnsupportedOperationException）。JSON 解析本身开销很小，
        // 真正吃内存的是纹理（PNG→NativeImage→GPU），所以只过滤重纹理目录。

        // 重型纹理目录；实体纹理放行玩家皮肤（PlayerSkinWidget 要用）。
        // 注意不要过滤 textures/environment/：云层纹理缺失会让 CloudRenderer 的重载
        // 监听器抛 FileNotFoundException，导致整个资源重载失败。
        if (p.startsWith("textures/block/")
                || p.startsWith("textures/item/")
                || p.startsWith("textures/painting/")
                || p.startsWith("textures/particle/")
                || p.startsWith("textures/mob_effect/")) {
            return true;
        }
        if (p.startsWith("textures/entity/")) {
            return !p.startsWith("textures/entity/player/");
        }

        // 音效与音乐全部保留（含 music/、records/），不精简

        // 语言：只留 en_us / zh_cn（LanguageManager 本来也只加载选中的语言）
        if (p.startsWith("lang/")) {
            return !p.equals("lang/en_us.json") && !p.equals("lang/zh_cn.json");
        }

        return false;
    }
}
