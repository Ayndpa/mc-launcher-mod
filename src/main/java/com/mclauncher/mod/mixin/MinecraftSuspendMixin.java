package com.mclauncher.mod.mixin;

import com.mclauncher.mod.LauncherSuspend;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 启动器挂起开关：真实游戏启动期间把整个启动器（宿主 MC）彻底冻结——
 * runTick 直接取消（不渲染、不 tick 音乐/音效/任何游戏逻辑），
 * 只保留 GLFW 事件泵让窗口消息循环不假死。
 * 标志由 LauncherSuspend 持有，VersionLaunchScreen 在隐藏窗口时置位、恢复时清除。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSuspendMixin {

    @Inject(method = "runTick(Z)V", at = @At("HEAD"), cancellable = true)
    private void mcLauncherMod$skipTickWhenSuspended(boolean renderLevel, CallbackInfo ci) {
        if (LauncherSuspend.suspended) {
            // 泵事件保持窗口响应（例如用户关窗），其余全部冻结
            GLFW.glfwPollEvents();
            ci.cancel();
        }
    }
}
