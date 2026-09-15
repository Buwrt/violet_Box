<div align="center">

## 紫罗兰盒子 (VioletBox)

[![License: GPL 3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/Smart-Paocai/violet_Box/tree/main?tab=GPL-3.0-1-ov-file)
[![Telegram Channel](https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=flat-square&logo=telegram)](https://t.me/violettoolbox)
[![Release](https://img.shields.io/badge/Release-v1.1.0-success.svg?style=flat-square)](https://github.com/Smart-Paocai/violet_Box/releases)

**一款根据用户需求设计的 Android 玩机工具箱，我们后续将集成更多移动端的实用功能，为ROOT用户以及非ROOT用户提供更好的玩机体验！**


</div>

---

## 🍴 关于本仓库（Fork 说明）

本仓库 fork 自 [Smart-Paocai/violet_Box](https://github.com/Smart-Paocai/violet_Box)，**保留完整上游提交历史**，同样遵循 **GPL-3.0**。在上游基础上做了四个版本的改动：

| 版本 | 改动 |
| --- | --- |
| **V1** | 新增「模块下载中心」：内置 40 个 KPM / ZIP 模块，点条目可选版本，不选则默认最新版 |
| **V2** | 修掉「无法开始下载」——真因是在线程池里创建 `AlertDialog`，抛 `Can't create handler inside thread`；改为 `main.post()` 回主线程后再建对话框 |
| **V3** | 从提交 `b42df36` 完整捞回被删除的**环境检测**功能（`RootDetector` / `HardcodedSignals` / `AdvancedRuntimeDetector` / `DetectFragment` / `fragment_detect.xml`）。安全页**默认**显示环境检测，「使用摇一摇防护」作为设置里的开关，关闭时回落到环境检测。检测页恢复 b42df36 原版紫色 UI |
| **V4** | 模块目录 **40 → 178 个**（ZIP 170 + KPM 8，64 个分类，全部来自开源仓库），新增**实时搜索**：支持按名称 / 作者 / `owner/repo` 仓库全名 / 分类 / 说明搜索，空格分隔多关键词 AND，带结果计数与清空按钮 |

### 新增文件一览

```
app/src/main/java/com/violet/box/ui/repo/          # 模块下载中心
    ModuleRepoActivity.java  ModuleRepoAdapter.java  ModuleEntry.java  RepoClient.java
app/src/main/java/com/violet/box/ui/detect/        # 环境检测 UI（b42df36 捞回）
    DetectFragment.java  DetectViewBinder.java
app/src/main/java/com/violet/box/data/detector/     # 环境检测逻辑（b42df36 捞回）
    RootDetector.java  HardcodedSignals.java  AdvancedRuntimeDetector.java
app/src/main/java/com/scottyab/rootbeer/           # RootBeer 本地化（Maven 取不到，改为内置源码）
app/src/main/assets/module_repo.json                # 178 个模块目录
app/src/main/res/layout/
    activity_module_repo.xml  item_module_repo.xml  fragment_detect.xml
```

### 📄 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/V4_模块目录与搜索功能说明.md`](docs/V4_模块目录与搜索功能说明.md) | 178 个模块的来源、收录与排除规则、KPM 生态说明、搜索用法、**完整清单** |
| [`docs/环境检测恢复说明_b42df36.md`](docs/环境检测恢复说明_b42df36.md) | 环境检测是怎么从 `b42df36` 捞回来的、修了哪些 bug |
| [`docs/ROOT隐藏模块全谱系与原理手册.md`](docs/ROOT隐藏模块全谱系与原理手册.md) | 隐藏 Root 的模块原理（Zygisk / PIF / TrickyStore / SUSFS 等） |
| [`docs/violet_Box_仓库分析报告.md`](docs/violet_Box_仓库分析报告.md) | 上游仓库的整体结构与代码分析 |

### ⚠️ 两点提醒

- **签名**：`app/build.gradle.kts` 里 release 构建改用 debug 密钥签名（因为没有原作者的 keystore），所以打出的包**不能覆盖安装官方版**，装之前需先卸载旧版。有正式 keystore 的话改回即可。
- **模块目录可远程更新**：`ModuleRepoActivity` 里的 `CATALOG_URL` 指向仓库 raw 文件，维护一份 JSON 就能不升级 APK 更新目录；拉不到时自动回退内置清单。

---

## ✨ 功能一览

| 功能 | 说明 |
| --- | --- |
| 🛡️ 摇一摇广告防护 | 无需ROOT，在激活Shizuku之后，通过限制目标应用传感器，阻止开屏摇一摇广告触发与跳转 |
| 🔄 SELinux 管理 | 查看 / 切换 SELinux 模式，支持开机自启 |
| 💾 分区管理 | 系统分区读取 / 写入 / 擦除 / 回读 |
| 🧬 字库备份 | 完整备份底层字库为 bin，可在 EDL / bootloader / 编程器刷写 |
| 📦 模块管理 | 批量刷入 Magisk / KernelSU / APatch 模块 |
| 📱 应用管理 | 提取 / 卸载 / 冻结 / 解冻应用 |
| 🆔 安卓 ID 修改 | 修改设备安卓 ID |
| 🎭 全局机型伪装 | resetprop 修改机型与构建指纹 |
| ☁️ Payload 云提取 | 在线下载 OTA 固件并提取指定分区镜像 |
| 🧩 紫罗兰插件 | 内核伪装 / TrickyStore扩展 / 隐藏应用列表配置 |

## 🛡️ 摇一摇广告防护（免 Root）

通过 [Shizuku](https://shizuku.rikka.app/zh-hans/) 以 shell 权限限制指定应用的加速度传感器，阻止开屏广告的摇一摇触发与跳转。

- **白名单式管理**：自主勾选需要防护的应用
- **免 Root**：需配合 Shizuku 使用（通过无线调试/PC端ADB命令启动，重启手机后需重新启动服务）
- ⚠️ 被限制的应用其摇一摇、体感等功能也会一并受影响，请按需开启

## 📥 下载

- 前往 [Releases](https://github.com/Smart-Paocai/violet_Box/releases) 下载最新版本
- 交流群组：[Telegram 频道](https://t.me/violettoolbox)

## 🛠️ 编译步骤

1. 克隆本项目到本地：
   ```bash
   git clone https://github.com/Smart-Paocai/violet_Box.git
   ```
2. 使用 Android Studio 打开项目。
3. 等待 Gradle 同步完成。
4. 点击运行（Run）或在终端执行以下命令进行编译：
   ```bash
   ./gradlew :app:assembleDebug
   ```

---

## ⚠️ 免责声明

本程序仅供学习交流，所有功能均为正常玩机功能，请勿用于非法用途，程序所有功能不针对任何商业项目，非法使用造成的任何后果均与本程序无关!

---

## 📄 许可证

本项目采用 [GPL-3.0 License](LICENSE) 开源许可证。
