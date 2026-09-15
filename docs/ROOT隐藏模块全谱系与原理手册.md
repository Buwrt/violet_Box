# Android ROOT 隐藏 / 完整性检测：模块全谱系与原理手册

> 整理日期：2026-09-14 | 所有项目状态经 GitHub API 与仓库页交叉核实
> 状态标记说明：**CURRENT**=活跃维护 | **STALE**=停更但未归档 | **ARCHIVED**=已归档 | **UNVERIFIED**=来源存疑

---

## 〇、前置说明：我无法替你检测设备

没有任何远程手段能读到你的手机状态。**要解决问题第一步是自己测**，按顺序做：

| 步骤 | 工具 | 看什么 |
|---|---|---|
| 1 | Play Integrity API Checker（Play 商店） | 三档哪些绿：BASIC / DEVICE / STRONG |
| 2 | Native Detector | 是否有 ROOT 二进制、mount 痕迹、Abnormal Boot State |
| 3 | Key Attestation Demo | TEE 证书链是否正常、是否被吊销 |
| 4 | MMRL / 你的 root 管理器 | 现有模块列表，有没有互斥项共存 |

测完拿着结果对照下面第五节表格，就知道该刷什么。

---

## 一、检测项体系：你可能挂在哪些地方

| 检测项 | Google/App 在查验什么 | 失败典型表现 |
|---|---|---|
| **BASIC** | 真实 Android 设备 + Play Protect | Google 钱包无法添加卡片 |
| **DEVICE** | 合法设备指纹 + 密钥证明 | 银行 App 闪退、Netflix 降清晰度 |
| **STRONG** | TEE 硬件级密钥证明（key attestation） | 政务/支付类严格 App 拒绝运行 |
| **解锁 Bootloader** | verified boot 状态 | 自 2025-05 起 Android 13+ **解锁 BL 按硬件设计必挂 DEVICE**，除非有密钥证明层骗过去 |
| **ROOT 痕迹** | `su` 二进制、Magisk 包名、`/data/adb`、`/proc/self/maps`、mount 计数 | App 内自检触发，直接拒绝服务 |
| **应用列表** | 是否装着 Magisk / LSPosed / 抓包工具 | 「检测到风险环境」 |
| **内核指纹** | `uname -r` 里带 "KSU"、bootconfig、vbmeta digest | 环境类 App 判定非原生 |
| **TEE 证明** | Keystore/KeyMint 返回的证书链是否被吊销 | STRONG 档失效最常见的根因 |

---

## 二、ZIP 类模块全清单（Magisk / KSU / APatch-APM）

### 2.1 Zygisk 注入层（必须且只能有一个）

Zygisk 是所有隐藏类模块的地基，其本质是在 Zygote fork 出应用进程时注入代码。**绝对不能装两个 provider**。

| 模块 | 仓库 | 作者 | 状态 | 原理 |
|---|---|---|---|---|
| **Zygisk Next** | [Dr-TSNG/ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) | Dr-TSNG | CURRENT（闭源）v1.4.2 | 独立于 Magisk 的 Zygisk 实现，为 KSU/APatch 提供注入能力 |
| **ReZygisk** | [PerformanC/ReZygisk](https://github.com/PerformanC/ReZygisk) | PerformanC | CURRENT | 重写版 Zygisk，自定义 linker + PLTI hook，绕开基于 linker 特征的检测 |
| **NeoZygisk** | [JingMatrix/NeoZygisk](https://github.com/JingMatrix/NeoZygisk) | JingMatrix | CURRENT | ptrace 注入 Zygote，加载后做痕迹清理 |
| **NyaZygisk** | — | — | 见于 XDA 推荐 | SukiSU 圈常用组合成员 |
| **OnyxZygisk** | [OnyxZygisk/OnyxZygisk](https://github.com/OnyxZygisk/OnyxZygisk) | — | UNVERIFIED | 2026-08 新建，93 star，社区尚无充分验证 |
| Magisk 内置 Zygisk | — | — | 仅 Magisk | 用 Restart 内置的，**装上述任一个前必须关掉** |

### 2.2 Play Integrity 属性/指纹层（管 BASIC + DEVICE）

| 模块 | 仓库 | 状态 | 原理 |
|---|---|---|---|
| **PlayIntegrityFork** | [osm0sis/PlayIntegrityFork](https://github.com/osm0sis/PlayIntegrityFork) | CURRENT | 修改 `android.os.Build` 各字段 + native 层 hook 属性读取，让 **GMS 里的 DroidGuard 进程**读到一套 Pixel 指纹。核心是 `pif.json`，**会随 Google 侧缓存过期而失效，需定期更新** |
| **PlayIntegrityFix (inject)** | [KOWX712/PlayIntegrityFix](https://github.com/KOWX712/PlayIntegrityFix) | CURRENT，default 分支 `inject_s` | 直接把 dex **注入 vending/GMS 进程**修改银行卡所需的 KeyInfo 等字段 |

> ⚠️ 原 `chiteroman/PlayIntegrityFix` 仓库**已从 GitHub 移除**，只剩 KOWX712 的镜像。别去搜旧链接下载。
> ⚠️ `pixel_beta` 指纹方案已失效，`spoofVendingSdk` 已被 Google 修补。

### 2.3 密钥证明层（管 STRONG —— 最关键也最脆弱）

这层的所有模块**争夺同一个位置**：Keystore/KeyMint。它们互斥，**必须三选一**。

| 模块 | 仓库 | 状态 | 原理 |
|---|---|---|---|
| **TrickyStore** | [5ec1cff/TrickyStore](https://github.com/5ec1cff/TrickyStore) | **STALE**（推送停在 2025-11-30，末版 v1.4.1） | hook keystore2/KeyMint 的 Binder 事务替换 attestation 证书链。`target.txt` 里 `?`=leaf hacking（沿用真实 TEE key 改写证书）；`!`=证书生成（TEE 坏也能用） |
| **TrickyStoreOSS** | [beakthoven/TrickyStoreOSS](https://github.com/beakthoven/TrickyStoreOSS) | **CURRENT** v3.1.0（2026-08-25） | GPLv3 重写版，Kotlin + PLTI hook，按设备自动选模式。**安装器会主动删掉冲突的 TEESimulator** |
| **TEESimulator** | [JingMatrix/TEESimulator](https://github.com/JingMatrix/TEESimulator) | CURRENT | 在 keystore2 进程内 PLT hook `libc.ioctl`，拦截 Binder IPC 的 generateKey/importKey |
| **TEESimulator-RS** | [Enginex0/TEESimulator-RS](https://github.com/Enginex0/TEESimulator-RS) | CURRENT v6.0.1-307 | Rust 版，`libcertgen.so`（ring + 手写 DER）直接生成完整 X.509 链并对齐 AOSP AuthorizationList 字段顺序，带 key 持久化与 per-UID 限流 |
| **OhMyKeymint** | [qwq233/OhMyKeymint](https://github.com/qwq233/OhMyKeymint) | CURRENT | 2026 年新兴替代，同为 KeyMint 拦截层 |

**这层的死穴**：它们都需要一份**未被 Google 吊销的 keybox.xml**。泄露的 OEM keybox 会被成批吊销，所以 STRONG 会突然失效——这是常态，不是你配置错了。**任何向你售卖 keybox 的人基本都是诈骗**，你无法从自己的设备"提取"出有效 keybox。

### 2.4 内核 / 挂载痕迹隐藏层

| 模块 | 仓库 | 状态 | 原理 |
|---|---|---|---|
| **SUSFS** (内核补丁) | [gitlab.com/simonpunk/susfs4ksu](https://gitlab.com/simonpunk/susfs4ksu) | CURRENT | **这是内核源码级补丁**，在 VFS 层做 `sus_path` / `sus_mount` / `sus_kstat` / `try_umount` / `open_redirect` / bootconfig spoof——从内核这一层就让你指定的路径、挂载、stat 数据「不存在」。这是用户态模块**结构性地做不到**的 |
| **susfs4ksu-module** | [sidex15/susfs4ksu-module](https://github.com/sidex15/susfs4ksu-module) | CURRENT R28（2026-07-26） | 上述内核补丁配套的**用户态模块 + `ksu_susfs` 二进制**。没它，依赖 susfs 的一切都报 `susfs not found` |
| **Mountify** | [backslashxx/mountify](https://github.com/backslashxx/mountify) | CURRENT v204 | 弃用 magic mount，改用 **OverlayFS**：把模块内容复制到 `/mnt/vendor/<fake>`，逐文件镜像 SELinux context，伪装成 OEM/APEX 挂载，**压低 mount 计数**以对抗计数类检测 |
| **Shamiko** | LSPosed 发布页 | **STALE**（末版 v1.2.5, 2025-06） | 读 Magisk DenyList（须关闭 Enforce），在 fork/specialize 阶段 umount 模块并清理 maps/cmdline |
| **Treat Wheel** | [PerformanC/Treat-Wheel-Zygisk](https://github.com/PerformanC/Treat-Wheel-Zygisk) | CURRENT | 用户态卸载规则，**ReZygisk 场景下替代 Shamiko** |
| **NoHello** | [MhmRdd/NoHello](https://github.com/MhmRdd/NoHello) | **STALE** | "Mount Rule System"，把卸载点从 hook `unshare` 移到 `preAppSpecialize`。无 susfs 内核时的替代 |
| **Zygisk-Assistant** | [snake-4/Zygisk-Assistant](https://github.com/snake-4/Zygisk-Assistant) | STALE | XDA 已普遍视其 outdated |

### 2.5 应用列表隐藏

| 模块 | 仓库 | 状态 | 原理 |
|---|---|---|---|
| **HMA-OSS** | [frknkrc44/HMA-OSS](https://github.com/frknkrc44/HMA-OSS) | CURRENT | hook PMS / IPackageManager 的查询接口，**按模板过滤包名**返回给目标 App；还能隐藏 USB 调试状态、无障碍开关查询 |
| **Hide My Applist** | [Dr-TSNG/Hide-My-Applist](https://github.com/Dr-TSNG/Hide-My-Applist) | CURRENT（v3.4 起闭源，新版本走 Telegram） | 同上，闭源原版 |

### 2.6 聚合工具箱（新手向）

| 模块 | 仓库 | 状态 | 说明 |
|---|---|---|---|
| **Integrity Box** | [MeowDump/Integrity-Box](https://github.com/MeowDump/Integrity-Box) | CURRENT v42（2026-09-05） | v28 起内嵌 PIFork（**模块 ID 同为 playintegrityfix**），自动管 target.txt、多 keybox、Lineage props、安全补丁伪装 |
| **Specter** | [dpejoh/specter](https://github.com/dpejoh/specter) | CURRENT v1.5.0（2026-09-12） | **= TEESimulator-RS + PIFork 打包**，一键拉 keybox、查吊销状态、更新 pif.json |
| **AlwaysStrong** | [evoker0/AlwaysStrong](https://github.com/evoker0/AlwaysStrong) | CURRENT | 同为 TEESimulator-RS + PIFork 打包变体 |
| **Tricky Addon** | [KOWX712/Tricky-Addon-Update-Target-List](https://github.com/KOWX712/Tricky-Addon-Update-Target-List) | CURRENT v4.4 | TrickyStore 的 WebUI 伴侣，加载自定义 keybox、勾选哪些 App 吃伪装。Requirements 明确写着 keybox 类 **三选一** |
| **YuriKey** | [Yurii0307/yurikey](https://github.com/Yurii0307/yurikey) | CURRENT | keybox 管理（原 dpejoh/yurikey，owner 改名）；XDA 认为已被 Specter 取代 |
| **Sensitive Props** | [Pixel-Props/sensitive-props](https://github.com/Pixel-Props/sensitive-props) | CURRENT | 补 `ro.*` 敏感属性 |

---

## 三、KPM 内核模块全清单

### 3.1 先纠正三个普遍误解

1. **KPM 不是 APatch/SukiSU 独占。** KernelSU-Next 通过 **KPatch-Next / KPatch-Next-Module**（484★）已把 KPM 加载能力带到 Magisk / KernelSU / KSUN——**唯独不支持 APatch**。要求 `CONFIG_KALLSYMS=y`。
2. **SUSFS 没有 KPM 形态。** 它是内核源码补丁 + 用户态 zip，两者都不是 `.kpm`。网上流传的「SUSFS KPM 版」**查无此项**。同理 **ZygiskNext / ReZygisk / NeoZygisk 全部只有 zip 形态**——Zygisk 注入发生在用户态 Zygote，做成 KPM 毫无收益。
3. **ReSukiSU 新版已移除 KPM**，需换 SukiSU-Ultra 或用旧版。

### 3.2 真实清单

先说实话：**KPM 生态远比传言稀疏**，全球可用的通用模块只有个位数。

**A. KernelPatch 官方示例**（[bmax121/KernelPatch](https://github.com/bmax121/KernelPatch/releases)，KP 0.13.8）
`demo-hello.kpm` / `demo-inlinehook.kpm` / `demo-syscallhook.kpm` —— 教学用途，非日用。

**B. 第三方合集**（[lzghzr/APatch_kpm](https://github.com/lzghzr/APatch_kpm)，末次提交 2026-06-06）

| KPM | 作用 | 原理 |
|---|---|---|
| `re_kernel` | 内核伪装 / 网络包过滤 | hook `binder_transaction`、`do_send_sig_info` |
| `hosts_redirect` | hosts 重定向 | hook `do_filp_open`，把 `/system/etc/hosts` 指到 `/data/adb/hosts` |
| `critical_partition_protect` | 分区写保护 | hook `do_filp_open` 拦截对 sd*/loop*/dm-* 的写入 |
| `selinux_policydb_fix` | SELinux 修复 | hook `policydb_write` |
| `cgroupv2_freeze` / `dont_kill_freeze` | 冻结策略 | 相邻机制调整 |
| `qti_battery_charger`、`xperia_ii_battery_age` | 充电/电池 | 机型特定 |
| `lmkd_dont_kill` | LMK | **已归档（2025-06-09）** |

**C. 独立 KPM**
- `memagent-kpm`（[qinghemuyu](https://github.com/qinghemuyu/memagent-kpm)，2026-08-03）：内核物理内存取证读取
- `privacykit_kpm.kpm`：伪造文件时间戳、`/proc/cpuinfo`、CPU 频率、**作者自述"experimental，未在真机验证"**
- 开发模板：`udochina/KPM-Build-Anywhere`、`cxapython/mkpms`

**D. 明确不可信 / 勿装**
- [Zhanfg/PatchNest-Kpms](https://github.com/Zhanfg/PatchNest-Kpms)：曾声称 6 个 anti-detect KPM，**2026-08-06 AUDIT 后公开目录已清空**，承认此前是占位链接
- `breaksand/apatch_kpm_read`：仅源码，需加 TG 群验证 → 不建议

### 3.3 各方案 KPM 支持现状

| 方案 | KPM 支持 | 备注 |
|---|---|---|
| **APatch** | 原生 ✅ | Embed（刷入 boot.img 持久）/ Load（临时，重启丢失）。**"Install" 至今未实现** |
| **SukiSU-Ultra** | 内置 ✅ | 需 `CONFIG_KPM=y`，模块目录 `/data/adb/kpm/`。注意新版已移除内核侧 susfs |
| **KernelSU-Next** | ✅ 通过 KPatch-Next | **不支持 APatch**，需 `CONFIG_KALLSYMS=y` |
| **ReSukiSU** | ❌ 新版已移除 | |
| **Magisk** | ✅ 通过 KPatch-Next | 原生不支持 |

---

## 四、KPM vs ZIP：什么时候必须上 KPM

判断标准只有一条：**检测结果的值是由内核产生的吗？**

| 场景 | 用哪种 | 原因 |
|---|---|---|
| 文件/属性替换、hosts、脚本、sepolicy、Zygisk 模块、Play Integrity | **ZIP** | 用户态可完成，可随时卸载，不易变砖 |
| hook 内核函数（`do_filp_open`、`policydb_write`、`binder_transaction`） | **KPM** | 用户态拿不到内核符号执行权 |
| 分区/块设备写保护（防误刷 boot、基带） | **KPM** | 必须在 VFS 层拦截，APatch 官方就用这个当例子 |
| syscall table hook、隐藏 `/proc/self/maps` 内核痕迹、反 ptrace | **KPM** | 脏数据在内核返回结果的那一刻就产生了 |
| 内核 uname、bootloader 状态伪装 | KPM 或**内核补丁** | 社区主流解法是 SUSFS 这种补丁，而非 KPM |

**结论**：绝大多数人的「过检测」需求，一个 KPM 都不需要。KPM 的真实用途是少数特定 hook 场景。

---

## 五、按症状查表：你该刷什么

| 你的症状 | 优先补什么 | 备注 |
|---|---|---|
| 只有 BASIC 绿 | PIF / PIFork / PIF-inject 的 pif.json **过期了** | 更新指纹 |
| DEVICE 红 | PIF + 一个 keybox 模块（TS/TSOSS/TEESim 三选一） | keybox 是否被吊销要查 |
| STRONG 红 | keybox 被吊销 | 换未吊销的，且这条路注定要反复维护 |
| App 报「检测到 ROOT」 | 上 SUSFS（内核级）> Mountify > TreatWheel | 有 susfs 内核就用 susfs |
| App 扫到 Magisk/LSPosed | **HMA-OSS** | |
| `uname -r` 带 KSU | SUSFS 的 spoof uname | 紫罗兰盒子的内核伪装页就在改这里的 config |
| 挂载数异常 | Mountify（压 mount 计数） | |
| 解锁 BL 导致 DEVICE 挂 | 只能靠 keybox 层伪造 attestation 的 deviceLocked | 无完美解 |

---

## 六、互斥关系（务必核对，装错等于白干）

- ❌ **Shamiko × ReZygisk** → 改用 Treat Wheel
- ❌ **TrickyStore/TSOSS × TEESimulator(-RS) × OhMyKeymint** → 同为 keystore 拦截层，**三选一**，TSOSS 装时会自动删 TEESim
- ❌ **Integrity Box ⊃ PIFork** → 模块 ID 同为 `playintegrityfix`，不可共存
- ❌ **AlwaysStrong ⊃ TEESimulator-RS + PIFork** → 不可再单装这两者
- ⚠️ **Integrity Box × SUSFS** → 2025 年作者明确不推荐共存（v6 在 post-mount 写 sus_paths 破坏隐藏）；2026 年未见官方更新说明，UNVERIFIED，需实测
- ❌ **Mountify × De-Bloater**（非标准 whiteout）
- ⚠️ **只能有一个 Zygisk provider**；Magisk 需先关内置 Zygisk
- ⚠️ **SUSFS 内核补丁版本必须与用户态模块同代**（如 v1.5.2+ ↔ kernel ≥ v1.5.2）

**推荐安装顺序**（每装一个重启一次，便于定位谁把你搞崩了）：
```
内核 SUSFS 补丁 → susfs4ksu 模块 → 单一 Zygisk Provider → PIF
→ TrickyStore 类 → VBMeta Fixer → 放 keybox.xml
→ 隐藏类(TreatWheel/HMA-OSS) → 最后才装工具箱(Specter/IntegrityBox)
```

---

## 七、紫罗兰盒子在这条链路里的位置

重申一遍：**它不帮你刷模块**（除了自动装它自带的 `violet_box_module` 核心扩展）。它是**后置配置器**：

| 紫罗兰盒子的页面 | 前置依赖（你得先刷好） | 它改的文件 |
|---|---|---|
| 内核伪装 | susfs 内核 + susfs4ksu | `/data/adb/violet_kernel_spoof/config.sh` |
| 全局机型伪装 | 有 resetprop 的任意 root 方案 | resetprop 写 `ro.product.*` / `ro.build.*` |
| TrickyStore 配置页 | 已刷 TrickyStore 系 | `target.txt`、安全补丁、hash |
| 隐藏应用列表 | 已刷 HMA 类 | 写对应 json 配置 |
| SUSFS 配置 | susfs 内核 + 模块 | 相关 config |

**模块没刷对，这些页面填得再漂亮也没用。**

---

## 八、风险提示（认真读）

1. **变砖**：写错 boot.img / magisk eraser 都会让你进不了系统。**刷之前备份 boot.img 并确认自己会 fastboot 救砖**。
2. **诈骗**：付费卖 keybox、卖"永久 STRONG"的，全是骗局。Google 会成批吊销。
3. **KPM 尤其危险**：它跑在内核态，一个 bug 就是 kernel panic 或数据损坏，且**没有应用商店式审核**。只装你能找到官方源、能看懂源码的。
4. **合规**：这些能力的典型用途是绕过银行/支付/游戏/政务 App 的设备风控。在法律与平台条款层面属灰色地带，部分场景（规避支付风控）可能触碰更硬的红线，风险自负。
5. **这是一场持续军备竞赛**：Google 每月更新检测，今天是绿的下个月可能全红，不存在"一劳永逸"。

---

*数据来源：GitHub REST API 与各项目仓库页、[KOWX712/PlayIntegrityFix](https://github.com/KOWX712/PlayIntegrityFix)、[osm0sis/PlayIntegrityFork](https://github.com/osm0sis/PlayIntegrityFork)、[Enginex0/TEESimulator-RS](https://github.com/Enginex0/TEESimulator-RS)、[beakthoven/TrickyStoreOSS](https://github.com/beakthoven/TrickyStoreOSS)、[MeowDump/Integrity-Box](https://github.com/MeowDump/Integrity-Box)、[dpejoh/specter](https://github.com/dpejoh/specter)、[sidex15/susfs4ksu-module](https://github.com/sidex15/susfs4ksu-module)、[backslashxx/mountify](https://github.com/backslashxx/mountify)、[lzghzr/APatch_kpm](https://github.com/lzghzr/APatch_kpm)、[apatch.dev KPM 文档](https://apatch.dev/kpm-usage-guide.html)、XDA 2026-06 实测帖。*
