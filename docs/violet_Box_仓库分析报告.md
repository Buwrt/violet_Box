# 紫罗兰盒子（VioletBox）仓库分析报告

> 仓库地址：<https://github.com/Smart-Paocai/violet_Box>
> 分析日期：2026-09-14（基于 main 分支最新代码，commit 076dc84）

---

## 一、一句话总结

**紫罗兰盒子（VioletBox）是一款面向 Android ROOT 玩家的中文"玩机工具箱"App**，集分区读写、字库备份、Magisk/KSU 模块管理、OTA 固件云提取、机型/内核伪装、摇一摇广告防护等底层功能于一体——相当于把"搞机圈"里散落的各类命令行操作封装成了一个带 GUI 的移动端工具。

---

## 二、仓库基本信息

| 项目 | 内容 |
|---|---|
| 仓库 | Smart-Paocai/violet_Box |
| 定位 | Android 玩机工具箱（移动端 App） |
| Star / Fork | **334** / 13 |
| 主语言 | Java（约 1.4 万行源码，混合 Kotlin） |
| 许可证 | **GPL-3.0** |
| 创建时间 | 2026-04-15 |
| 最近更新 | 2026-09-06（97 个提交） |
| 发布版本 | v1.0.0 正式版（APK 17.8MB，2026-04-24 发布） |
| 当前源码版本 | versionName 1.1.0（versionCode 2，未发 Release） |
| 官网 | <https://violettool.top/> |
| 社区 | Telegram 频道 t.me/violettoolbox |
| Topics | android、payload、root、violetbox、violettoolbox |
| 作者 | "泡菜"（GitHub 账号 Smart-Paocai / smartpaocai 两个身份交替提交） |

---

## 三、项目结构

```
violet_Box/
├── app/                        # Android 应用主模块
│   └── src/main/
│       ├── java/com/violet/box/
│       │   ├── core/util/      # SELinux、电池、GPU 检测工具
│       │   ├── data/model/     # 数据模型
│       │   ├── receiver/       # 开机广播（SELinux 自启）
│       │   └── ui/            # 全部界面（12 个功能模块）
│       ├── aidl/               # Shizuku ShellService AIDL
│       ├── proto/              # AOSP update_metadata.proto（OTA 解析）
│       └── assets/             # 内置 violet_box_module.zip（静默安装）
├── violet_box_module/          # Magisk/KSU/APatch 核心扩展模块
│   ├── customize.sh / service.sh / post-fs-data.sh
│   ├── tools/ksu_susfs_arm64   # 内置 susfs 二进制
│   └── module.prop
└── build.gradle.kts 等         # Gradle 9.3.1 构建体系
```

**技术栈**：Java 11 + Kotlin、Jetpack Compose(Material3) 与传统 View 混合 UI、OkHttp 4.12（网络）、Apache commons-compress + xz（OTA 解压）、Shizuku API 13.1.5（免 Root 能力）、Protobuf（OTA 元数据）、minSdk 24 / targetSdk 36。

---

## 四、功能全景（源码逐模块核实）

| 功能 | 实现类 | 底层原理 | 需要权限 |
|---|---|---|---|
| 🛡️ 摇一摇广告防护 | SafetyPageController / SensorCommand / SafetyShell | 经 Shizuku 以 shell 身份执行 `cmd sensorservice set-uid-state <包名> idle --user <uid>`，把目标应用标记为 idle，系统即限制其加速度传感器，开屏"摇一摇"跳转广告失效 | **免 Root**（Shizuku） |
| 🔄 SELinux 管理 | SelinuxManagerActivity + BootCompletedReceiver | `getenforce`/`setenforce 0/1` 切换宽容/严格模式，开机广播自动恢复 | Root |
| 💾 分区管理 | PartitionManagerActivity | `ls /dev/block/by-name` 枚举分区，`su -c dd` 直接对分区读/写/擦除/回读 | Root |
| 🧬 字库备份 | FontLibraryBackupActivity | dd 整块备份底层字库为 bin，可用于 9008(EDL)/bootloader/编程器救砖 | Root |
| 📦 模块管理/备份 | ModuleManagerActivity / ModuleBackupActivity | 批量刷入与备份 Magisk / KernelSU / APatch 模块 | Root |
| 📱 应用管理 | AppManagerActivity | 应用提取（APK 导出）/卸载/冻结/解冻（pm disable） | 免 Root 起 |
| 🆔 安卓 ID 修改 | DeviceIdModifyActivity | 修改设备 android_id | Root |
| 🎭 全局机型伪装 | GlobalDeviceSpoofActivity + violet_box_module | `resetprop` 覆写 `ro.product.*` / `ro.build.fingerprint`，并写入持久化脚本 | Root + 模块 |
| ☁️ Payload 云提取 | PayloadActivity + PayloadCore（969 行核心） | 解析 AOSP `update_metadata.proto` 元数据，HTTP Range 从云端 OTA 包按块抽取指定分区镜像，支持 REPLACE/REPLACE_BZ/REPLACE_XZ 操作，无需下载全量固件 | 免 Root |
| 🧩 内核伪装 | KernelDisguiseActivity | susfs `set_uname` 伪装 uname 内核版本与构建信息 | Root + susfs 内核 |
| 🧩 TrickyStore 扩展 | TrickyStore*Activity（4 个） | 配置密钥证明伪装（app 列表、安全补丁级别、指纹 hash） | Root + TrickyStore |
| 🧩 隐藏应用列表 | HiddenAppListConfigActivity | 生成 Hide My Applist 风格的隐藏配置 | Root + HMA |
| ⚡ 快捷重启 | MainActivity | Recovery / Bootloader / 系统重启菜单 | Root |

---

## 五、核心技术亮点（源码级分析）

### 1. 摇一摇广告防护（本项目最具差异化免 Root 功能）
- **原理**：Android 的 `sensorservice` 支持 per-UID 的 idle 状态。应用处于 idle 时系统挂起其传感器访问——广告 SDK 的摇一摇监听拿不到加速度事件，跳转就不会触发。
- **工程细节**：`SensorCommand.build()` 用正则白名单严格校验包名与参数；`SafetyShell` 用单线程池 + CountDownLatch 管理 Shizuku Binder 连接，卡死的同步 Binder 调用会拒绝新请求而非堆积线程；支持 set/get/reset 三态与重启后恢复。
- **代价**：被限制应用自身的摇一摇、体感功能一并失效，所以做成白名单式按需勾选。

### 2. OTA Payload 云提取（最大单体，969 行）
- 直接内置 AOSP `chromeos_update_engine` 的 `update_metadata.proto`，解析 `payload.bin` 的 `DeltaArchiveManifest`，列出各分区大小、原始占用与 SHA256。
- 支持**本地 zip** 与**云端 URL** 两种来源；云端模式用 HTTP Range 请求按 operation 的 dataOffset/dataLength 精准拉块，配合 BZip2/XZ 流式解压，"不用下载 5GB 固件也能抽出 boot.img"。

### 3. 双形态架构：App + Magisk 模块
- 仓库里的 `violet_box_module` 是独立 Magisk/KSU/APatch 模块：`service.sh` 阶段执行 `ksu_susfs set_uname` 伪装内核、`resetprop` 伪装机型，配置持久化在 `/data/adb/violet_kernel_spoof/config.sh`。
- App 的 `MainActivity.maybeAutoInstallCoreModuleSilently()` 会从 assets 里的内置 zip **静默自动安装**该模块——App 是"遥控器"，模块是"执行器"。

### 4. 工程质量
- release 开启 R8 + 资源收缩，APK 从 13.1MB 压到 3.4MB；
- 所有 su 命令统一走 `shellEscape()` 单引号转义防注入；
- 线程模型讲究：传感器操作用独立单线程 Executor 保证 Activity 重建不打断已接受的变更；
- 中英双语资源（localeFilters zh/en），深色模式适配；
- 无任何广告/统计/追踪 SDK 依赖。

---

## 六、开发历程（97 commits 时间线）

| 阶段 | 事件 |
|---|---|
| 2026-04-15 | 初始提交 |
| 2026-04-19~22 | 密集开发：分区管理、字库备份、模块管理、Payload 云提取、机型伪装、UI 打磨 |
| 2026-04-24 | 发布 **v1.0.0 正式版** |
| 2026-04-26 | 自动安装核心扩展模块；修复低版本安卓闪退 |
| 2026-05-13~15 | 新增模块备份、SUSFS 管理卡片，版本号升至 1.1.0，之后进入**近 4 个月沉寂** |
| 2026-09-05~06 | 复活更新：R8 体积优化、移除环境检测、新增摇一摇广告防护、品牌/README 重构；期间出现一次"三次提交回退又 Revert 回来"的反复 |

**观察**：个人开发者项目，节奏是典型的"爆发式冲刺 → 长期沉寂 → 回归维护"。两个 GitHub 账号（大小号）交替提交，2026-09-06 的提交记录明确显示作者在"测试大号提交身份"。Star 数 334 在中文玩机圈属于小有热度的水平。

---

## 七、风险与定性评估

**✅ 不是恶意软件**
- 所有功能都是玩机圈公开、常见的技术（dd、resetprop、susfs、TrickyStore、HMA 均为社区知名开源方案）；
- 无网络上报、无广告 SDK、权限申请克制（仅 QUERY_ALL_PACKAGES / INTERNET / 开机广播）；
- GPL-3.0 完整开源，可自行审计编译。

**⚠️ 但需要认识到：**
1. **变砖风险高**：分区 dd 写入/擦除是直接对块设备操作，一旦选错分区或写坏 GPT/EFS，轻则丢基带串号、重则需 EDL 救砖——字库备份功能本身的存在就是为这种场景兜底的。
2. **伪装功能属灰色地带**：机型伪装、内核伪装、TrickyStore、隐藏应用列表的组合，常见用途是绕过 App 的设备风控（游戏防封、银行/支付类 App 的 Root 与环境检测）。README 虽有"仅供学习交流"免责声明，但实际使用场景读者自明。
3. **静默安装模块**：App 会自动往 `/data/adb` 刷入内置模块，对普通用户而言透明度不足——虽然源码可见，行为本身是合规的。
4. **单点维护**：单人项目 + 长期沉寂期，可靠性依赖作者个人热情。

---

## 八、适用人群

| 适合 | 不适合 |
|---|---|
| 会救砖、懂 dd/resetprop 的 ROOT 玩家 | 小白用户（一个误操作就变砖） |
| 想免 Root 治理摇一摇广告的人 | 只想正常用手机的用户 |
| 需要经常从 OTA 提取镜像的 ROM 折腾者 | 对"设备伪装"合规性敏感的用户 |

**总评**：这是一份完成度不错的中文玩机工具箱——功能覆盖广、代码有工程素养（转义校验、线程管理、体积优化都看得出用心）、完全开源可审计。它在"工具"属性上是合格的；但它的核心用户画像是搞机圈玩家，其底层的分区写入与伪装能力天然携带高风险与灰色属性，普通用户不建议碰。

---

*报告依据：仓库元数据（GitHub API）、v1.0.0 Release 说明、main 分支源码（25MB zip 全量下载分析）、提交历史与模块 README 综合分析生成。*
