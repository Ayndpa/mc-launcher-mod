# mc-launcher-mod Agent 指南

本文件写给后续接手本项目的 AI Agent / 开发者，说明编译与部署的正确做法。改完 Java 代码后按此流程验证与部署。

## 编译（必读）

本机 **没有 gradle wrapper**，PATH 上的 `java` 是 Java 8（Gradle 9.x 直接拒绝启动）。不要尝试 `gradlew` 或裸 `gradle`，会失败。

唯一正确的编译方式（项目根目录执行）：

```powershell
cd D:\Dev\Codes\Test\mc-launcher-mod
$env:JAVA_HOME = "C:\Users\Admin\.vfox\cache\java\v-25.0.2+10\java-25.0.2+10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "D:\Dev\Codes\Test\gradle-9.7.0\bin\gradle.bat" --offline "-Dorg.gradle.java.home=$env:JAVA_HOME" build -q
```

- 只查编译错误：把 `build` 换成 `compileJava`。
- 依赖已全部缓存，**务必加 `--offline`**，不必开代理。
- PowerShell 5.1 下 `-Dorg.gradle.java.home=...` 必须整体加引号，否则会被拆成 task 名。
- 增量编译通常 1~10 秒。

## 部署

编译产物为 `build\libs\mc-launcher-mod-1.0.0.jar`，覆盖到游戏实例 mods 目录：

```powershell
Copy-Item "build\libs\mc-launcher-mod-1.0.0.jar" `
    "D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\mods\mc-launcher-mod-1.0.0.jar" -Force
```

部署后需**重启游戏**才生效。真实实例目录是 `D:\Dev\Codes\Test\mc-launcher\target\debug\instances\default\`；仓库里的 `mc-launcher\src\embedded\` 只是打包资源模板，复制过去不会生效。

## 源码编辑红线

- 所有 `.java` 文件为 UTF-8（无 BOM）且含大量中文字符串。**严禁**用 PowerShell 的 `Set-Content` / `Out-File` 改写源码（默认 GBK 会永久损坏中文）。必须用编辑器工具或 `[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`。
- 文本渲染颜色参数是 **ARGB 8 位十六进制**，必须写 `0xFFFFFFFF` 这种 4 字节形式，写 `0xFFFFFF` 会全透明。
