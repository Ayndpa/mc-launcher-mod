package com.mclauncher.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;

/**
 * 实例管理：与 Rust 启动器 paths.rs 的实例隔离设计对应。
 * <p>
 * 目录结构：launcher_root/instances/<实例名>/，每个实例有独立的
 * versions / libraries / assets / saves 等（launcher_root = 当前 gameDir 的上层，
 * 即 bootstrap 的 mc/ 目录；当前实例自己的 gameDir 就是 instances/<当前名>/）。
 * <p>
 * 当前实例 = 游戏进程的 --gameDir。其它实例 = gameDir 的兄弟目录。
 * 下载新版本时可选择放入任一实例；同名冲突自动追加 (1) (2) …
 */
public final class Instances {
    /** 一个可选的下载目标实例 */
    public record Instance(String name, Path dir) {
    }

    private Instances() {
    }

    /** 当前游戏进程所在实例目录（= --gameDir） */
    public static Path currentDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    /** 实例集合根目录：名为 instances 的目录本身；非该布局（如裸 .minecraft）则用 gameDir 本身 */
    public static Path instancesRoot() {
        Path gameDir = currentDir();
        if ("instances".equals(gameDir.getFileName().toString())) {
            return gameDir;
        }
        Path parent = gameDir.getParent();
        if (parent != null && "instances".equals(parent.getFileName().toString())) {
            // bootstrap 布局：<root>/instances/<实例名>，实例集合根就是 <root>/instances
            return parent;
        }
        return gameDir;
    }

    /** 扫描全部实例目录：instancesRoot() 下所有包含 versions 子目录的文件夹 + 当前实例 */
    public static List<Instance> scan() {
        List<Instance> out = new ArrayList<>();
        Path cur = currentDir();
        out.add(new Instance(cur.getFileName().toString(), cur));
        Path root = instancesRoot();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> Files.isDirectory(d.resolve("versions")))
                    .filter(d -> !d.equals(cur))
                    .sorted(Comparator.comparing(d -> d.getFileName().toString()))
                    .forEach(d -> out.add(new Instance(d.getFileName().toString(), d)));
        } catch (IOException ignored) {
        }
        return out;
    }

    /** 在指定实例里为版本 id 找一个不冲突的目录名：26.3 → 26.3(1) → 26.3(2) … */
    public static Path freeVersionDir(Instance inst, String versionId) {
        Path base = inst.dir().resolve("versions").resolve(versionId);
        if (!Files.exists(base)) {
            return base;
        }
        for (int i = 1; i < 1000; i++) {
            Path candidate = inst.dir().resolve("versions").resolve(versionId + "(" + i + ")");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return base; // 极端情况退回
    }
}
