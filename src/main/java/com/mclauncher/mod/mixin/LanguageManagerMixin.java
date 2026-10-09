package com.mclauncher.mod.mixin;

import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 让语言列表只保留 简体中文 / English，
 * 这样游戏原版的语言选择界面（LanguageSelectScreen）就只会显示这两项。
 */
@Mixin(LanguageManager.class)
public class LanguageManagerMixin {

    @Inject(method = "getLanguages", at = @At("RETURN"), cancellable = true)
    private void limitLanguages(CallbackInfoReturnable<SortedMap<String, LanguageInfo>> cir) {
        SortedMap<String, LanguageInfo> filtered = new TreeMap<>();
        for (Map.Entry<String, LanguageInfo> e : cir.getReturnValue().entrySet()) {
            if (e.getKey().equals("zh_cn") || e.getKey().equals("en_us")) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        cir.setReturnValue(filtered);
    }
}
