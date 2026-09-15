# V5：KPM 刷写机制分析与实现说明

> 你要我先讲清楚 KPM 的刷写机制，再动手。这一份就是分析结论 + 我据此做了什么。
> 结论里有**一个必须直说的坏消息**：运行时热加载第三方 App 做不到，原因在下面第三节。

产物：`apk/VioletBox-V5-release.apk` / `apk/VioletBox-V5-debug.apk`

---

## 一、KPM 到底是什么

KPM = **KernelPatch Module**。它不是 Magisk 那种「替换文件」的模块，而是一个**内核态的可重定位 ELF**（`.kpm`），被 KernelPatch 直接加载进内核地址空间执行。

| | Magisk / KSU 模块（APM） | KPM |
|---|---|---|
| 执行位置 | 用户态，靠 OverlayFS / bind mount 覆盖文件 | **内核态**，直接 hook 内核函数 |
| 文件形态 | `.zip` + `module.prop` + 脚本 | `.kpm`（ELF） |
| 生效时机 | 开机后由守护进程挂载 | 内核加载时执行 `mod_initcall` |
| 能力 | 改文件、跑脚本 | **inline hook、syscall table hook**、改内核行为 |
| 宿主 | Magisk / KernelSU / APatch | 只有 **KernelPatch / APatch** |

所以 KPM 能做 APM 做不到的事——比如保护分区镜像不被写入、改 SELinux 决策、hook 系统调用。代价是它跟内核版本强绑定，**换内核/换机型就得重新适配**。

KPM 的元信息不在 zip 里，而在 ELF 的一个专用段 **`.kpm.info`**。KernelPatch 的 `KPM_NAME()` 等宏会往这个段里塞 `name=value\0` 形式的字符串：

```c
// kernel/include/kpmodule.h
static const char __kpm_info_name[] __attribute__((section(".kpm.info"), aligned(1)))
    = "name=" "hello";
```

段内容就是 `"name=hello\0version=1.0\0license=GPL\0author=xxx\0description=xxx\0"`。
字段长度上限：name/version/license/author 各 32 字节，description 512 字节。

**这一点很关键**：既然是标准 ELF + 纯文本段，就能**不依赖任何原生代码**解析出模块名、版本、作者、说明。V5 就是这么做的。

---

## 二、官方的三种使用方式（Embed / Load / Install）

APatch 官方文档和源码里，KPM 有且只有三条路径：

| 方式 | 做法 | 生效 | 重启后 |
|---|---|---|---|
| **Embed（嵌入）** | 用 `kptools` 把 .kpm 和 patched kernel 一起合进 `boot.img`，再刷入 boot 分区 | 在 `pre-kernel-init` 阶段加载 | **永久存活** |
| **Load（加载）** | 运行时通过 supercall 把 .kpm 塞进正在跑的内核 | **立即生效** | **丢失** |
| **Install（安装）** | 放到 `/data/adb/ap/kpm/<id>/<id>.kpm`，由开机加载器统一加载 | 需重启 | **永久存活** |

官方文档里那句「APatch hasn't implemented Install」**已经过时了**。我从 APatch 当前源码里读到的真实布局是：

```
/data/adb/ap/                          APATCH_FOLDER
/data/adb/ap/bin/kptools               元信息导出工具（kptools -l -M x.kpm）
/data/adb/ap/kpm/<id>/<id>.kpm         一个已「安装」的 KPM
/data/adb/ap/kpm/<id>/disable          这个文件存在 = 该模块被禁用
```

`KPModuleViewModel.kt` 里明确写着：

```kotlin
// The installed flag is set only when the directory scan below finds
// /data/adb/ap/kpm/<id>/<id>.kpm.
```

而 `installKpm()` 的全部动作就是：

```kotlin
val id = safeKpmModuleId(name)                       // 名字清洗成合法目录名
val dir = "${APApplication.KPMS_DIR}$id"             // /data/adb/ap/kpm/<id>
rootShellForResult("mkdir -p '$dir' && cp -f '${temp.path}' '$destination'")
// Installed KPMs are loaded by the boot-time loader. Do not load them in
// the current session; installation takes effect after reboot.
```

**也就是说：「刷写 KPM」在今天的 APatch 上，本质就是放文件。** 这是纯文件操作，root shell 就能做，第三方 App 完全可以复刻。

`safeKpmModuleId` 的规则（我也原样实现了）：
```
只保留 [A-Za-z0-9._-]，其余替换成 _ ；首尾的 . _ - 去掉；最长 64；空则取 "kpm"
```

启用/禁用也只是一句话：
```
启用：rm    -f /data/adb/ap/kpm/<id>/disable
禁用：touch    /data/adb/ap/kpm/<id>/disable
```

---

## 三、必须说清楚：运行时热加载为什么我没做

Load 走的是 KernelPatch 的 **supercall**——一个被劫持的 syscall：

```c
#define __NR_supercall 45          // 就是 __NR3264_truncate
#define SUPERCALL_KPM_LOAD   0x1020
#define SUPERCALL_KPM_UNLOAD 0x1021
#define SUPERCALL_KPM_CONTROL 0x1022
#define SUPERCALL_KPM_NUMS  0x1030
#define SUPERCALL_KPM_LIST  0x1031
#define SUPERCALL_KPM_INFO  0x1032

long ver_and_cmd(const char *key, long cmd) {
    uint32_t version_code = (MAJOR << 16) + (MINOR << 8) + PATCH;
    return ((long)version_code << 32) | (0x1158 << 16) | (cmd & 0xFFFF);
}

syscall(45, key, ver_and_cmd(key, SUPERCALL_KPM_LOAD), path, args, reserved);
```

**第一个参数是 superkey（超级密钥）**。它是用户在 APatch 里自己设的、只存在 APatch 手里（或用户脑子里），第三方 App 拿不到。

我还查了两条可能的旁路，都不通：

- **`apd` 命令行**：翻遍 `apd/src/cli.rs`，只有 `module install/uninstall/enable/disable/list/config`、`insmod`（.ko）、`late_load`（.ko 越狱用）、`resetprop`、`sepolicy`。**没有任何 kpm 子命令**。网上流传的 `apd kpm control` 是老版本/魔改版。
- **`/system/bin/truncate`**：APatch 里 `SUPERCMD = "/system/bin/truncate"` 确实是 supercall 的入口，但它只被当成**提权 shell**（等价于 `su`）使用，不接受 KPM 参数。

结论：**热加载需要 superkey，只有 APatch 自己能做。** 我不打算伪造一个点了没反应、或者拿错误 superkey 反复试的按钮。V5 的做法是：把能做的做扎实，热加载那一步**明确告诉用户去 APatch 里做，并给一键跳转**。

顺带说清一件常见误解：**刷 KPM 不会格机**。KPM 是要重启才加载的，万一某个 KPM 让你的机子起不来，进 Recovery 删掉 `/data/adb/ap/kpm/<id>/` 就恢复了。真正会变砖的是刷错 boot/dtbo 分区镜像（也就是 Embed 路线），所以我**没有**做自动 Embed。

---

## 四、V5 做了什么

### 1. 玩机页 → 实用功能 → 新增「KPM刷写」

占了原来那个「占位」格子，图标是新画的芯片矢量图。

页面内容：

- **环境卡片**（先回答「我这台能不能刷」）
  - ROOT 是否已获取
  - 是否检测到 APatch（含版本号）、`kptools` 是否可用
  - 模块目录状态：`/data/adb/ap/kpm/`（就绪 / APatch 不在，刷了也不会被加载 / 需要 ROOT）
  - 一段说明，把「刷入=放文件、需重启」和「热加载要用 APatch」讲清楚

- **已刷入列表**：读 `/data/adb/ap/kpm/<id>/`，解析每个 .kpm 的 name/version/author/license/description，显示启用/禁用状态，可**禁用/启用**、**卸载**

- **可刷入的文件列表**：扫描 VioletBox 下载目录、公共 Download 等位置的 `.kpm`，显示元信息，可**刷入**

- **右上角「选择文件」**：走系统文件选择器（SAF），任何目录的 .kpm 都能选
- **刷入前确认弹窗**：显示模块名/版本/作者/说明 + 目标目录 + 「需重启生效」；如果没解析出 `.kpm.info` 会**明确警告**这可能不是有效 KPM
- **刷入完成后**：弹窗给「打开 APatch」按钮，方便你去那边热加载或重启

### 2. 模块备份页 → 新增类型切换

顶部加了一排筛选（沿用模块下载中心的 Chip 风格）：

- **系统模块 (ZIP)** — 原来的行为，扫 `/data/adb/modules/*`
- **内核模块 (KPM)** — 新的，扫 `/data/adb/ap/kpm/<id>/<id>.kpm`，**并兼容社区目录 `/data/adb/kpm/*.kpm`**（很多 KPM 的 README 让你直接丢这里）

勾选后备份到 `/storage/emulated/0/Magisk模块备份/内核模块/`，每个 KPM 打成一个只含 `<id>.kpm` 的 zip——因为 KPM 是单文件，没有 `module.prop` 也没有目录结构，恢复时解压出来直接就能刷。

### 3. 元信息解析（不依赖 kptools）

新增 `com.violet.box.kpm.KpmInfo`，自己在 Java 里解析 ELF：

- 读 ELF 头，判断 32/64 位、大小端
- 遍历 section header，从 `.shstrtab` 取 section 名，找到 `.kpm.info`
- 按 `\0` 切分，解析 `key=value`

优先级：**直接读文件**（/sdcard 下的能读）→ **root 复制到 `/data/local/tmp/violetbox_kpm` 再读**（`/data/adb` 下的）→ **`kptools -l -M`**（兜底）。

我用合成 ELF 验证过解析正确性：

```
parsed = true
name   = hello
version= 1.0
license= GPL-2.0
author = bmax121
desc   = KernelPatch demo module
id     = hello
safeId: [My KPM!!] → [My_KPM]   [a/b/c] → [a_b_c]   [...] → [kpm]
```

### 4. 新增/改动文件

```
新增 app/src/main/java/com/violet/box/kpm/KpmInfo.java          # ELF .kpm.info 解析 + id 规则
新增 app/src/main/java/com/violet/box/kpm/KpmShell.java         # root shell：环境探测/清单/刷入/启停/卸载
新增 app/src/main/java/com/violet/box/ui/module/KpmManagerActivity.java
新增 app/src/main/res/layout/activity_kpm_manager.xml
新增 app/src/main/res/layout/item_kpm.xml
新增 app/src/main/res/layout/item_kpm_header.xml
新增 app/src/main/res/drawable/ic_kpm.xml

改动 app/src/main/res/layout/fragment_explore_placeholder.xml   # 实用功能加「KPM刷写」
改动 app/src/main/java/com/violet/box/ui/main/MainActivity.java # 绑定入口
改动 app/src/main/AndroidManifest.xml                           # 注册 Activity
改动 app/src/main/res/layout/activity_module_backup.xml         # 加类型切换 Chip
改动 app/src/main/java/com/violet/box/ui/module/ModuleBackupActivity.java  # KPM 模式
改动 app/proguard-rules.pro                                     # -keep com.violet.box.kpm.**
```

UI 全部沿用原有 iOS 玻璃风格（`ios_card` / `ios_card_corner` / `ios_accent`），没有引入新的视觉语言。

---

## 五、使用流程

1. 玩机 → 实用功能 → **KPM刷写**
2. 确认环境卡片显示 ROOT 已获取、检测到 APatch
3. 点「选择文件」选一个 `.kpm`（或先把模块下载中心的 KPM 下载好，会自动出现在「可刷入的文件」里）
4. 确认信息 → 刷入
5. **重启**让 APatch 加载它
6. 想立刻生效而不重启 → 点弹窗里的「打开 APatch」，在 APatch 里 Load

---

## 六、已知边界

1. **热加载/热卸载需要 APatch** —— 见第三节，这是 superkey 的设计约束，不是偷懒。
2. **没做自动 Embed** —— 那要改 boot 分区，失败就是真砖，不该由工具箱一键代劳。
3. **部分 KPM 可能解析不出元信息** —— 说明它不是标准 KPM、或编译时没用 `KPM_NAME()` 宏。此时 id 取文件名，弹窗会有警告。
4. **`/data/adb/kpm/` 是社区约定目录**，APatch 不会自动扫它，那边扫到的 KPM 只是列出来方便你备份/刷入。
