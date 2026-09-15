# V11：移除 KPM 刷写功能

产物：`apk/VioletBox-V11-release.apk` / `apk/VioletBox-V11-debug.apk`
对应源码标签：`V11` / `v1.1.0-V11`

---

## 一、删了什么

KPM 刷写整个功能链路，从入口到内核调用，全部移除。

### 代码

| 路径 | 说明 |
| --- | --- |
| `java/com/violet/box/kpm/` | 整个包删除：`KpmShell` / `KpmInfo` / `KpmPreset` / `KpmEmbedded` / `KpmEmbedTool` |
| `java/com/violet/box/ui/module/KpmManagerActivity.java` | KPM 刷写页面（~1000 行） |

### 资源

| 路径 | 说明 |
| --- | --- |
| `res/layout/activity_kpm_manager.xml` | 刷写页布局（含运行环境折叠卡片） |
| `res/layout/item_kpm.xml` / `item_kpm_header.xml` | 列表项与分组头 |
| `res/menu/menu_kpm.xml` | 刷新 / boot 备份恢复菜单 |
| `res/drawable/ic_kpm.xml` | KPM 图标 |
| `res/values/strings.xml` | `kpm_env_*` / `kpm_log_*` 五个字符串 |

### 入口与配置

- `AndroidManifest.xml`：移除 `KpmManagerActivity` 注册
- `MainActivity`：移除 `btnKpmManager` 点击跳转
- `res/layout/fragment_explore_placeholder.xml`：移除「KPM刷写」图标块（玩机 → 实用功能）
- `assets/kpload.arm64`：supercall 工具（4377 字节）

### 连带清理

`ModuleBackupActivity` 里的**内核模块备份**一并移除——它直接依赖 `kpm` 包：

- 「系统模块 (ZIP) / 内核模块 (KPM)」类型切换条（`ChipGroup`）整块删除，只剩一种类型就没必要选了
- `loadKpmModules()`、`doBackupKpm()`、`isKpmMode()`、`ModuleItem.isKpm` 字段全部删除
- 备份目录不再有 `/内核模块` 子目录

历史文档 `docs/kpload/`（helper 汇编源码与构建脚本）随之删除。

---

## 二、保留了什么（及原因）

| 保留项 | 原因 |
| --- | --- |
| **模块下载中心的 KPM 分类** | 那只是**下载**，不是刷写。178 个模块里 8 个 KPM 条目（`re_kernel`、`hosts_redirect`、`critical_partition_protect` 等）照常可搜可下 |
| 环境检测里提到 KPM 的那句说明 | 一句文案，解释检测项需要什么内核方案 |
| `docs/V5_`、`V7_`、`V8_` 三份 KPM 文档 | 版本历史记录，README 里有链接。它们描述的是**做过什么**，不是代码 |

---

## 三、验证

- 编译：`:app:assembleRelease` + `:app:assembleDebug` 通过
- 无悬空引用：`activity_kpm_manager` / `item_kpm` / `menu_kpm` / `kpm_env_*` / `kpm_log_*` / `ic_kpm` 引用数均为 0
- 产物核对（`classes.dex`）：`KpmManager` / `KpmShell` / `KpmInfo` / `kpload` / `kptools` / `supercall` / `/data/adb/ap/kpm` 全部消失
- 产物核对（`resources.arsc`）：`KPM刷写` / `activity_kpm_manager` / `item_kpm` / `menu_kpm` / `ic_kpm` 全部消失
- 保留项仍在：下载中心 178 个模块中 8 个 KPM 条目完好

---

## 四、按版本回溯

| 版本 | 标签 |
| --- | --- |
| V9 | `v1.1.0-V9` / `V9` |
| V10 | `v1.1.0-V10` / `V10` |
| **V11** | **`v1.1.0-V11` / `V11`** |

想把这个功能找回来：

```bash
git checkout V10 -- app/src/main/java/com/violet/box/kpm \
                    app/src/main/java/com/violet/box/ui/module/KpmManagerActivity.java \
                    app/src/main/res/layout/activity_kpm_manager.xml \
                    app/src/main/res/layout/item_kpm.xml \
                    app/src/main/res/layout/item_kpm_header.xml \
                    app/src/main/res/menu/menu_kpm.xml
```
