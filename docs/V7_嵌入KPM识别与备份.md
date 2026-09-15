# V7：识别并备份「已嵌入」的 KPM（Embed 路线支持）

产物：`apk/VioletBox-V7-release.apk` / `apk/VioletBox-V7-debug.apk`

---

## 一、你问的「我已经嵌入模块了，为什么显示我没有」

你的 APatch 截图里，Nohello 和 selinux_magisk_access_filter 的徽章是**「已嵌入」**——这是 KPM 三条路径里我之前没覆盖的那条：

| 路径 | KPM 在哪 | 之前 V6 能看到吗 |
|---|---|---|
| **Install** | `/data/adb/ap/kpm/<id>/<id>.kpm`（磁盘上的独立文件） | ✅ 能 |
| **Embed（你用的）** | **合进了 boot/init_boot 分区镜像里**，磁盘上没有独立文件 | ❌ 不能 |
| Load | 只在内存里，重启即失 | ❌（需要 superkey，做不了） |

V6 只扫了文件系统，而嵌入的 KPM **不是文件**——它是被 `kptools` 打进内核镜像后面的一段 ELF。所以你「已经嵌入模块」但我的页面显示没有：**不是 ROOT 问题，是扫描位置根本不含 boot 分区**。

## 二、怎么做到的（源码级分析）

我下载了 KernelPatch 的 kptools 源码（`tools/patch.c`、`kernel/include/preset.h`），确认了嵌入格式。嵌入的每个附加项是一条 **128 字节的头 + 参数 + 载荷** 的链：

```
struct patch_extra_item_t {      // 小端（arm64 Android）
    char     magic[4];   // +0   "kpe\0"
    int32_t  priority;   // +4
    int32_t  args_size;  // +8
    int32_t  con_size;   // +12  载荷长度（KPM 就是整个 .kpm ELF）
    int32_t  type;       // +16  0=none 1=kpm 2=shell 3=exec 4=raw 5=android_rc 6=旧kconfig
    char     name[32];   // +20  （kptools -E 起的嵌入名，可为空）
    char     event[32];  // +52  （如 pre-kernel-init）
    int32_t  flags;      // +84
};
// 头(128) + args_size + con_size，然后是下一项
```

关键事实：**KPM 载荷就是原始的 `.kpm` 可重定位 ELF**（`ET_REL` 带 `.kpm.info` 段）——所以可以逐字节抠出来：既能解析元信息，也能原样导出/备份。

### 实现方式

```
su -c cat /dev/block/by-name/boot(_a|_b) / init_boot(...)
    ↓ 流式扫描（1MB 窗口，找 "kpe\0" 魔数）
    ↓ 解析 128 字节头（type=1 且载荷以 \x7fELF 开头才认）
    ↓ 把 con_size 字节载荷抠到应用缓存目录 embed_<n>_<name>.kpm
    ↓ 用现有的 KpmInfo 解析 .kpm.info（name/version/author/description）
```

设计要点：

- **不落盘整个 boot 镜像**：直接 `su -c cat` 分区流式进 Java，1MB 窗口滑动扫描，内存占用恒定（boot 分区 64–256MB 也没问题）。
- **不依赖 SELinux 豁免**：分区由 root 的 `cat` 读出，App 只看管道，`/data/local/tmp` 权限问题不存在。
- **分区候选**：`boot<slot>` → `init_boot<slot>` → `boot` → `boot_a/b` → `init_boot*`（Android 13+ GKI 的内核在 init_boot 里），先扫到先停，单分区超过 512MB 直接跳过。
- **防误报**：type 必须 1–6、args/con 尺寸有上限、KPM 载荷必须 `\x7fELF` 开头，否则当噪音跳过；抠完还会过一遍现有 ELF 解析器校验。
- **已做单元测试**：手工合成假 boot 镜像（2 个嵌入 KPM + 1 个 shell 附加项 + 伪造 "kpe" 垃圾），普通流和 1 字节分块流都验证：抠出结果与源逐字节一致，元信息解析正确（测试里就用了 `Nohello v1.8.3.7 by mhmrdd` 和 `selinux_magisk_access_filter v1.1.6.1 by Admire`——正是你截图里那两个模块的名字）。

## 三、界面上多了什么

**KPM刷写页**：

- 环境卡新增一行「嵌入检测」：扫描到会显示 `在 /dev/block/by-name/boot_a 中发现 N 个已嵌入的 KPM`，没有则显示未发现
- 列表新增 **「已嵌入 · boot 镜像（N）」** 区块：徽章 KPM、状态 **已嵌入**（绿色），路径行显示 `嵌入于 <分区>`
- 嵌入的 KPM 不能用文件开关启停/卸载（它随内核启动，那是 boot 镜像层面的事，需要重新 embed/unpatch），所以只有 **「导出」** 按钮：把抠出的 `.kpm` 复制到 `Android/data/com.violet.box/files/Download/VioletBox/<id>.kpm`

**模块备份页（内核模块）**：

- 扫描结果现在包含 **已刷入 + 社区目录 + 已嵌入** 三类，嵌入的条目名字带「（已嵌入）」后缀，可以勾选备份——打出来的 zip 里就是从镜像里抠出的原始 `.kpm`，恢复时解压即可重新刷入/嵌入
- 空状态文案改为：「还没有可备份的内核模块……已刷入的看 /data/adb/ap/kpm/，已嵌入的看 boot 镜像」

## 四、改动文件

```
新增 java/kpm/KpmEmbedded.java               # kpe 头链流式扫描 + KPM 载荷抠取
改动 java/kpm/KpmShell.java                  # scanEmbeddedBoot()：分区定位 + su cat + 结果汇总
改动 java/ui/module/KpmManagerActivity.java  # 「已嵌入 · boot 镜像」区块 + 导出按钮 + 环境卡嵌入检测行
改动 java/ui/module/ModuleBackupActivity.java # 备份列表纳入已嵌入 KPM
```

## 五、边界与说明

1. **扫描耗时**：每次进入页面/刷新都会重扫 boot 分区（64–256MB 的流式读取，通常 1–3 秒），进度圈会转一下，属正常。
2. **嵌入的 KPM 无法在 App 内启停/卸载**：它的生命周期在内核镜像里，删除要走 APatch 的 unpatch/重新 embed 流程；App 能做的是「导出留底」。
3. **热加载依然做不了**：superkey 约束没变（见 V5 文档）。
4. 如果你的设备把 patched 内核放在别的分区（极少数非标准布局），扫不到的话把分区路径告诉我，我加进候选列表。

## 六、校验

`assembleDebug` / `assembleRelease` 均 **BUILD SUCCESSFUL**；dex 校验 `KpmEmbedded` / `scanEmbeddedBoot` / 新文案全部在包内；`assets/module_repo.json` 仍为 178 条。合成镜像单测通过（含逐字节比对与分块流边界用例）。
