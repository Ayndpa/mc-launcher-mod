# mc-launcher-mod 编译与部署方案

## 环境要求

| 组件 | 版本 | 路径 |
|------|------|------|
| JDK | Temurin 25.0.4.1+1 | `D:\Dev\Codes\Test\mc-launcher\mc\java\jdk-25.0.4.1+1` |
| Gradle | 9.7.0（本机无 wrapper，需手动下载） | 见下方"获取 Gradle" |
| Minecraft / Loader | 26.1.2 / Fabric Loader 0.19.5 | 见 `gradle.properties` |
| Loom 插件 | net.fabricmc.fabric-loom 1.18-SNAPSHOT | |
| 代理 | `127.0.0.1:7897`（gradle.properties 已配置，下载依赖时需要） | |

## 获取 Gradle 9.7.0

项目没有 `gradlew` wrapper，本机只装了 Gradle 7.4 / 8.x，而 Loom 1.18 需要 Gradle 9.x。
首次编译前下载并解压一次（约 150MB）：

```powershell
$dest = "$env:TEMP\kilo\gradle-9.7.0-bin.zip"
Invoke-WebRequest -Uri "https://mirrors.cloud.tencent.com/gradle/gradle-9.7.0-bin.zip" `
    -OutFile $dest -Proxy "http://127.0.0.1:7897"
Expand-Archive -Path $dest -DestinationPath "$env:TEMP\kilo\gradle970" -Force
```

之后 Gradle 可执行文件位于：
`$env:TEMP\kilo\gradle970\gradle-9.7.0\bin\gradle.bat`

> 建议把解压目录移到固定位置（如 `D:\Dev\Tools\gradle-9.7.0`），下文以临时目录路径为例。

## 编译

在项目根目录（本目录）执行：

```powershell
cd D:\Dev\Codes\Test\mc-launcher-mod
$env:JAVA_HOME = "D:\Dev\Codes\Test\mc-launcher\mc\java\jdk-25.0.4.1+1"
& "$env:TEMP\kilo\gradle970\gradle-9.7.0\bin\gradle.bat" build
```

- 产物：`build\libs\mc-launcher-mod-1.0.0.jar`
- 只检查编译错误：把 `build` 换成 `compileJava`
- 增量编译通常 1~10 秒；首次构建需联网拉取依赖（走 7897 代理）

## 部署

把构建产物覆盖到游戏 mods 目录：

```powershell
Copy-Item "build\libs\mc-launcher-mod-1.0.0.jar" `
    "D:\Dev\Codes\Test\mc-launcher\mc\mods\mc-launcher-mod-1.0.0.jar" -Force
```

部署后重启游戏生效。

## 一键脚本（编译 + 部署）

```powershell
cd D:\Dev\Codes\Test\mc-launcher-mod
$env:JAVA_HOME = "D:\Dev\Codes\Test\mc-launcher\mc\java\jdk-25.0.4.1+1"
& "$env:TEMP\kilo\gradle970\gradle-9.7.0\bin\gradle.bat" build -q
if ($?) {
    Copy-Item "build\libs\mc-launcher-mod-1.0.0.jar" `
        "D:\Dev\Codes\Test\mc-launcher\mc\mods\mc-launcher-mod-1.0.0.jar" -Force
    Get-Item "D:\Dev\Codes\Test\mc-launcher\mc\mods\mc-launcher-mod-1.0.0.jar" | Select-Object LastWriteTime
}
```

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
