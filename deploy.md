# mc-launcher-mod 编译与部署方案

## 环境要求

| 组件 | 版本 | 路径 |
|------|------|------|
| JDK | Temurin 25（vfox 或实例自带） | 见下方"JDK 与 Gradle" |
| Gradle | 9.7.0（本机无 wrapper） | `D:\Dev\Codes\Test\gradle-9.7.0\bin\gradle.bat` |
| Minecraft / Loader | 26.1.2 / Fabric Loader 0.19.5 | 见 `gradle.properties` |
| Loom 插件 | net.fabricmc.fabric-loom 1.18-SNAPSHOT | |
| 代理 | `127.0.0.1:7897`（gradle.properties 已配置，下载依赖时需要） | |

## 编译

在项目根目录（本目录）执行：

```powershell
cd D:\Dev\Codes\Test\mc-launcher-mod
$env:JAVA_HOME = "C:\Users\Admin\.vfox\cache\java\v-25.0.2+10\java-25.0.2+10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "D:\Dev\Codes\Test\gradle-9.7.0\bin\gradle.bat" --offline "-Dorg.gradle.java.home=$env:JAVA_HOME" build
```

- 产物：`build\libs\mc-launcher-mod-1.0.0.jar`
- 只检查编译错误：把 `build` 换成 `compileJava`
- 增量编译通常 1~10 秒；依赖已缓存，`--offline` 即可，首次构建才需要联网（走 7897 代理）

## 部署

把构建产物覆盖到游戏实例的 mods 目录：

```powershell
Copy-Item "build\libs\mc-launcher-mod-1.0.0.jar" `
    "D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\mods\mc-launcher-mod-1.0.0.jar" -Force
```

部署后重启游戏生效。

### 实际路径（重要）

文档早期版本写的 `D:\Dev\Codes\Test\mc-launcher\mc\...` 已不存在。Rust 启动器
（`mc-launcher`）运行时的真实实例目录是：

| 用途 | 路径 |
|------|------|
| 游戏实例根目录 | `D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\` |
| mods 目录 | `...\instances\default\mods\` |
| Java 运行时 | `...\instances\default\java\jdk-25.0.4.1+1` |

仓库里的 `D:\Dev\Codes\Test\mc-launcher\src\embedded\` 只是打包进启动器的**资源模板**，
把 jar 复制到那里不会生效。

### JDK 与 Gradle

本机 PATH 上的 `java` 是 Java 8，Gradle 9.x 会因 "requires JVM 17 or later" 直接拒绝启动
（`-Dorg.gradle.java.home` 也无法绕过该前置检查）。可用的 JDK 25 有两个：

- `C:\Users\Admin\.vfox\cache\java\v-25.0.2+10\java-25.0.2+10`
- `D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\java\jdk-25.0.4.1+1`

Gradle 9.7.0 已固定在 `D:\Dev\Codes\Test\gradle-9.7.0\bin\gradle.bat`，无需重新下载。
依赖已全部进缓存，加 `--offline` 即可，不必开代理。

## 一键脚本（编译 + 部署）

```powershell
cd D:\Dev\Codes\Test\mc-launcher-mod
$env:JAVA_HOME = "C:\Users\Admin\.vfox\cache\java\v-25.0.2+10\java-25.0.2+10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "D:\Dev\Codes\Test\gradle-9.7.0\bin\gradle.bat" --offline "-Dorg.gradle.java.home=$env:JAVA_HOME" build -q
if ($?) {
    Copy-Item "build\libs\mc-launcher-mod-1.0.0.jar" `
        "D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\mods\mc-launcher-mod-1.0.0.jar" -Force
    Get-Item "D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\mods\mc-launcher-mod-1.0.0.jar" | Select-Object LastWriteTime
}
```

> PowerShell 5.1 下 `-Dorg.gradle.java.home=...` 必须整体加引号，否则会被拆成 task 名。

## 常见问题

### 中文源码乱码
源文件均为 UTF-8（无 BOM）。不要用 `Set-Content` / `Out-File` 直接改写 `.java` 文件——
Windows PowerShell 5.1 默认按 GBK 读写，会把中文字符串永久损坏。必须用
`[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`
或编辑器工具。

### BMCLAPI 返回 302 / "Not a JSON: null"
BMCLAPI 清单接口会 302 重定向，`HttpClient` 已配置 `Redirect.ALWAYS`（见 `LauncherCore.httpGet`）。
下载器（`VersionDownloader`）使用 `HttpURLConnection`，其默认跟随同协议重定向，无需额外配置。

### 文本渲染不可见
26.x 的 `GuiGraphicsExtractor.text` / `fill` 颜色参数是 **ARGB**（8 位十六进制），
写 `0xFFFFFF` 会被当成 alpha=0 全透明。所有颜色必须写 `0xFFFFFFFF` 这种 4 字节形式。

### 列表项不显示 / 画在鼠标位置
26.x 的 `AbstractSelectionList.Entry.extractContent(g, mouseX, mouseY, hovered, delta)`
第二个 int 是鼠标 Y 而非行顶坐标。行内坐标要用 `getContentX()` / `getContentY()`。
