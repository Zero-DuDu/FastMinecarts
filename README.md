# FastMinecarts

按**铁轨下方方块**改变被乘坐矿车的速度上限，并为上坡补足推进力的 Paper / Folia 插件。

- **维护者**：zerodudu
- **原作者 / 上游**：[certainly1182/FastMinecarts](https://github.com/certainly1182/FastMinecarts)（MIT，2023）
- **许可证**：MIT，保留上游版权声明，见 [LICENSE](LICENSE)
- **本仓库性质**：上游的本地加强版，个人服务器自用，未发布到 Modrinth 等平台

---

## 来源与署名

本插件基于 certainly1182 的开源项目 [FastMinecarts](https://github.com/certainly1182/FastMinecarts)（MIT 许可）修改而来。

| 归属 | 内容 |
| --- | --- |
| **certainly1182**（原作者 / 上游） | 原始设计、初始实现、`blocks` 方块速度表机制、下车复位原版速度 |
| **zerodudu**（本版本维护者） | 上坡补速、宽限期、reload 命令、Folia 支持、配置校验、并发安全等本版本的全部改动 |

上游版权声明与 MIT 许可原文完整保留在 [LICENSE](LICENSE) 中。代码包名 `me.certainly1182.fastminecarts` 沿用上游，以保持血缘清晰、便于对照上游代码。

## 上游原本具备的功能

上游 README 记录 3 项能力，其 `config.yml` 只有 `blocks` 一个配置段：

1. 可配置哪些方块影响速度
2. 可为每个方块指定矿车速度
3. 玩家下车时把矿车减速回原版速度

## 与上游的差异（一）：新增

| 新增内容 | 说明 |
| --- | --- |
| **上坡补速 `slope-assist`** | 三种模式 `off` / `boost` / `constant`，配套 `boost-per-tick`、`min-speed-ratio` 两个参数。由**实体调度任务**每 tick 驱动，沿坡道方向补速度向量 |
| **宽限期 `grace-ticks`** | 离开配置路段后仍维持上次速度上限一段时间（默认 40 tick = 2 秒），消除速度上限抖动 |
| **`/fastminecarts reload` 命令** | 别名 `/fmc reload`，配套 `fastminecarts.reload` 权限。上游 README 明确写有"没有命令和权限"，本版本补上 |
| **Folia 支持** | `folia-supported: true`。使用实体调度器而非全局调度器，任务跟随矿车所在的区域线程 |
| **配置校验与警告日志** | 非法方块名、非方块材料、非有限数值、越界参数都会被忽略或回退默认值，并打出明确的中文警告 |
| **线程安全与生命周期管理** | 并发状态表、`@Volatile` 配置快照、reload 原子替换配置、卸载时取消全部任务并复位矿车 |

## 与上游的差异（二）：行为调整

差异依据上游 README 与其提交记录整理。

1. **速度上限解析**：不再是"当前脚下方块有配置就用，没有就立刻回原版"，而是三级判定——命中配置用配置值；未命中但仍在宽限期内维持上次的值；宽限期结束才回到原版 `0.4`。
2. **上坡行为**：仅提高 `maxSpeed` 不会让矿车在坡上有更多动能。本版本在"上坡轨道 + 当前处于加速路段（目标速度 > 0.4）"时额外补充推进力，平地与下坡完全交给原版物理。
3. **下车复位时机**：仅当车内不再有其他玩家时（最后一名玩家下车）才把 `maxSpeed` 复位为原版 `0.4`，避免多人同乘时提前复位。
4. **铁轨定位**：先看矿车到达位置本身是否为铁轨，否则看其下方一格，再取该铁轨**正下方**的方块作为速度依据。
5. **卸载 / 重载清理**：取消全部补速任务、清空状态表，并尝试把矿车速度复位为原版。

## 这样改的好处

- **让高速铁路真的能跑起来**：原版矿车爬坡时动能被重力持续消耗，只提高 `maxSpeed` 只在平地与下坡有效，上坡照样失速停住甚至往回滑。补速之后，长距离上坡线路不再"卡在坡底打滑"，配置的高速（例如 `GRAVEL: 0.8`）才真正可用。
- **手感可调，且不破坏原版**：`boost` 模式逐 tick 补一点力，保留"爬坡费力"的手感；`constant` 模式像高速列车；`off` 则完全交还原版。介入条件被严格限制在"上坡 + 加速路段"，未配置的普通线路、平地与下坡不受任何影响，出问题也能一键还原。
- **速度上限不再抖动**：垫坡方块、轨道路口、单格缺料会让"铁轨下方方块"频繁变化，导致上限来回跳、矿车一顿一顿。宽限期把 2 秒内的波动吃掉。
- **配置写错能立刻发现**：非法方块名与非法数值会被忽略并打出明确警告，而不是静默失效或抛异常，排错时间大幅缩短。
- **调参不用重启**：`/fmc reload` 即时生效。上坡手感这类数值本来就需要反复试，效率提升明显。
- **Folia 也能用，且只有一份实现**：用实体调度器替代全局调度器，不需要为 Paper / Folia 维护两套代码。
- **长跑不积累垃圾**：并发状态表配合明确的取消/清空路径，反复 reload 或长时间运行都不会留下失效状态与孤儿任务。

## 速度上限是怎么决定的

```text
矿车每移动一次（VehicleMoveEvent）：
  1. 忽略车上没有玩家的矿车
  2. 定位铁轨：到达位置本身是铁轨 → 用它；否则用其下方一格
  3. 速度依据 = 该铁轨正下方方块在 blocks 中的配置值
  4. 命中 → 采用配置值，并记录"速度 + 当前 tick"
     未命中 → 距上次命中未超过 grace-ticks 就沿用上次的值，否则回原版 0.4
  5. 写入 minecart.maxSpeed
  6. 若最终速度高于原版 0.4 且 slope-assist 未关闭 → 为该矿车挂上补速任务
```

单位是**格/tick**，×20 约等于格/秒。原版矿车速度上限为 `0.4`。

## 上坡补速是怎么工作的

每 tick（实体调度任务）：

1. **状态自检**：状态已被替换 / 矿车已无效 / 车上已没有玩家 → 清理状态、复位 `maxSpeed`、结束任务。
2. **配置自检**：`slope-assist.mode` 已改为 `off`，或目标速度已回落到原版 → 结束任务。
3. 取矿车当前所在铁轨；非上坡轨道（`ASCENDING_NORTH/SOUTH/EAST/WEST`）→ 本 tick 不动作。
4. 计算 45° 上坡单位向量；若与"停住前的行进朝向"相反 → 不动作（说明车头朝另一边）。
5. 当前沿坡方向的速度分量低于目标值时补齐：`constant` 用目标速度，`boost` 用目标速度 × `min-speed-ratio`。
6. `constant` 一次性补齐差额；`boost` 每 tick 最多补 `boost-per-tick`。

补速必须由调度任务驱动而非移动事件：**矿车在坡上失速停住后就不再触发 `VehicleMoveEvent`，而那恰恰是最需要补速的时刻。**

另一个关键点是沿坡道方向**加**速度向量，而不是缩放现有速度向量——矿车失速后速度方向已经朝下，缩放只会让它滑得更快，且速度接近零时缩放倍数趋于无穷。

## 安装

1. 把 `FastMinecarts-1.0.0.jar` 放进服务端的 `plugins/` 目录。
2. 启动服务器，确认插件加载成功。
3. 首次启动会生成 `plugins/FastMinecarts/config.yml`。

## 配置

配置文件位于 `plugins/FastMinecarts/config.yml`。

```yml
# 离开配置路段后的宽限 Tick 数，期间维持原有速度上限。
# 用于避免斜坡垫坡方块、轨道路口、单格缺料导致的速度上限抖动。
# 设为 0 则立即恢复原版速度上限。
grace-ticks: 40

# 上坡辅助
slope-assist:
  # off      = 不补速，爬坡完全交给原版
  # boost    = 逐 Tick 补一点力，速度不低于目标速度的 min-speed-ratio，手感接近原版
  # constant = 坡上直接维持目标速度，手感像高速列车
  mode: boost

  # boost 模式每 Tick 补充的速度，单位为格/Tick。
  # 数值越大爬坡越猛，过大会显得不自然。
  boost-per-tick: 0.04

  # boost 模式在坡道上允许掉到目标速度的最低比例（0.0 ~ 1.0）。
  # 0.85 表示坡上最低维持目标速度的 85%，保留一点"爬坡费力"的手感。
  # 设为 1.0 效果接近 constant 模式。
  min-speed-ratio: 0.85

# 铁轨下方方块对应的目标最高速度，单位为格/Tick（数值 × 20 约等于格/秒）
blocks:
  GRAVEL: 0.8
  SOUL_SAND: 0.2
```

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `grace-ticks` | `40` | 离开配置路段后维持上次速度上限的 tick 数。为负数会警告并回退默认值 |
| `slope-assist.mode` | `boost` | `off` / `boost` / `constant`。拼写无效会警告并回退 `boost` |
| `slope-assist.boost-per-tick` | `0.04` | `boost` 模式每 tick 补充的速度。必须为大于 0 的有限数值 |
| `slope-assist.min-speed-ratio` | `0.85` | 坡上允许掉到目标速度的最低比例，取值 `0.0 ~ 1.0` |
| `blocks.<方块名>` | 无 | 铁轨**下方**方块 → 该路段的速度上限。方块名同 [Material](https://jd.papermc.io/paper/org/bukkit/Material.html)，支持 `namespace:key` 形式 |

`blocks` 中非法方块名、非方块材料、速度为 0 / 负数 / NaN / Infinity 的条目会被忽略，并在控制台打出警告。

## 命令与权限

| 命令 | 说明 |
| --- | --- |
| `/fastminecarts reload` | 重载配置、重建速度表、取消全部补速任务并清空状态（别名 `/fmc reload`） |

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `fastminecarts.reload` | op | 允许重载配置 |

## 从源码构建

需要 **JDK 25** 与 Maven。依赖 paper-api `26.1.2.build.70-stable`（provided），Kotlin `2.3.20`。

```bash
mvn clean package
```

产物：

- `target/FastMinecarts-1.0.0.jar` — **用这个**，已内嵌 Kotlin 运行时（shade）
- `target/original-FastMinecarts-1.0.0.jar` — 未内嵌依赖的原始产物，运行时缺 Kotlin 类会报 `NoClassDefFoundError`，**不要用**

## 兼容性

- Paper 及其分支，`api-version: 26.1.2`
- 声明 `folia-supported: true`，使用实体调度器，Paper / Folia 共用同一份实现
- 编译目标 Java 25

## 已知限制

- 补速是启发式实现，假定标准 1:1 坡道（45°）。长坡与弯道组合下效果需按实测调整 `boost-per-tick`。
- 补速不判断动力铁轨是否通电，只要求"当前处于加速路段"。
- 状态清理依赖 `VehicleExitEvent`。玩家异常离开座位（例如异常断线）可能留下条目，交由下次任务自检或 reload 清理。
- 内嵌的 Kotlin 运行时未做 relocation，与同样内嵌不同版本 Kotlin 的插件共存时可能冲突。
- `LICENSE` 未打进插件 jar 内，仅存在于源码仓库。

## 许可证

[MIT](LICENSE)。原始版权归 certainly1182（2023），本版本修改与新增部分的版权归 zerodudu（2026）。
