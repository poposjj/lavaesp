# Lava ESP

作者：**oioi**

一款独立的 Minecraft 26.2 / Fabric 客户端模组。用于高亮玩家周围所有 y ≥ 64 的岩浆方块，支持穿墙显示。

**用途**：Hypixel SkyBlock 水晶矿洞（Crystal Hollows）钓虫时定位岩浆点位。

设置入口有两个：游戏内指令，以及模组菜单中的配置界面。

## 功能

- **范围**：X/Z 方向不限，覆盖玩家周围所有已加载区块（受渲染距离限制，最多 32 区块半径）；Y 轴范围 64 至 200。
- **穿墙显示**：基于香草调试渲染 API `Gizmos`，岩浆方块可透过地形可见。
- **分档着色**：六个相邻方块均为岩浆的方块视为内部方块，以 10% 不透明度绘制；其余方块使用配置色完整绘制。两者均会渲染，不作隐藏处理。
- **距离淡出**：默认在 25 格内渐显，可在设置界面调整为 10 至 100 格。
- **扫描机制**：每 1000 毫秒执行一轮，运行于独立守护线程。按区块、子区块（section）层级遍历，先经调色板判定筛选，再逐格检测。设有 10 秒超时保护，并以代际号防止过期结果覆盖新结果。

## 指令

| 指令 | 说明 |
| --- | --- |
| `/lavaesp` | 打开设置界面 |
| `/lavaesp on` / `off` | 开启 / 关闭 |
| `/lavaesp rescan` | 清空缓存并立即重扫 |
| `/lavaesp color <AARRGGBB>` | 设置颜色，前两位为不透明度 |
| `/lavaesp fade <true\|false>` | 距离淡出开关 |
| `/lavaesp fadestart <10-100>` | 淡出起始距离 |

## 设置界面

以下两种方式进入同一界面：

- 游戏内执行 `/lavaesp`
- 模组菜单（Mod Menu）→ Lava ESP → 配置

界面包含作者信息、各项设置的简要说明，以及开关、淡出、淡出起点、颜色（预设循环与手动输入）、立即重扫、完成等控件。所有修改即时写入 `config/lavaesp.properties`。

## 构建

需要 JDK 25，以及解包的 Minecraft 26.2 客户端依赖目录。

依赖目录结构：

```
<McDir>\client.jar          Minecraft 26.2 客户端（官方名称，未混淆）
<McDir>\libs\*.jar          客户端依赖库
<McDir>\fabric-api\*.jar    Fabric API 0.161.0+26.2 的嵌套模块
<McDir>\extra\*.jar         Mod Menu（仅编译期使用）
```

构建命令：

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"
.\build.ps1 -McDir "D:\path\to\mc262"
```

产物路径：`build\libs\lavaesp-1.0.0.jar`

## 依赖

| 项目 | 版本 |
| --- | --- |
| Minecraft | ~26.2 |
| Fabric Loader | >= 0.17.0 |
| Fabric API | * |
| Java | >= 25 |
| Mod Menu | >= 20.0.0（可选，仅用于设置界面入口） |

## 实现说明

- 渲染使用香草调试渲染 API `Gizmos`。26.2 的提交式渲染管线对自定义 `RenderType` 支持有限，`Gizmos` 在不同环境下更稳定。

## 许可与声明

仅供个人在 Hypixel SkyBlock 中使用。在任何服务器使用第三方模组均存在封禁风险，请自行判断。

本项目与 Hypixel、Mojang、Fabric 无任何关联。
