# V11修改版：把「模块备份 → 内核模块 (KPM)」加回来

## 一、为什么会没了

V11 的需求是「把 KPM 刷写的所有代码全部删除」。当时删得比较彻底：

- 删掉了 `com.violet.box.kpm` 整个包；
- 顺带删掉了 `ModuleBackupActivity` 里依赖这个包的 KPM 备份分支，以及备份页顶部那个
  「系统模块 (ZIP) / 内核模块 (KPM)」切换控件。

问题在于：**KPM 备份并不属于「刷写」**。它只读 —— 列目录、读 ELF 元数据、抠出已嵌入的
payload 打成 zip。真正会动分区的是嵌入写回 / 热加载 / 安装 / 删除，那部分才是该删的。
V11 把两者一起端掉了，于是备份页只剩系统模块一个页签。

这一版把只读的那部分单独裁出来恢复，刷写能力保持删除状态。

## 二、这一版恢复了什么

### 1. `com.violet.box.kpm` —— 但只剩「读」

| 文件 | 处理方式 |
|---|---|
| `KpmInfo.java` | 原样恢复。解析 KPM ELF 里的 `.kpm.info` 段，取出 name/version/author/description |
| `KpmPreset.java` | 原样恢复。解析 kpimg 头，判断镜像有没有 KernelPatch 补丁 |
| `KpmEmbedded.java` | 原样恢复。从内核镜像流里按 kpe 头链抠出 payload ELF |
| `KpmShell.java` | **精简后恢复**。只保留列目录、读元数据、把 root 只读文件复制成可读副本 |
| `KpmEmbedTool.java` | **精简后恢复**。只保留 `inspect()`，即「识别已嵌入的 KPM」 |

被永久移除、这一版也没有带回来的部分：

- `KpmShell.install()` / `setEnabled()` / `uninstall()` / `deleteFile()` / `writePrivileged()`
  —— 所有会往 `/data/adb/ap/` 写东西的入口；
- `KpmEmbedTool.patch()` 及其一整套流水线（备份 boot → `kptools -p` → 回填 superkey →
  `repack` → `dd` 回分区），以及 `Result`、`restoreKeyMaterial()`、`backupBoot()`、
  `backups()`、`restore()`；
- V10 加进去的热加载通道（`SUPERCALL_KPM_LOAD`、`kpload.arm64`、明文 superkey 复用）；
- `KpmManagerActivity` 及其布局、菜单、图标、入口按钮。

一句话：**能读、能列、能备份；不能刷、不能装、不能删、不能改分区。**

### 2. 模块备份页

- 顶部恢复「系统模块 (ZIP) / 内核模块 (KPM)」切换（ChipGroup，默认系统模块）；
- 切到内核模块后，扫描三个来源：
  1. `/data/adb/ap/kpm/<id>/<id>.kpm` —— APatch 的持久化安装位，带启用/禁用标记；
  2. `/data/adb/kpm/*.kpm` —— 社区约定散放目录，不由 APatch 管理；
  3. boot / init_boot 镜像里**已嵌入**的 KPM —— dump 分区 → `kptools unpack` → 读 kpe 链 →
     抠出 ELF，名字后标注「（已嵌入）」；
- 备份产物落到 `/storage/emulated/0/Magisk模块备份/内核模块/`，每个 KPM 一个 zip，
  里面就一个 `<id>.kpm`，解压即可直接再用；
- 空态文案改成「先用 APatch / KernelPatch 之类的管理器刷入或嵌入，之后即可在这里备份」
  （原来那句指向的 KPM 刷写页面已经不存在了）。

## 三、验证

- `assembleDebug` / `assembleRelease` 均 `BUILD SUCCESSFUL`；
- 产物 `classes.dex` 中可查到 `KpmShell`、`KpmEmbedTool`、`KpmInfo`、`KpmPreset`、
  `KpmEmbedded`、`/data/adb/ap/kpm`、`/内核模块`；
- 产物中**查不到** `KpmManagerActivity`、`kpload`、`SUPERCALL`、`supercall`、`KPM刷写`、
  `ic_kpm`、`activity_kpm_manager`、`重新打补丁失败`；
- 切换控件：资源 id `chipGroupBackupType` 在 `resources.arsc`，文案「内核模块 (KPM)」
  在编译后的布局文件里，两者都在。

## 四、产物

| 文件 | 大小 | sha256 |
|---|---|---|
| `VioletBox-V11修改版-release.apk` | 3,570,698 B | `d75ba87943ed3b92…466835e0` |
| `VioletBox-V11修改版-debug.apk` | 18,301,570 B | `0ca6609374a7c1da…4e9ca424` |

## 五、版本回溯

- `V11修改版` / `v1.1.0-V11修改版` —— 本版（有备份、无刷写）
- `V11` / `v1.1.0-V11` —— 无备份、无刷写
- `V10` / `v1.1.0-V10` —— 刷写三选一（嵌入 / 加载 / 安装）
- `V9` … `V5` —— 更早的迭代

想连刷写一起拿回来（不推荐，除非你确实需要），可以：

```bash
git checkout V10 -- app/src/main/java/com/violet/box/kpm \
  app/src/main/java/com/violet/box/ui/module/KpmManagerActivity.java \
  app/src/main/res/layout/activity_kpm_manager.xml
```
