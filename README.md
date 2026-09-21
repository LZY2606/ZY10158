# CAN 诊断帧分析台（CAN Diagnostic Replay & ISO-TP Reassembly）

离线分析脱敏的 CAN 帧时间线：识别 UDS 诊断请求/响应，按简化 ISO 15765-2 规则重组多帧载荷，
处理 ECU 重启代次、丢帧、重复帧、冲突帧、流控超时与旧响应重放。系统**只分析导入文件中的帧**，
不连接真实车辆、不打开 CAN 接口、不发送任何总线消息。

- Kotlin 服务（JDK 内置 HTTP Server，零 Web 框架）
- SQLite 存储（`sqlite-jdbc`），分层版本化
- 浏览器帧级回放页面（原生 HTML/JS，无构建步骤）
- 可控虚拟时钟（系统时钟或确定性手动时钟）
- 覆盖重启、超时端点、重复/冲突帧、不完整载荷的 fixture 与 JUnit 测试

## 构建与运行

```bash
# 打包（跳过测试）
mvn -q -DskipTests package

# 演示：测试 + 启动（自动导入 fixtures/ 下五个场景）
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5358'
```

打开 <http://127.0.0.1:5358>。

可选参数：`--db <sqlite 路径>`（默认 `data/can-diag.sqlite`）、`--fixtures <目录>`、
`--no-fixtures`、`--host 127.0.0.1`。

## 捕获文件格式（`.cand`）

每行一条记录，`#` 开头为注释；时间戳必须非递减：

```
# restart [at=<ms>] [id=<hex CAN ID>] <备注…>     # 文件内重启指令（导入证据，非人工判定）
<offset_ms>  <CAN_ID(hex)>  <TX|RX>  <数据字节，空格分隔的 hex>
```

- `TX` = 诊断仪发送（请求方向），`RX` = 诊断仪收到（ECU 发送）。
- 经典 CAN，数据长度 1..8 字节。
- 示例：

```
100 7E0 TX 02 10 03 00 00 00 00 00     # TesterPresent 请求
110 7E8 RX 02 50 03 00 00 00 00 00     # 正响应
# restart at=500 id=7E8 ECU 上下电
```

导入以文件内容 **SHA-256 幂等**：同一内容（即使文件名不同）重复导入返回已有记录，不产生第二份数据。

## 协议子集

经典 CAN、11 位 ID、UDS 物理寻址。服务端只建模以下 ISO 15765-2 帧类型（PCI 高半字节）：

| PCI | 类型 | 语义 |
|---|---|---|
| `0x0` | SF 单帧 | 长度 = data[0] 低半字节（1..7），载荷从 data[1] 开始 |
| `0x1` | FF 首帧 | 总长 = `((data[0]&0x0F)<<8)|data[1]`，简化范围 8..4095；首帧携带 data[2..7] 共 6 字节 |
| `0x2` | CF 连续帧 | 4 位滚动序号（1..14，然后 0，再回到 1），载荷 data[1..7] |
| `0x3` | FC 流控 | 仅建模 CTS（FS=0）；WAIT/溢出等按意外帧报告 |

标准 UDS 物理 ID 映射：请求 `0x7E0..0x7E7` ↔ 响应 `0x7E8..0x7EF`（±8）。
FC 在数据的**反方向**传输，重组时按对端 CAN ID 路由到对应传输，不单独成一条消息。

请求/响应配对使用 UDS 服务标识：正响应 SID = 请求 SID + `0x40`；负响应为
`7F <请求SID> <NRC>`。

## 超时语义（半开时间窗）

所有时间都取自捕获文件中的相对时间戳（虚拟时钟），与墙钟无关。窗口为**半开 `[0, timeout]`**：

- **恰好**落在 `起始 + N_Bs / N_Cr` 时刻的帧仍然准时；只有**严格晚于**该时刻才超时。
- `N_Bs = 100 ms`：FF → FC 等待。超时产生 `ISO_TIMEOUT_N_BS`，传输结束为 `TIMEOUT`。
- `N_Cr = 100 ms`：相邻 CF 之间等待。超时产生 `ISO_TIMEOUT_N_CR`，保留已收字节与缺失序号。
- `P2 = 500 ms`：请求结束 → 响应开始的配对窗口，同样半开；窗口内才可能配对。

在捕获结束（时间线最后一帧）时仍未闭合的传输：若已超时按超时处理；若仍在窗口内则标记
`INCOMPLETE`，绝不伪装成成功响应。

## ECU 代次与重启边界

同一 CAN ID 可在 ECU 重启后开始**新一代次（generation）**，代次按 CAN ID 独立计数。

- 边界来源：文件 `# restart` 指令（L0 证据）与页面/API 的人工标记（L2 判定）。
- 带 `id=` 的边界只作用于该 CAN ID；不带 `id=` 的全总线边界作用于所有诊断 ID。
- 边界时刻的帧属于新代次（半开 `[boundary, +∞)`）。
- 同一 `(offset, scope)` 的文件指令与人工标记视为同一事件，不会重复 bump。
- **跨重启的帧永远不能拼成一条载荷**：重组状态机按 `(CAN ID, 方向, 代次)` 完全隔离；
  被重启截断的传输保留为 `INCOMPLETE` 并产生 `ISO_CROSS_RESTART_TRUNCATION`，重启后的孤立 CF
  记为意外帧。
- 配对也限定同代次：旧代次的响应即使在 P2 内也不会与新代次请求配对。

## 重复帧、冲突帧与不完整载荷

- **重复（重传）**：同一 CF 序号再次到达且载荷相同 → `ISO_DUPLICATE_RETRANSMIT`（INFO），
  保留证据但不重复追加字节。
- **冲突**：同一 CF 序号载荷不同 → `ISO_SEQUENCE_CONFLICT`（ERROR），消息置 `CONFLICT`，
  **后来者不覆盖**先到数据。
- **丢帧**：期望序号未到而后续序号到达 → 保留收到的字节，记录缺失序号列表（4 位序号值），
  消息为 `INCOMPLETE`/`CONFLICT`，不会以截断数据冒充成功。
- 单帧/首帧在传输未闭合时插入会中止当前传输并记录意外帧。

## 请求/响应关系与重放

按响应到达顺序处理：一条请求被一个更早到达的响应消费后，不能被后续响应再次认领，
从而抵制冷启动后重放的旧响应。

- 窗口内同代次、SID 匹配、唯一候选 → 直接配对。
- 窗口内有 **2 个候选请求**（重复请求/歧义）→ 机器默认暂定最近者，同时在响应上**保留两个
  会话归属候选**，产生 `PAIR_AMBIGUOUS` 供人工仲裁；可选择其一或**保留两个候选**（不自动配对）。
- 窗口内无候选，但同代次历史上出现过同 SID 请求 → `PAIR_REPLAY_SUSPECT`（陈旧/重放响应，不配对）。
- 同代次从未出现过该 SID 请求 → `PAIR_ORPHAN_RESPONSE`。
- 请求结束后超过 P2 无响应 → `PAIR_UNANSWERED_REQUEST`。

## 分层与版本化

- **L0 原始层（永久只读）**：`raw_frames` 与 `restart_directives` 只随导入写入，之后永不修改。
- **L1 解析层（可重算、版本化）**：`parser_runs` / `messages` / `diagnostics`。每次重跑生成新的
  run 编号；一个 run 的所有消息与诊断在**同一事务**内先以 `published=0` 落盘，全部成功后才翻转为
  `published=1`——读者永远看不到发布了一半的作业。
- **L2 审核层（人工判定、版本化、不被重跑覆盖）**：
  - 帧判定 `frame_reviews`：拒绝污染帧（重排时排除但 L0 保留）、方向修正、备注；
  - 会话归属 `attribution_reviews`：歧义候选选择或保留双候选；
  - 重启标记 `restart_marks`。
  - 每次写入版本号 +1，并提升捕获的 `review_version`。写入采用**乐观并发**：客户端带
    `expectedVersion`，与当前版本不符时返回 **HTTP 409**，并发判定冲突绝不静默丢失。
  - 重跑只从 L0 + 当前 L2 输入重新计算 L1；`localKey`
    （`m<CANID>-<方向>-g<代次>-f<首帧seq>`）在相同输入下稳定，因此已审核的归属在重跑后继续生效。

## 异常分类

诊断条目带严重级别（INFO / WARNING / ERROR）与代码：

| 代码 | 含义 |
|---|---|
| `ISO_TIMEOUT_N_BS` / `ISO_TIMEOUT_N_CR` | 流控 / 连续帧半开窗超时 |
| `ISO_DUPLICATE_RETRANSMIT` | 同序号同载荷重传（保留证据） |
| `ISO_SEQUENCE_CONFLICT` | 同序号不同载荷（冲突，不覆盖） |
| `ISO_UNEXPECTED_FRAME` | 无上下文的 CF/FC、迟到 FC、交错 SF/FF、缺槽到达等 |
| `ISO_CROSS_RESTART_TRUNCATION` | 多帧传输被 ECU 重启截断 |
| `ISO_MALFORMED_FRAME` / `ISO_LENGTH_MISMATCH` | PCI 无法分类 / 声明长度与收到字节不符 |
| `PAIR_ORPHAN_RESPONSE` | 无任何匹配请求的响应 |
| `PAIR_REPLAY_SUSPECT` | 无开放请求的陈旧响应（疑似重放） |
| `PAIR_AMBIGUOUS` | 一个响应匹配多个请求，需/已人工归属 |
| `PAIR_UNANSWERED_REQUEST` | 请求超过 P2 无响应 |
| `FRAME_REJECTED` | 帧被人工拒绝，已从重组排除（L0 保留） |

## HTTP API

| 方法与路径 | 说明 |
|---|---|
| `GET /api/captures` | 捕获列表（含 run 摘要） |
| `POST /api/captures` | 导入 `{name, content}`；同 SHA-256 返回 `idempotentHit:true` |
| `GET /api/captures/{id}` | 帧（含有效方向/代次/审核）、边界、最新 run 的消息与诊断、歧义视图 |
| `POST /api/captures/{id}/rerun` | 基于当前 L2 重跑并原子发布新 run |
| `GET/POST /api/captures/{id}/restart-marks`、`DELETE …/restart-marks/{mid}` | 人工重启边界 |
| `POST /api/frames/{fid}/review` | 帧判定（`expectedVersion` 做乐观锁，冲突 409） |
| `POST /api/captures/{id}/attribution` | 歧义归属：`choiceLocalKey` 或 `keepBoth`，同样带版本 |

## 页面功能

- 原始帧时间线（L0 只读标识）、按虚拟时间轴播放 / 单帧 / 拖动回放；
- 重组消息卡片：状态徽标、已收/期望字节、缺失 CF 序号、载荷、配对关系与候选；
- 错误诊断面板（级别/代码/时间/代次/详情）；
- 请求⇄响应关系视图，按代次分组显示；
- 标记重启边界、帧判定弹窗（拒绝/方向修正/备注/版本与 409 提示）、歧义归属弹窗（双候选/保留两者）。

## Fixtures（`fixtures/*.cand`）

1. `01_restart_generations` — 重启后同 ID 新代次，旧响应在新代次成为孤儿；
2. `02_timeout_endpoints` — FC/CF 恰好 100ms 准时与 101ms 超时（N_Bs、N_Cr 端点）；
3. `03_duplicate_conflict` — 同载荷重传 vs 同序号异载荷冲突；
4. `04_incomplete_cross_restart` — 缺失 CF 序号保留、重启截断不跨代拼接；
5. `05_replay_ambiguous_pairing` — 未应答请求、重复请求双候选、陈旧重放、纯孤儿响应。

## 存储位置与离线性质

默认 SQLite 文件在 `data/can-diag.sqlite`（可用 `--db` 更改）。程序全程只读写本地文件与本地
HTTP，不进行任何车辆总线 I/O。
