package com.mclauncher.mod.mixin;

import com.mclauncher.mod.slim.SlimFilterPack;
import com.mclauncher.mod.slim.SlimResources;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadableResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * 资源精简：拦截 {@link ReloadableResourceManager#createReload}，
 * 把传入的全部资源包（vanilla / fabric-api / 用户资源包）用 {@link SlimFilterPack} 包一层，
 * 隐藏主菜单用不到的方块/物品纹理、模型、音效等，让初始资源重载只加载主菜单所需素材。
 *
 * 由于所有重载监听器（AtlasManager / ModelManager / SoundManager / FontManager / …）
 * 都通过这个 MultiPackResourceManager 读取和枚举资源，此处一处拦截即可全局生效；
 * 原版对缺失资源有兜底（紫黑格纹理 / missing model），过滤不会导致崩溃。
 */
@Mixin(ReloadableResourceManager.class)
public class ReloadableResourceManagerMixin {

    @ModifyVariable(
            method = "createReload",
            at = @At("HEAD"),
            argsOnly = true
    )
    private List<PackResources> slimResourcePacks(List<PackResources> packs) {
        if (!SlimResources.isEnabled()) {
            return packs;
        }
        // 用上游提供的 filter 机制包一层；mapping 名可能因版本而异，这里直接按接口包装
        return packs.stream()
                .map(p -> (PackResources) new SlimFilterPack(p, SlimResources::isBlocked))
                .toList();
    }
}
