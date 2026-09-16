<div align="center">

## 紫罗兰Box（VioletBox）

[![License: GPL 3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/Smart-Paocai/violet_Box/tree/main?tab=GPL-3.0-1-ov-file)
[![Telegram Channel](https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=flat-square&logo=telegram)](https://t.me/violettoolbox)
[![Release](https://img.shields.io/badge/Release-v1.1.1-success.svg?style=flat-square)](https://github.com/Buwrt/violet_Box/releases)
[![Modules](https://img.shields.io/badge/%E6%A8%A1%E5%9D%97-3821-blueviolet?style=flat-square)](https://github.com/Buwrt/violet_Box/releases/tag/v1.1.1-V12)

**一款根据用户需求设计的 Android 玩机工具箱，内置 3821 个开源模块（ZIP / LSP / KPM）的下载中心，支持后台下载；为ROOT用户以及非ROOT用户提供更好的玩机体验！**


</div>

---

## 🍴 关于本仓库（Fork 说明）

本仓库 fork 自 [Smart-Paocai/violet_Box](https://github.com/Smart-Paocai/violet_Box)，**保留完整上游提交历史**，同样遵循 **GPL-3.0**。在上游基础上做了十二个版本的改动，**每个版本一次提交、一个标签，可随时回到任意版本**：

| 版本 | 标签 | 改动 |
| --- | --- | --- |
| **V1** | — | 新增「模块下载中心」：内置 40 个 KPM / ZIP 模块，点条目可选版本，不选则默认最新版 |
| **V2** | — | 修掉「无法开始下载」——真因是在线程池里创建 `AlertDialog`，抛 `Can't create handler inside thread`；改为 `main.post()` 回主线程后再建对话框 |
| **V3** | — | 从提交 `b42df36` 完整捞回被删除的**环境检测**功能（`RootDetector` / `HardcodedSignals` / `AdvancedRuntimeDetector` / `DetectFragment` / `fragment_detect.xml`）。安全页**默认**显示环境检测，「使用摇一摇防护」作为设置里的开关，关闭时回落到环境检测。检测页恢复 b42df36 原版紫色 UI |
| **V4** | `v1.1.0-V4` | 模块目录 **40 → 178 个**（ZIP 170 + KPM 8，64 个分类，全部来自开源仓库），新增**实时搜索**：支持按名称 / 作者 / `owner/repo` 仓库全名 / 分类 / 说明搜索，空格分隔多关键词 AND，带结果计数与清空按钮 |
| **V5** | `v1.1.0-V5` / `V5` | **KPM 刷写**（玩机 → 实用功能）：解析 ELF 的 `.kpm.info` 段列出名称 / 版本 / 作者，支持模块目录安装与 boot 内嵌两类来源；模块备份新增「内核模块」类型 |
| **V6** | `v1.1.0-V6` / `V6` | 新页面 UI 统一回原生 Material 设计，抽出 `ModuleRepoAdapter`，列表补空状态与权限说明 |
| **V7** | `v1.1.0-V7` / `V7` | 修复「我在 APatch 里嵌入了 KPM，为什么这里显示没有」——真因是 kptools repack 会重新压缩 kernel，raw 分区扫不到 `kpe` magic；改为 `dd` 导出分区后 unpack 再读 kernel |
| **V8** | `v1.1.0-V8` / `V8` | **写入 Embedded KPM**（对标 FolkPatch）：从你自己的 boot 里刻出 kpimg 重新打补丁，把旧镜像的 superkey 字节原样 `dd` 回去，root 授权完全不变；附带 boot 镜像备份 / 恢复 |
| **V9** | `v1.1.0-V9` / `V9` | 「可刷入的文件」卡片加**删除**按钮（删前弹路径确认，绝不接受删目录）；**运行环境**卡片首次展开、之后记忆折叠状态，点标题行重新展开，折叠时标题右侧保留一行 `ROOT · APatch` 摘要 |
| **V10** | `v1.1.0-V10` / `V10` | 刷入分三条路：卡片「刷入」弹 **嵌入 / 加载**，「选择文件刷入」弹 **嵌入 / 加载 / 安装**。「加载」是运行时热加载，走 KernelPatch supercall（syscall 45），superkey 从用户自己的 boot 镜像读取，用完只在内存里 |
| **V11** | `v1.1.0-V11` / `V11` | **移除 KPM 刷写功能**：删掉 `kpm` 工具包、KPM 刷写页面、boot 嵌入 / supercall 加载 / 模块目录安装、`kpload` 工具、玩机页入口，以及模块备份里的「内核模块」类型。模块下载中心的 KPM 分类保留（那只是下载，不是刷写） |
| **V12** | `v1.1.1-V12` / `V12` | 应用名改为**紫罗兰Box**、版本 **1.1.1**；模块目录重做成三类 **ZIP 模块 1687 / LSP 模块 2087 / KPM 内核模块 47 = 3821 个**（GitHub + GitLab，全部有公开源码且不含格机或破坏性代码）；**支持后台下载**（关掉对话框不停任务，进度同步到通知栏）；下载 / 模块备份 / APK 导出 / 应用备份**全部统一到 `/storage/emulated/0/Download/VioletBox`**（分区存储下有 all-files 授权引导 + root 兜底）；**数量按当前分类联动显示**；镜像与官方两个都保留，官方用**蓝色小字**标注原作者、镜像标灰色；长按卡片可复制开源地址 |

### 🔙 回到任意版本

```bash
git clone https://github.com/Buwrt/violet_Box.git
cd violet_Box
git tag -l                     # v1.1.0-V4 … v1.1.1-V12（同时存在简写 V5…V12）
git checkout v1.1.0-V6         # 回到 V6 的源码
# 或 git checkout V6
```

带 APK 附件的版本：**V4、V5、V6、V7、V8、V9、V12**（每个 release / debug 两个包）。
V10、V11、V11修改版只打了标签没发 Release，需要的话按上面的命令 checkout 后自行编译。

### 📁 当前源码结构（V11 起已移除 KPM 刷写）

```
app/src/main/java/com/violet/box/core/util/    # 核心工具
    VioletPaths.java        # V12  落盘路径中心（公共 Download/VioletBox + root 兜底）
    SelinuxShellUtil.java   # su -c 执行封装（SelinuxShell 通用）
    BatterySysFiles.java  GpuInfoQuery.java  SelinuxStatusReader.java
app/src/main/java/com/violet/box/ui/repo/      # 模块下载中心（V1 起，V12 重做）
    ModuleRepoActivity.java   # 列表 / 搜索 / 版本选择 / 后台下载 + 通知栏进度
    ModuleRepoAdapter.java    # 列表渲染，官方蓝字 / 镜像灰字的来源标注
    ModuleEntry.java          # 清单条目模型（含 official / upstream / host）
    RepoClient.java           # GitHub + GitLab REST 拉取与下载
app/src/main/java/com/violet/box/ui/detect/    # 环境检测 UI（V3 从 b42df36 捞回）
    DetectFragment.java  DetectViewBinder.java
app/src/main/java/com/violet/box/data/detector/ # 环境检测逻辑
    RootDetector.java  HardcodedSignals.java  AdvancedRuntimeDetector.java
app/src/main/java/com/scottyab/rootbeer/       # RootBeer 本地化（Maven 取不到，改为内置源码）
app/src/main/assets/module_repo.json           # V12：3821 个模块清单（ZIP 1687 / LSP 2087 / KPM 47）
app/src/main/res/layout/
    activity_module_repo.xml  item_module_repo.xml
    dialog_repo_download.xml  dialog_repo_header.xml  item_repo_release.xml
```

### 📄 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/V4_模块目录与搜索功能说明.md`](docs/V4_模块目录与搜索功能说明.md) | 178 个模块的来源、收录与排除规则、KPM 生态说明、搜索用法、**完整清单** |
| [`docs/V5_KPM刷写机制与功能说明.md`](docs/V5_KPM刷写机制与功能说明.md) | KPM 刷写的三条路线（嵌入 / 加载 / 安装）、ELF `.kpm.info` 结构、V5 做了什么 |
| [`docs/V6_UI统一与空状态修复说明.md`](docs/V6_UI统一与空状态修复说明.md) | 新页面为什么改回原生 UI、空状态与 root 权限提示 |
| [`docs/V7_嵌入KPM识别与备份.md`](docs/V7_嵌入KPM识别与备份.md) | 「已嵌入却显示不出来」的根因与 `kpe`  extras 链解析 |
| [`docs/V8_嵌入KPM写入机制与功能说明.md`](docs/V8_嵌入KPM写入机制与功能说明.md) | 与 FolkPatch 的对比、superkey 字节迁移、11 道写入闸门、已知限制 |
| [`docs/V9_删除按钮与运行环境折叠.md`](docs/V9_删除按钮与运行环境折叠.md) | 删除按钮的安全边界、运行环境折叠状态的持久化与摘要 |
| [`docs/V10_嵌入加载安装三选一.md`](docs/V10_嵌入加载安装三选一.md) | 三条刷入路径的区别、supercall 热加载的实现与限制 |
| [`docs/V11_移除KPM刷写.md`](docs/V11_移除KPM刷写.md) | 本次删除的完整清单、保留项及原因、如何把功能找回来 |
| [`docs/V11修改版_恢复KPM备份.md`](docs/V11修改版_恢复KPM备份.md) | V11 误删的「内核模块」备份怎么加回来的（只读，不恢复刷写） |
| [`docs/V12修复版_说明.md`](docs/V12修复版_说明.md) | 后台下载的实现、落盘路径的分区存储坑与三级兜底、分类计数修复、3821 个模块的采集与校验方式 |
| [`docs/环境检测恢复说明_b42df36.md`](docs/环境检测恢复说明_b42df36.md) | 环境检测是怎么从 `b42df36` 捞回来的、修了哪些 bug |
| [`docs/ROOT隐藏模块全谱系与原理手册.md`](docs/ROOT隐藏模块全谱系与原理手册.md) | 隐藏 Root 的模块原理（Zygisk / PIF / TrickyStore / SUSFS 等） |
| [`docs/violet_Box_仓库分析报告.md`](docs/violet_Box_仓库分析报告.md) | 上游仓库的整体结构与代码分析 |

### ⚠️ 使用提醒

- **签名**：`app/build.gradle.kts` 里 release 构建用 debug 密钥签名（没有原作者的 keystore），所以打出的包**不能覆盖安装官方版**；且 release 与 debug 签名不同，两者切换前都要先卸载旧版。有正式 keystore 的话改回即可。
- **文件落在哪里**：V12 起下载、模块备份、APK 导出、应用备份**全部统一到 `/storage/emulated/0/Download/VioletBox`**。Android 11+ 分区存储会限制应用写公共目录，应用内已做了「申请所有文件访问权限 + root 兜底」两条路，实在写不进去才会退回私有目录。
- **模块的开源口径**：收录标准是**有公开源码 + 源码不含格机或破坏性代码**（不要求 LICENSE 文件）。危险的擦除 / 格式化类项目和 Xml 一概不收，但模块本身的风险请自行判断，刷机有风险。
- **模块列表自动更新**：`ModuleRepoActivity` 里的 `CATALOG_URL` 指向仓库 raw 文件，维护一份 JSON 就能不升级 APK 更新目录；拉不到时自动回退内置清单。作者发布新版本 module 时，应用会实时拉最新 release。
- **`补丁包/`**：早期版本留下的增量包形态（含源码副本），仅供参考；主源码看 `app/`。

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
| 🧰 模块下载中心 | **3821 个开源模块**：ZIP 1687 / LSP 2087 / KPM 47，分类筛选、实时搜索、选版本、**后台下载**、长按复制开源地址 |
| 💾 模块备份 | ZIP 模块与内核模块快照，备份到 `Download/VioletBox/备份/` |

## 🛡️ 摇一摇广告防护（免 Root）

通过 [Shizuku](https://shizuku.rikka.app/zh-hans/) 以 shell 权限限制指定应用的加速度传感器，阻止开屏广告的摇一摇触发与跳转。

- **白名单式管理**：自主勾选需要防护的应用
- **免 Root**：需配合 Shizuku 使用（通过无线调试/PC端ADB命令启动，重启手机后需重新启动服务）
- ⚠️ 被限制的应用其摇一摇、体感等功能也会一并受影响，请按需开启

## 📥 下载

最新版 **V12（紫罗兰Box · 1.1.1）**，APK 作为 Release 附件提供：

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| [**VioletBox-V12-release.apk**](https://github.com/Buwrt/violet_Box/releases/download/v1.1.1-V12/VioletBox-V12-release.apk) | 3.8 MB | `29a75d2c493d1602c832600be89ab7edb2222d8dbe828016aa51d15063a0303c` |
| [**VioletBox-V12-debug.apk**](https://github.com/Buwrt/violet_Box/releases/download/v1.1.1-V12/VioletBox-V12-debug.apk) | 18 MB | `46a679ee84cd6634df0db44d7ab6ffce231653ba11f074a9dce13f421c520c36` |

👉 [Releases 页面](https://github.com/Buwrt/violet_Box/releases) · [V12 发布说明](https://github.com/Buwrt/violet_Box/releases/tag/v1.1.1-V12)

日常用 release 包；debug 包带调试符号，排查问题时用。两者源码相同、功能一致。
> ⚠️ release 与 debug **签名不同**，互相不能直接覆盖安装，切换前请先卸载旧版。

之前的 V4~V9 也各有一份 Release 附件；V10 / V11 / V11修改版只打了标签没发包，需要的话 `git checkout V11` 自行编译。
- 交流群组：[Telegram 频道](https://t.me/violettoolbox)

## 🛠️ 编译步骤

1. 克隆本项目到本地：
   ```bash
   git clone https://github.com/Buwrt/violet_Box.git
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
