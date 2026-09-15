# V6：UI 统一到原生设计 + 备份空状态说明

产物：`apk/VioletBox-V6-release.apk` / `apk/VioletBox-V6-debug.apk`

---

## 一、先回答「我已经给了 ROOT，为什么还是这样」

**ROOT 是拿到手的**，这一点从 KPM 刷写页的环境卡片就能自证：

```
ROOT：已获取
KernelPatch / APatch：APatch 115028（kptools 可用）
模块目录：/data/adb/ap/kpm/（就绪）
```

这三行都是真的通过 root shell 跑出来的（`id -u`、读 APatch 版本、列 `/data/adb/ap/kpm/`）。所以问题是另一回事：

> **「ROOT 有」≠「设备上有 KPM」。**

「模块备份 → 内核模块」和「KPM 刷写 → 已刷入」扫的都是**已经存在于设备上的 KPM 文件**：

```
/data/adb/ap/kpm/<id>/<id>.kpm     ← APatch 的持久化安装布局
/data/adb/kpm/*.kpm                ← 社区常见放置目录（兼容扫描）
```

你的机器上这两个目录都是空的（KPM 刷写页同样显示「暂无 KPM」），所以备份列表自然也是空的。这跟你给没给 ROOT 没有关系——**你得先有一个 KPM 才能备份它**。

### 怎么让它有东西

1. 玩机 → 实用功能 → **KPM刷写** → 右下角「选择文件刷入」（或先在模块下载中心的「KPM 内核模块」分类里下载一个）
2. **重启**，APatch 开机把它加载起来
3. 再回到「模块备份 → 内核模块」，就能看到并勾选备份了

### 顺带修掉的误导文案

原来那句 `未找到内核模块（需 ROOT，且 KPM 需已刷入）` 被挤在「全选」那一行右侧，换行后看着像是报错。现在拆成两处：

- 状态栏只留短的：`未找到内核模块`
- 列表区**居中**显示完整解释：

  > 还没有已刷入的内核模块
  >
  > ROOT 正常，但设备上没有安装任何 KPM。
  > 可到「玩机 → KPM刷写」刷入，重启后即可在这里备份。

ZIP（系统模块）模式也补了同样的居中空状态：`未找到系统模块 / 已安装的 Magisk / KSU / APatch 模块会出现在这里。如果装了模块却看不到，请先授予 ROOT 权限。`

---

## 二、UI 难看的真凶：返回键用错了图标

两个页面的「返回按钮」我误用了 `ic_explore`——那是**指南针图标**，所以左上角那个黑色圆圈根本不是返回箭头，而且和标题挤在一起。

```
<!-- 之前 -->
<ImageButton android:id="@+id/btnKpmBack" android:src="@drawable/ic_explore" ... />
```

同时这两页用的都是我自己手搓的 `LinearLayout` 标题栏（大号加粗标题 + 右上角蓝色文字链接），和 App 原生页面（模块备份、设备信息等）的 `Toolbar` 标题栏不是一套东西，所以显得格外突兀。

## 三、改成了什么

统一采用「模块备份」页的骨架与视觉语言：

| 元素 | 之前 | 现在 |
|---|---|---|
| 标题栏 | 自绘 LinearLayout + 指南针图标 | `AppBarLayout` + `Toolbar`，`?attr/homeAsUpIndicator`（真·返回箭头），标题走 `TextAppearance.AppCompat.Widget.ActionBar.Title` |
| 右上角文字链接（刷新/选择文件） | 蓝色文字挤在标题旁 | 刷新 → Toolbar 菜单里的刷新图标（`ic_refresh`）；选择文件 → 右下角 ExtendedFAB |
| FAB | 无 | 「选择文件刷入」，青色 `explore_cyan_600`，与备份页「备份所选」一致 |
| 列表卡片 | 灰白玻璃卡 `ios_card`（无描边无阴影） | 与 `item_module_backup` 同款：白底 + `explore_slate_200` 描边 + 2dp 阴影 + 12dp 圆角 |
| 徽章 ZIP / KPM | `purple_200`（#EFE9FF 浅紫）配**白字**，几乎看不清 | 实色胶囊：ZIP = 紫罗兰 `ios_accent`，KPM = 青 `explore_cyan_600`，白字 |
| 「下载最新」按钮 | 蓝色文字，两行「下载\n最新」 | 实心圆角按钮，单行 `下载最新` |
| 环境卡片 | 灰白无描边 | 白卡 + 描边 + 阴影，标题「运行环境」，正文 `slate_800` |
| 筛选 Chips | 悬空在背景上 | 与备份页一样放进 `ios_card` 通栏条里，下方接结果计数 |

KPM 行的按钮也重排了：禁用/启用、卸载为文字按钮，**「刷入」改成实心胶囊主按钮**。

## 四、改动文件

```
新增 res/drawable/ic_refresh.xml            # Toolbar 刷新图标
新增 res/drawable/bg_badge_pill.xml         # 徽章底（tint 决定颜色）
新增 res/drawable/bg_btn_accent_pill.xml    # 实心圆角主按钮
新增 res/menu/menu_kpm.xml                  # 刷新
新增 res/menu/menu_repo.xml                 # 刷新清单

重写 res/layout/activity_kpm_manager.xml     # AppBar + Toolbar + 环境卡 + FAB
重写 res/layout/activity_module_repo.xml     # AppBar + Toolbar + 搜索 + Chips 条
重排 res/layout/item_kpm.xml                 # 白卡 + 实色徽章 + 胶囊按钮
重排 res/layout/item_module_repo.xml         # 白卡 + 实色徽章 + 胶囊按钮
微改 res/layout/item_kpm_header.xml
微改 res/layout/activity_module_backup.xml   # 列表区加居中空状态

改动 ui/module/KpmManagerActivity.java       # extend AppCompatActivity + Toolbar + 菜单
改动 ui/repo/ModuleRepoActivity.java         # 同上
改动 ui/repo/ModuleRepoAdapter.java          # 徽章配色 + 按钮文案单行
改动 ui/module/ModuleBackupActivity.java     # 空状态视图与文案
```

## 五、校验

`assembleDebug` / `assembleRelease` 均 **BUILD SUCCESSFUL**，静态检查：

- dex：`KpmInfo` / `KpmShell` / `KpmManagerActivity` / `ModuleRepoActivity` / `ModuleBackupActivity` 全在（R8 `-keep` 生效），新文案都在
- 资源：新布局、两个菜单、三个新 drawable 均进包（release 下资源名被 AGP 混淆成 `res/xxx.xml`，按内容校验通过）
- `assets/module_repo.json` 仍为 **178** 条

| 文件 | 体积 | SHA256 |
|---|---|---|
| `VioletBox-V6-release.apk` | 3.58 MB | `f096c3051550226fd2f89ea9f39a809f78f53fe0cf0f2ebd7afb38607aa5e881` |
| `VioletBox-V6-debug.apk` | 18.3 MB | `a058844a9e52e0961100b577237a93b98316612352feb2c6bd0fd6ce5edb07cc` |

**仍未真机验证**：沙箱里没有手机也没有模拟器，Toolbar 标题栏、FAB、菜单图标没能亲眼跑一遍；装上去如有偏差（比如深色模式下图标颜色）告诉我，我按截图继续调。
