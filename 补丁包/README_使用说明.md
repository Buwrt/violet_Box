# 紫罗兰盒子 · 功能补丁（模块下载中心 / 环境检测 / KPM 刷写）

> 目标：给 violet_Box 增加 ① 模块下载中心（可选版本，默认最新）② 恢复 b42df36 的环境检测 ③ KPM 刷写 + 内核模块备份 ④ **boot 镜像嵌入写入（等同 FolkPatch）**

---

## 📌 版本线（最新是 **V8**）

| 版本 | 重点 | APK |
|---|---|---|
| V1 | 首次加入模块下载中心（40 个模块，选版本 / 默认最新） | `VioletBox-V1-{release,debug}.apk` |
| V2 | 修掉「无法开始下载」——真因是在线程池里创建 `AlertDialog`，抛 `Can't create handler inside thread`；改为 `main.post()` | `VioletBox-V2-*.apk` |
| V3 | 从 `b42df36` 完整捞回环境检测（`RootDetector` / `HardcodedSignals` / `AdvancedRuntimeDetector` / `DetectFragment` / `fragment_detect.xml`），安全页**默认**环境检测，摇一摇防护作为设置里的开关；所有 UI 恢复原版设计 | `VioletBox-V3-*.apk` |
| V4 | 模块目录 **40 → 178**（ZIP 170 + KPM 8，64 个分类），新增**实时搜索**（名称/作者/仓库全名/分类/说明，多关键词 AND，带结果计数与清空按钮） | `VioletBox-V4-*.apk` |
| **V5** | 玩机 → 实用功能新增 **KPM刷写**（ELF `.kpm.info` 解析 + 刷入/启停/卸载/环境卡片）；模块备份新增 **「系统模块 (ZIP) / 内核模块 (KPM)」** 类型切换 | `VioletBox-V5-*.apk` |
| **V6** | UI 统一到 App 原生设计：两页改用 `AppBar + Toolbar`（**修掉返回键误用指南针图标**）+ 青色 FAB + 白卡列表 + 实色徽章 + 实心「下载最新」按钮；备份页加**居中空状态**并说明「ROOT 有效但没装 KPM」 | `VioletBox-V6-*.apk` |
| **V7** | 支持 **Embed（已嵌入）路线**：流式扫描 boot/init_boot 分区里的 `kpe` 头链，把嵌入的 KPM 载荷（完整 ELF）抠出来 → 解析元信息 → KPM刷写页新增「已嵌入 · boot 镜像」区块与「导出」、备份页可备份已嵌入 KPM | `VioletBox-V7-*.apk` |
| **V8** | **能写入了**：把 KPM **嵌入 boot 镜像**（和 FolkPatch 同一条 kptools 管线）。读取方式也换成「dump → kptools unpack → 读解包出来的 kernel」，修掉「已嵌入却显示没有」。新增：嵌入 / 移除 / boot 镜像备份恢复、一排出厂级校验（先备份 → 重打包 → 回解包校验 → 通过才落盘）、**superkey 字段原样回填所以不会掉 ROOT** | `VioletBox-V8-*.apk` |

V8 改动的文件：

```
新增 java/kpm/KpmPreset.java                    # 定位/校验 kpimg（KP1158 头），抠出 kpimg 与 superkey 字段
新增 java/kpm/KpmEmbedTool.java                 # 嵌入写入管线：dump/unpack/重打补丁/回填 key/repack/校验/刷入/备份恢复
改动 java/kpm/KpmShell.java                     # 新增 root 流式读写、分区/文件尺寸、kptools 候选分区与预设探测
改动 java/ui/module/KpmManagerActivity.java     # 「安装 / 嵌入」二选、已嵌入行「移除」、boot 备份恢复菜单
改动 java/ui/module/ModuleBackupActivity.java   # 已嵌入 KPM 改用 kptools 解包后的通道读取（更准）
改动 layout/activity_kpm_manager.xml            # 环境卡加「嵌入 boot：」一行
改动 menu/menu_kpm.xml                          # 新增「boot 镜像备份 / 恢复」
```

详见 **`/workspace/V8_嵌入KPM写入机制与功能说明.md`**（含与 FolkPatch 的源码级对比、superkey 保留原理、每一道校验）。
V5 改动的文件：

```
新增 java/kpm/KpmInfo.java                      # ELF .kpm.info 解析 + id 规则（纯 Java，无 native）
新增 java/kpm/KpmShell.java                     # root shell：环境探测 / 清单 / 刷入 / 启停 / 卸载
新增 java/ui/module/KpmManagerActivity.java      # KPM 刷写页
新增 layout/activity_kpm_manager.xml / item_kpm.xml / item_kpm_header.xml
新增 drawable/ic_kpm.xml
改动 layout/fragment_explore_placeholder.xml     # 实用功能加「KPM刷写」入口
改动 MainActivity.java / AndroidManifest.xml
改动 layout/activity_module_backup.xml           # 加类型切换 Chip
改动 java/ui/module/ModuleBackupActivity.java    # KPM 备份模式
改动 proguard-rules.pro                          # -keep com.violet.box.kpm.**
```

详见 **`/workspace/V5_KPM刷写机制与功能说明.md`**（含 KPM 三种刷写路径的源码级分析）。
V4 目录来源 / 收录排除规则见 **`/workspace/V4_模块目录与搜索功能说明.md`**。

---

## 一、APK 已经打好了 ✅

两个包都在 `/workspace/apk/`：

| 文件 | 体积 | 说明 |
|---|---|---|
| `VioletBox-V8-release.apk` | **3.59 MB** | 开 R8 + 资源收缩，多个 dex 合成 1 个，**推荐用这个** |
| `VioletBox-V8-debug.apk` | 18.3 MB | 未压缩、可断点调试，出问题用它复现 |

`versionCode=2 / versionName=1.1.0`，包名 `com.violet.box`，均已签 v2 签名。

### ⚠️ 关于签名，必须知道

- **签名用的是我这边的 debug 密钥**，不是原作者的发布密钥。
- 因此它**无法覆盖安装已装好的官方紫罗兰盒子**（Android 会报「签名不一致」）。
- 安装前请先卸载旧版，或装到另一个用户/profile。**应用数据会清空。**
- 想要能无缝升级，得用同一把 keystore 重签；把你的 `.jks` 给我，或者按第五节自己编译。

### 我是怎么在断网环境里把它编出来的

| 缺口 | 解决办法 |
|---|---|
| Maven 依赖拉不到 | 腾讯 `mirrors.tencent.com/nexus/repository/maven-public` + 阿里云 `maven.aliyun.com/repository/google`，AGP / Kotlin / OkHttp / Compose 全部拉齐 |
| Gradle 发行包 | 腾讯 `mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip`，SHA256 与官方一致 |
| Android SDK 完全拿不到 | 手工拼最小 SDK：Robolectric `android-all` 当 `android.jar`；aapt2 从 Maven 解出；其余可执行文件占位补齐 |
| 唯一一个 `.aidl` 无法编译 | 本地编译时手写 Java 版 `IShellService` + `aidl = false`；**推送到 GitHub 的版本已还原为原始 `.aidl` + `aidl = true`** |
| `core-for-system-modules.jar` | `jimage extract` 从 JDK 21 `lib/modules` 提取 java.base 重打成 jar |
| R8 报缺 `java.util.logging` | 把 JDK 的 `java.logging` 模块补进 android.jar（比 `-dontwarn` 更忠实） |

编译日志：`assembleDebug` / `assembleRelease` 均 **BUILD SUCCESSFUL**（release 52 个 task）。

---

## 二、功能设计

### 模块下载中心（V1/V2/V4）

| 操作 | 行为 |
|---|---|
| 点 **整行** | 弹出版本单选列表，**默认选中第一项**（标为「★ 最新版（默认）」），没手动选直接点下载 = 用最新版 |
| 点 **右侧「下载最新」按钮** | 跳过选择，直接拉 GitHub 最新 Release |
| 顶部 Chips | 全部 / ZIP 模块 / KPM 内核模块 筛选 |
| 搜索框（V4） | 名称 / 作者 / 仓库全名 / 分类 / 说明，多关键词 AND，实时过滤 + 「匹配 M / N 个模块」计数 |
| 右上角「刷新清单」或下拉刷新 | 从 GitHub raw 拉最新清单，不重新发版就能改目录 |

下载后文件落在 `Android/data/com.violet.box/files/Download/VioletBox/`，弹窗提供「打开文件」（交给 Magisk / KernelSU / APatch）与「分享到 root 管理器」。

> 项目原有的 `res/xml/file_paths.xml` 已含 `<external-files-path path="." />`，FileProvider 直接用，**不需要改任何配置**。

### KPM 刷写（V5）

- 入口：玩机 → 实用功能 → **KPM刷写**（占用了原来的「占位」格子）
- **环境卡片**：ROOT / APatch 版本 / `kptools` / 模块目录状态
- **已刷入列表**：`/data/adb/ap/kpm/<id>/`，解析 name/version/author/license/description，可启用·禁用 / 卸载
- **可刷入的文件**：扫描下载目录里的 `.kpm`，显示元信息并可刷入；右上角「选择文件」走 SAF 选任意目录
- 刷入前确认弹窗会显示元信息 + 目标目录 + 「需重启生效」；解析不出 `.kpm.info` 时**明确警告**
- 刷入完成后给「打开 APatch」按钮（热加载只能在 APatch 里做，见下）

### 模块备份（V5）

顶部 Chip 切换：

- **系统模块 (ZIP)** — 原行为，扫 `/data/adb/modules/*`
- **内核模块 (KPM)** — 扫 `/data/adb/ap/kpm/<id>/<id>.kpm`，并兼容社区目录 `/data/adb/kpm/*.kpm`

KPM 模式备份到 `/storage/emulated/0/Magisk模块备份/内核模块/`，每个 KPM 打成只含 `<id>.kpm` 的 zip。
**已嵌入的 KPM 也能备份**（名字带「（已嵌入）」后缀），V8 起它们的来源是 `kptools unpack` 后的 kernel，不再是裸分区扫描。

### 嵌入 boot 镜像（V8）

- 入口：玩机 → 实用功能 → **KPM刷写** → 右下角「选择文件刷入」→ 选 `.kpm` → 弹「刷入方式」
  - **安装到模块目录**（默认，可随时卸载）
  - **嵌入到 boot 镜像**（随内核最早启动，等同 FolkPatch 的 Embed）
- 环境卡片新的一行「嵌入 boot：」会如实写明能不能嵌入、用的是哪个分区、KernelPatch 版本；不能用时给出原因
- 已嵌入的条目现在有 **导出** 和 **移除** 两个按钮；「移除」= 重新打包镜像并去掉那一项
- Toolbar 菜单 → **boot 镜像备份 / 恢复**：可立刻备份当前 boot 分区，或把 `/sdcard/Download/VioletBox/boot-backup-*.img` 写回去

安全流程（每一步失败都会停在这里，绝不继续）：

```
备份原镜像 → dump 分区 → kptools unpack → 提取当前 kpimg
→ kptools -p ... -M <新模块> -E <保留项...> → 回填原始 superkey 字段
→ 再列一遍核对数量 → kptools repack → 大小不得超过分区
→ 把 new-boot.img 重新解包校验（数量 + superkey 一致）→ 才允许 dd 写回
```

任何一步失败都会明确告诉你「**没有写入 boot 分区**」，并给出备份文件路径。

> V5 的限制#3「没做自动 Embed」在 V8 解除——但请记住：**任何重刷 boot 的行为都有可能变成砖**，所以默认按钮是「仅生成」，且原镜像永远先落到 `/sdcard/Download/VioletBox/`。

---

## 三、怎么用这条命令打补丁

```bash
python3 apply_patch.py /path/to/violet_Box
```

脚本会自动做 6 件事，并把被改的文件备份成 `*.vbback`：

| # | 动作 | 目标路径 |
|---|---|---|
| 1 | 复制 Java 源文件 | `app/src/main/java/com/violet/box/ui/repo/` |
| 2 | 复制布局 | `app/src/main/res/layout/` |
| 3 | 复制模块清单 | `app/src/main/assets/module_repo.json` |
| 4 | 注册 Activity | `AndroidManifest.xml` |
| 5 | 探索页占位按钮 → 入口 | `fragment_explore_placeholder.xml` |
| 6 | 加跳转 | `MainActivity.java` |

> V5 新增的 KPM 文件请手工复制：`java/kpm/*` → `com/violet/box/kpm/`，`java/ui/module/*` → `com/violet/box/ui/module/`，`layout/activity_kpm_manager.xml`、`item_kpm*.xml` → `res/layout/`，`drawable/ic_kpm.xml` → `res/drawable/`，并在 Manifest 里注册 `.ui.module.KpmManagerActivity`。

**第 5 步有个巧思**：探索页最后一行网格的第二个格子原本是个 `visibility="invisible"` 的占位空位，我直接把它改造成入口——**布局结构一行没动**，不会出现错位。

---

## 四、内置清单（178 个真实模块）

清单在 `assets/module_repo.json`（`{schema, updated, notice, modules[]}`），按检测用途分组：

| 分组 | 收录模块 |
|---|---|
| 完整性 BASIC/DEVICE | PIF inject、PlayIntegrityFork、Sensitive Props |
| **密钥证明 STRONG** | TrickyStore、TrickyStoreOSS、TEESimulator、TEESimulator-RS、OhMyKeymint、Tricky Addon |
| Zygisk 注入层 | Zygisk Next、ReZygisk、NeoZygisk、OnyxZygisk |
| 痕迹隐藏 | Treat Wheel、NoHello、Mountify |
| 内核 SUSFS | susfs4ksu-module |
| 应用列表隐藏 | HMA-OSS、Hide My Applist |
| 聚合工具箱 | Integrity Box、Specter、AlwaysStrong、YuriKey |
| **KPM 内核模块** | re_kernel、hosts_redirect、critical_partition_protect、selinux_policydb_fix、memagent 等 8 个 |

每个条目带 `owner/repo`、`assetSuffix`（`.zip` / `.kpm`）、`assetKeyword`、`desc`。
**想增删/改模块，编辑这个 JSON 即可**——App 内点「刷新清单」就能热更新，不用重发 APK。

---

## 五、自己编译 APK

```bash
./gradlew :app:assembleDebug     # debug，可直接安装
./gradlew :app:assembleRelease   # release，需要你自己的签名配置
```

产物在 `app/build/outputs/apk/`。

> release 开 R8 后 **18.3MB → 3.5MB**（dex 15 个合并成 1 个）。
> 若要正式发布：① 把 `ModuleRepoActivity` 里的 `CATALOG_URL` 改成你自己的仓库地址；② 用自己的 keystore 签名。
> R8 规则里已经为 KPM 相关类加了 `-keep`（`com.violet.box.kpm.**`、`KpmManagerActivity`），因为 ELF 解析依赖段名常量、shell 脚本以字符串形式保存，被合并后无法排查。

---

## 六、几个必须知道的限制

1. **GitHub API 未认证限速 60 次/小时/IP**。频繁刷版本列表会撞 403，代码里已做友好提示。长期用建议申请 Personal Access Token 塞进请求头。
2. **KPM 热加载（Load）必须在 APatch 里做**。它走 KernelPatch supercall（syscall 45），第一个参数是 superkey，只有 APatch 持有；`apd` 命令行也没有 kpm 子命令。V5 只做**文件级刷入（Install）**，刷完需重启。
3. **Embed 只在「boot 分区已经被 KernelPatch 打过补丁」时可用**。V8 会用和 kptools 自己的 `find_patched_preset()` 完全相同的两条判据（magic 偏移必须等于 `align_ceil(kimg_size, 4096)`、`header_backup` 必须像一条 arm64 分支指令）来确认，确认不了就拒绝，不会拿你的 boot 分区做实验。首次安装 KernelPatch 请仍用 APatch / FolkPatch。
4. **刷 KPM 不会格机**。最坏情况是开机卡住，进 Recovery 删掉 `/data/adb/ap/kpm/<id>/` 即恢复。
5. **编译时比运行时更有把握，但依然没跑过真机**。两个 variant 都编译通过（含 R8 全链路），并做了静态校验（dex 里有新类、资源里有新布局、`assets/module_repo.json` 178 条、v2 签名存在）。但沙箱里没有手机也没有模拟器，下载流程 / RecyclerView 渲染 / root shell 执行这些没能亲眼跑一遍。首次运行请看 logcat。

---

## 七、文件清单

```
violet_box_模块下载补丁/
├── README_使用说明.md
├── apply_patch.py                       # 一键应用脚本（幂等，自动备份）
├── apply_patch_kpm.py                   # V5 增量（若已提供）
├── AndroidManifest.xml                  # 已注册 ModuleRepoActivity / KpmManagerActivity
├── MainActivity.java                    # 两个入口的跳转
├── assets/module_repo.json              # 178 个模块的清单
├── layout/
│   ├── activity_module_repo.xml         # 下载中心主页（含搜索框）
│   ├── item_module_repo.xml             # 列表项
│   ├── activity_kpm_manager.xml         # KPM 刷写页
│   ├── item_kpm.xml / item_kpm_header.xml
│   ├── activity_module_backup.xml       # 备份页（ZIP / KPM 切换）
│   ├── fragment_explore_placeholder.xml # 实用功能入口
│   ├── fragment_detect.xml              # 环境检测（b42df36 原版 UI）
│   └── fragment_settings_placeholder.xml
├── drawable/ic_kpm.xml / ic_refresh.xml / bg_badge_pill.xml / bg_btn_accent_pill.xml
├── menu/menu_kpm.xml / menu_repo.xml          # Toolbar 刷新菜单
├── java/
│   ├── ui/repo/                         # 下载中心：Entry / Client / Activity / Adapter
│   ├── ui/module/                       # KpmManagerActivity / ModuleBackupActivity
│   ├── ui/detect/                       # DetectFragment / DetectViewBinder
│   ├── kpm/                             # KpmInfo（ELF 解析）/ KpmShell（root shell）
│   │                                    # / KpmEmbedded（kpe 头链扫描）/ KpmPreset（kpimg 定位）
│   │                                    # / KpmEmbedTool（嵌入写入管线）
│   ├── data/detector/                   # RootDetector / HardcodedSignals / AdvancedRuntimeDetector
│   └── scottyab/rootbeer/               # 内置版 RootBeer（Maven 源 404，已 vendored）
└── safety/SafetyPageController.java     # 安全页：默认环境检测 / 摇一摇防护开关
```
