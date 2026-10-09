package com.mclauncher.mod.mixin;

import com.mclauncher.mod.screen.LauncherAccessibilityScreen;
import com.mclauncher.mod.screen.LauncherSoundScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommonButtons;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 首次启动的 "Welcome to Minecraft!"（AccessibilityOnboardingScreen）保留原样，
 * 只把上面的两个子页面按钮改为打开启动器精简版：
 * <ul>
 *   <li>"无障碍设置" → {@link LauncherAccessibilityScreen}
 *       （原版 AccessibilityOptionsScreen 大部分选项在主菜单场景无意义）</li>
 *   <li>"音乐和声音" → {@link LauncherSoundScreen}
 *       （原版 SoundOptionsScreen 里生物/方块/环境等音源只有进世界才会发声）</li>
 * </ul>
 * 欢迎页本身的"继续"按钮仍走原版 onboarding 流程（onboardingAccessibilityFinished），
 * 打开精简页再返回也不会破坏该流程。
 */
@Mixin(AccessibilityOnboardingScreen.class)
public class AccessibilityOnboardingScreenMixin {

    @Redirect(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/CommonButtons;accessibility(ILnet/minecraft/client/gui/components/Button$OnPress;Z)Lnet/minecraft/client/gui/components/SpriteIconButton;"
            )
    )
    private static SpriteIconButton launcherAccessibility(int width, net.minecraft.client.gui.components.Button.OnPress onPress, boolean iconOnly) {
        return CommonButtons.accessibility(width, b -> {
            Minecraft mc = Minecraft.getInstance();
            // 点击时 mc.screen 就是欢迎页本体，作为返回目标
            mc.setScreen(new LauncherAccessibilityScreen(mc.screen, mc.options));
        }, iconOnly);
    }

    @Redirect(
            // new SoundOptionsScreen 位于 "音乐和声音" 按钮的 lambda 里（lambda$init$1），
            // 不在 init 本体；用通配 lambda$init* 覆盖所有 init 派生 lambda
            method = "lambda$init$1",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Options;)Lnet/minecraft/client/gui/screens/options/SoundOptionsScreen;"
            )
    )
    private static SoundOptionsScreen launcherSound(Screen lastScreen, net.minecraft.client.Options options) {
        return new LauncherSoundScreen(lastScreen, options);
    }
}
