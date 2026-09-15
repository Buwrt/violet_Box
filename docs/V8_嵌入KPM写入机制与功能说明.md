# V8 · 嵌入 KPM 写入机制与功能说明

> 回答这一轮的两个问题：
>
> 1. **紫罗兰盒子的 APK 能不能像 [FolkPatch](https://github.com/LyraVoid/FolkPatch) 一样把 KPM 嵌进 boot 镜像？**
>    → **能。** V8 走的就是 FolkPatch 同一条 kptools 管线，而且比它多一层：不需要你的 superkey。
> 2. **模块备份能不能备份已嵌入的 KPM？**
>    → **能，V7 就已经能了**；V8 把「怎么找到它们」换成了更可靠的通道（见 §4），备份到的仍是完整的原始 `.kpm` ELF。

---

## 一、先说结论：和 FolkPatch 比，一样的地方和不一样的地方

| | FolkPatch / APatch | 紫罗兰盒子 V8 |
|---|---|---|
| 嵌入（Embed）写入 | ✅ `boot_patch.sh` + `kptools` | ✅ 同一条管线 |
| 移除已嵌入项 | ✅ 重打包时不带那一项 | ✅ 同上 |
| 读回「已嵌入」清单 | `kptools -l -i kernel` + ini4j | ✅ 同样用 kptools 列，另有纯 Java 兜底扫描 |
| 需要用户提供 superkey | **需要**（`-S "$SUPERKEY"`） | **不需要** —— 见 §3 |
| kpimg 来源 | APK 里内置 `assets/kpimg` | **从用户自己的 boot 镜像里抠出来**（版本必然一致） |
| 是否自动刷入 | 视「安装到设备」选项 | 默认**只生成镜像**，刷入要显式点第二次（§5） |
| 失败兜底 | `ori.img` 备份 | `/sdcard/Download/VioletBox/boot-backup-*.img` + 菜单可直接恢复 |

一句话：**写入语义完全一致，权限模型更保守，素材来源更稳。**

---

## 二、FolkPatch 到底是怎么把 KPM 嵌进去的（源码级）

### 2.1 很多人误以为的一段代码

`app/src/main/java/me/bmax/apatch/util/EmbeddedKpmUtils.kt` 看起来像在「做嵌入」，其实它**只读**：

```kotlin
fun getEmbeddedKpmNames(): Set<String>? {
    symlink kptools / busybox 出 nativeLibraryDir
    val bootDev = exec("./busybox sh ./boot_extract.sh")      // 拿到 BOOTIMAGE=
    exec("./kptools unpacknolog $bootDev")
    val out = exec("./kptools -l -i kernel")                   // INI 文本
    // ini4j 解析 patched -> patched，num，每个 [extra N] 的 type / name
}
```

它唯一的作用，是把 UI 上的状态从「已加载」区分成「**已嵌入**」，返回 `null` 代表解析失败。

### 2.2 真正写入的两处

**① 参数拼装** — `ui/viewmodel/PatchesViewModel.kt`：

```kotlin
for (i in 0..<newExtrasFileName.size) {          // 本次新增
    patchCommand.addAll(listOf("-M", newExtrasFileName[i]))
    extras[i].args?.let { patchCommand.addAll(listOf("-A", it)) }
    extras[i].event?.let { patchCommand.addAll(listOf("-V", it)) }
    patchCommand.addAll(listOf("-T", extras[i].type.desc))
}
for (i in 0..<existedExtras.size) {              // 已有的项，必须重新声明！
    patchCommand.addAll(listOf("-E", existedExtras[i].name))
    ... -A ... -V ... -T ...
}
```

**`-E` 那一段是最容易忽略、也最要命的**：kptools 生成新镜像时是完全从头写的，任何没有用 `-E` 重新点名的旧项都会无声消失。

**② 执行脚本** — `assets/boot_patch.sh`：

```sh
./kptools unpack "$BOOTIMAGE"          # 拆出 kernel（必要时解压）
mv kernel kernel.ori
./kptools -p -i kernel.ori -S "$SUPERKEY" -k kpimg -o kernel <上述参数>
./kptools repack "$BOOTIMAGE"          # 产出 new-boot.img
[ "$FLASH_TO_DEVICE" = true ] && flash_image new-boot.img "$BOOTIMAGE"
```

### 2.3 镜像里到底是什么形状

来自 `KernelPatch/tools/patch.c: patch_update_img_buf()`：

```
0                        ..  ori_kimg_len     原内核镜像
align_ceil(kimg,4096)    ..  +kpimg_size      kpimg 整块（含 64B setup_header_t + setup_preset_t）
+extras                  ..  +extra_size      128B「kpe」头链（每项后跟 args + payload）
```

`kernel/include/preset.h` 给出两个结构的确切尺寸：

```c
#define KP_MAGIC "KP1158"          #define MAGIC_LEN 0x8
#define KP_HEADER_SIZE 0x40        #define SUPER_KEY_LEN 0x40
#define ROOT_SUPER_KEY_HASH_LEN 0x20
#define PATCH_EXTRA_ITEM_LEN (128)

typedef struct { setup_header_t header; setup_preset_t setup; } preset_t;
```

`setup_preset_t` 前部全是 little-endian int64，偏移是固定的：

| setup 内偏移 | 字段 | 用途 |
|---|---|---|
| +8 | `kimg_size` | 校验锚点：`magic_offset == align_ceil(kimg_size, 4096)` |
| +16 | `kpimg_size` | 抠取 kpimg 的长度 |
| +56 | `extra_size` | 头链总长 |
| +160 | `header_backup[8]` | 第二条校验：必须像 arm64 主入口分支指令 |
| +168 | `superkey[64]` | `-s` 模式的明文密钥 |
| +232 | `root_superkey[32]` | `-S` 模式的 SHA256 哈希 |

> 由于 `setup->kpimg_size = kpimg_len` 且 kptools 读入时按 16 字节对齐，**按这个长度抠出来的字节流与原 kpimg 文件完全一致**——这就是 V8 不需要内置/下载 kpimg 的原因。

---

## 三、关键创新：不需要你的 superkey，也不会弄丢 ROOT

### 3.1 问题

`-p` 必须带 `-s` 或 `-S`（`if (!superkey && !root_key) tools_loge_exit("empty superkey")`）。APatch 自己的 key 存在它的加密偏好里，第三方拿不到；而**随便填一个新的 key 等于把 ROOT 权限改写掉**。

`tools/patch.c` 的写入逻辑：

```c
if (!root_key) {
    strncpy(setup->superkey, superkey, SUPER_KEY_LEN - 1);      // -s：明文
} else if (superkey && superkey[0] != '\0') {
    sha256(superkey) → memcpy(setup->root_superkey, hash, 32);  // -S：哈希
} else {
    memset(setup->root_superkey, 0, 32);                        // 空 key → 全零
}
```

而校验侧（`kernel/base/predata.c: auth_superkey()`）：

```c
// 先比明文 superkey；不匹配再看 root key 是否启用，比 SHA256(root_superkey, key, 32)
// 首次哈希匹配后 reset_superkey(key) 并关掉 root key 模式
root_superkey_is_set = *(uint64_t *)root_superkey;   // 只看前 8 字节是否为 0
```

也就是说：**ROOT 的凭据就是 `superkey` + `root_superkey` 这 96 个字节。**

### 3.2 解法

这 96 个字节本来就在用户自己的 boot 镜像里，原样搬走即可：

```
1. 读旧镜像 → KpmPreset.scan() 把 superkey[64] 和 root_superkey[32] 抓出来
2. kptools -p -S violetbox ...        ← 占位密钥，爱是什么是什么
3. 用 dd 把旧的两个字段按字节写回新 kernel 的对应偏移（conv=notrunc）
4. 立刻再读一遍比对，不一致就中止且不刷入
```

`-s` 模式的机器：搬回来的是「明文非空 + 哈希全零」；`-S` 模式的机器：搬回来的是「明文全零 + 哈希非空」。两种情况都和原来**逐位相同**，ROOT 授权完全不受影响。这一步在 `KpmEmbedTool.restoreKeyMaterial()` 里，配有写回后自校验。

> 附带好处：**永远不需要让用户把 superkey 交出来**，也就没有密钥被明文塞进日志、SharedPreferences 或网络的可能。

---

## 四、顺手修掉了「我已经嵌入模块了，为什么显示我没有」

这是上一版真正出错的地方，值得单独记一笔。

V7 的做法是**直接流式扫描 boot 分区**找 `kpe` 魔数。这在「内核段未被重新压缩」时有效，但看一眼 `tools/bootimg.c: repack_bootimg_mem()` 就知道，重打包时会按原算法（gzip / lz4 / zstd / bzip2）**把整个打过补丁的内核重新压缩回去**。一旦内核是压缩存放的，原始分区里就只有压缩流，没有明文魔数可找——所以你明明嵌了模块，App 却说「没找到」。

V8 改成和 FolkPatch 一致的读法，并保留旧做法兜底：

```
dd <boot 分区> → /data/local/tmp/violetbox_embed/boot.img
kptools unpack boot.img                       # 得到解包（必要时解压）后的 kernel
├─ KpmPreset.scan(kernel)     → 有没有 KernelPatch 补丁、版本、superkey 字段
├─ kptools -i kernel -l       → 权威的嵌入项清单（type/name/event/args/priority/size）
├─ kptools -i kernel -f       → CONFIG_KALLSYMS 是否满足
└─ KpmEmbedded.scan(kernel)   → 把每个 KPM 的 payload 原样抠成 .kpm（用于导出/备份）
```

KPM刷写页和模块备份页两条路都改用了这个通道。全过程仍然是 `su -c cat` 流式解析，内存里不会多出一份镜像副本。

---

## 五、写入管线与每一道闸

`KpmEmbedTool.patch()` 的完整顺序：

| # | 动作 | 失败时 |
|---|---|---|
| 0 | 前置检查：ROOT、kptools、`CONFIG_KALLSYMS`、文件大小 ≤ 32MB、`.kpm.info` 可解析 | 直接返回原因 |
| 1 | **重名保护**：已嵌入项之间同名会让 `-E name` 绑错对象 → 拒绝并要求先用 APatch 清理 | 不开始 |
| 2 | **隐藏项保护**：`setup.extra_size` 比「清单里各项之和」多出 >4KB（通常是 legacy kconfig 嵌入项，kptools 列不出来、重打包会丢） | 不开始 |
| 3 | 新模块若与已有项同名 → 拒绝 | 不开始 |
| 4 | 原 boot 镜像备份到 `/sdcard/Download/VioletBox/boot-backup-<分区>-<时间戳>.img` | 记进日志继续（备份失败也要告知） |
| 5 | `kptools -p -i kernel.ori -S <占位> -k kpimg -o kernel -M <新> -T kpm … -E <保留项> -T … [-V …] [-A …]` | 停止，未写盘 |
| 6 | 回填原始 superkey 字段 + 写回自校验 | 停止，未写盘 |
| 7 | 再 `-i kernel -l`，嵌入项数量必须等于「保留 + 新增」 | 停止 |
| 8 | `kptools repack boot.img` → `new-boot.img` | 停止 |
| 9 | 镜像体积不得超过分区（`blockdev --getsize64`） | 停止，但镜像留着给你 fastboot 用 |
| 10 | **端到端验证**：把 `new-boot.img` 另解包一次，核对数量 **且** kpimg 里的 superkey 字段与旧的一致 | 停止 |
| 11 | 仅在用户选了「生成并刷入」时才 `dd` 写回，写完 `sync` | 报告失败 + 备份路径 |

UI 上的三个按钮是 **仅生成 / 生成并刷入 / 取消**——默认落点是「仅生成」。任何一步出错，对话框第一句话就是「**没有写入 boot 分区**」。

另外，Toolbar 菜单新增 **boot 镜像备份 / 恢复**：可一键备份当前 boot 分区，或把 `/sdcard/Download/VioletBox/` 里的 `.img` 写回去。

---

## 六、两处和 FolkPatch 的实现差异（都是刻意的）

1. **不用 busybox。** FolkPatch 要先把 `libbusybox.so` 从自己的 nativeLibraryDir 软链出来跑 `boot_extract.sh`。V8 全程只用 toybox 自带的 `cat / dd / cp / chmod / mkdir / stat / blockdev`，有没有 busybox 都一样跑。
2. **`-M` 后面不传 `-V`。** 内核侧的 `extra_event_load_kpm()` 是这么匹配的：

   ```c
   if (!strcmp(event, extra->event) || (!extra->event[0] && !strcmp(event, EXTRA_EVENT_KPM_DEFAULT)))
   ```

   即 **event 留空就等于 `pre-kernel-init`**，这正是 KPM 的默认位置。少传一个参数，少一处可能写错的机会。

---

## 七、怎么用

```
玩机 → 实用功能 → KPM刷写
```

- 环境卡片多了「**嵌入 boot：**」一行，会如实写：`可用（boot_a · KernelPatch 0.11.4）` 或 `不可用 — <原因>`
- 右下角「选择文件刷入」→ 选 `.kpm` → **刷入方式**二选：
  - **安装到模块目录**（默认，可随时卸载）
  - **嵌入到 boot 镜像**（随内核最早启动）
- 「已嵌入 · boot 镜像」区块里每条现在有 **导出** 与 **移除**
- Toolbar 菜单 → **boot 镜像备份 / 恢复**

**模块备份**：玩机 → 模块备份 → 类型切到「内核模块 (KPM)」，已嵌入的条目带「（已嵌入）」后缀，勾上就能和普通的 KPM 一起打包进 zip——内容是从 boot 镜像里抠出来的原始 ELF，可以直接被 APatch/FolkPatch 再次嵌入。

---

## 八、必须说清楚的限制

1. **只能处理「已经被 KernelPatch 打过补丁」的 boot。** V8 用 kptools 自己的两条判据来确认（magic 偏移必须等于 `align_ceil(kimg_size,4096)`、`header_backup` 必须像一条 arm64 分支），确认不了就报「boot / init_boot 镜像里没有 KernelPatch 补丁」并放弃。首次安装 KernelPatch 请用 APatch / FolkPatch。
2. **写 boot 分区永远是危险操作。** 备份会先落到 `/sdcard/Download/VioletBox/`，但请确认你会用 `fastboot flash boot` 或 Recovery 救回来。
3. **运行时热加载（Load）依然做不到**，也不需要伪装：那走 KernelPatch supercall（syscall 45），第一个参数是 superkey，第三方拿不到。
4. **沙箱里没有真机也没有模拟器。** 本次改动做了这些验证：
   - `KpmPreset` 单元测试全绿（合成 860KB 补丁镜像）：魔数落点 = `align_ceil(kimg,4096)`、版本 `0.11.4`、compile_time、kpimg_size、extra_size 全部正确；**抠出的 kpimg 与原始字节流完全一致**；1 字节一次的分片流结果相同；未打补丁镜像返回 `null`；`kpimg_size` 被篡改成 `Long.MAX_VALUE` 时正确拒绝。
   - `KpmEmbedded` 在同一镜像上仍能找出 2 个 KPM。
   - `KpmEmbedTool.parseExtras()` 对真实 `kptools -l` 输出（`[extra N]`、`args_size=0x10`、空 `event=`）解析正确。
   - APK 静态校验：dex 内含 `KpmPreset` / `KpmEmbedTool`，资源内含 `action_kpm_boot` / `tvKpmEnvEmbed`，关键命令行字符串均在包内（R8 未把 shell 脚本字符串改坏）。
   - **没有验证过的部分**：真实设备上的 kptools 版本差异、`dd`/`repack` 在各种 boot header 版本（v0~v4）下的表现、以及实际写盘。第一次用务必先选「仅生成」。

---

## 九、产物

| 文件 | 大小 | SHA256 |
|---|---|---|
| `/workspace/apk/VioletBox-V8-release.apk` | 3,593,507 B | `3bcbe46596cb226cd8ce6fce91e36ce23fc0aa531af622234b7f118aa39c89d9` |
| `/workspace/apk/VioletBox-V8-debug.apk` | 18,303,151 B | `bb9af0f236813504738bc8e4d064390e1c80c8520cfcb9a5aad8a2ee41b28aba` |

源码同步在 `/workspace/violet_box_模块下载补丁/`（`java/kpm/KpmPreset.java`、`java/kpm/KpmEmbedTool.java` 为新增）。

**授权说明**：本文引用的 KernelPatch 片段来自 GPL-2.0-or-later 的 KernelPatch 项目；FolkPatch 片段来自其开源仓库；紫罗兰盒子本体为 GPL-3.0。本版本的实现未复制任何二进制素材（kpimg/kptools 均来自设备自身），仅用 Java 复刻了解析与编排逻辑。
