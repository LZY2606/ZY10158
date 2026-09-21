# CAN / ISO-TP 诊断帧分析与帧级回放

车载诊断分析服务：导入**脱敏**的 CAN 帧时间线，在简化 **ISO 15765-2（ISO-TP）**
规则下重组多帧载荷，识别 UDS 诊断请求/响应关系，跟踪 **ECU 代次（generation）**，
并对丢帧、重复帧、冲突帧、流控超时与“重放的旧响应”做明确分类。

系统只分析文件中的帧，**不连接真实车辆，也不发送任何总线消息**。

- Kotlin 服务（JDK 内置 `HttpServer`，无 Web 框架依赖）
- SQLite 持久化（原始帧永久只读；重组载荷、会话归属、人工判定分层版本化）
- 浏览器帧级回放页（虚拟时钟逐帧推进）
- 可控虚拟时钟：所有超时判定仅由捕获时间戳驱动，不使用墙钟

---

## 1. 快速开始

### 构建（跳过测试打包）

```bash
mvn -q -DskipTests package
```

### 演示（测试 + 启动服务）

```bash
mvn -q test && \
mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5358'
```

打开 <http://127.0.0.1:5358> 。

首次启动会自动导入 `fixtures/` 下三个演示捕获文件（导入以文件 SHA-256 幂等，
重复启动不会产生重复数据）。

其他启动参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--port` | `5358` | HTTP 端口 |
| `--db` | `data/cantp.sqlite` | SQLite 数据库路径 |
| `--fixtures <dir>` | `fixtures` | 启动时自动导入的目录 |
| `--no-fixtures` | — | 不自动导入 fixture |

---

## 2. 捕获文件格式

每行一个事件，`#` 开头为注释，空行忽略。时间戳统一为 `秒.微秒`（最多 6 位小数），
也兼容 candump 的 `(秒.小数)` 形式。

### 2.1 扩展格式（推荐）

```
<ts> <CAN_ID> <DIR> <hex...>
```

- `CAN_ID`：3 位十六进制为标准 11bit ID；8 位为扩展 29bit ID
- `DIR`：**数据流归属方向**，`REQ`（请求数据流）或 `RESP`（响应数据流）
- 数据：空格分隔的十六进制字节，单帧最多 64 字节（CAN FD）

```
0.000000 7E0 REQ 02 10 03
0.002000 7E8 RESP 02 50 03 00 00 00 00 00
```

### 2.2 candump 兼容格式

```
(1.500000) can0 7E8#0250030000000000
```

无显式方向时按简化 UDS 约定推断：偶数诊断 ID（如 `7E0`）为 `REQ`，对应 `+8`
（`7E8`）为 `RESP`。远程帧（`##R`）不参与重组。

### 2.3 ECU 重启边界

```
#RESET <ts> [id=0x7E0]
```

- 不带 `id`：对文件中出现过的所有 CAN ID 的两个方向都开启新代次
- 带 `id`：只对该 CAN ID 开启新代次
- 边界在“上一帧之后、下一帧之前”生效

### 2.4 方向（DIR）语义约定

方向表示**数据流归属**，不是物理收发引脚：

- 请求数据流：tester→ECU，FF/CF/SF 在 `7E0 REQ`，**FC 在 `7E8 REQ`**（FC 由接收方在对端 ID 上发送，但属于同一条请求数据流）
- 响应数据流：ECU→tester，FF/CF/SF 在 `7E8 RESP`，**FC 在 `7E0 RESP`**

页面上可对单帧修正角色；修正会作为人工层输入触发一次新的重组运行。

---

## 3. 简化 ISO-TP 协议子集

### 3.1 PCI 类型（首字节高 nibble）

| 高 nibble | 类型 | 含义 |
| --- | --- | --- |
| `0x0` | SF | 单帧；低 nibble=长度（`0` 为 CAN FD escape，长度在 byte1） |
| `0x1` | FF | 首帧；12bit 长度跨 byte0/byte1（全 0 为 32bit escape，byte2..5） |
| `0x2` | CF | 连续帧；低 nibble 为 SN（首个 CF 为 1，模 16） |
| `0x3` | FC | 流控；byte0 低 nibble=FS，byte1=BS，byte2=STmin |

FC 的 FS：`0=CTS`、`1=WAIT`（简化：仅允许一次，重新开 FC 窗）、
`2=ABORT`（立即中止）、其余为保留值（按中止处理）。

### 3.2 多帧状态机

```
IDLE ──FF──▶ WAIT_FC ──FC(CTS)──▶ WAIT_CF ──CF*──▶ COMPLETE
              │                    │
              │ FC=ABORT/保留      │ SN 冲突 / CR 超时 / RESET
              ▼                    ▼
          ABORTED            SN_CONFLICT / TIMEOUT_CF / ABORTED
              │
        FC 超时（100ms 默认）
              ▼
          TIMEOUT_FC
```

- 经典 CAN FF 携带 6 个服务字节（`byte2..byte7`），随后按 SN=1,2,… 接收 CF
- 每个 CF 携带至多 7 字节；最后一帧超出声明长度的字节视为填充（padding），保留证据后截断
- CF 允许**乱序暂存**：高 SN 先到时缓存，等缺口补齐后按序拼接；等待期间记录缺失 SN
- `BS != 0` 时收满一个 block 回到 `WAIT_FC`

### 3.3 半开时间窗（关键语义）

| 定时器 | 默认 | 区间 | 端点行为 |
| --- | --- | --- | --- |
| FC 超时 | `100 ms` | `[FF 时刻, FF + 100ms)` | FC 恰在 `t == deadline` 到达 = **超时** |
| CF/CR 间隙超时 | `250 ms` | `[上一被接受 CF, +250ms)` | CF 恰在 `t == deadline` 到达 = **超时，帧被拒绝** |

即时间窗是**半开区间**：`t < deadline` 在窗内，`t == deadline` 已超时。
fixture `02_timeout_boundaries.log` 精确构造了这两个端点。

### 3.4 重复帧、冲突与不可覆盖

- **同 SN 且载荷相同**：重传，不覆盖任何字节，挂 `retransmit` 证据并发 `CF_RETRANSMIT(INFO)`
- **同 SN 但载荷不同**：`SN_CONFLICT(ERROR)`，立即终局；**后来者绝不覆盖**已收字节，
  冲突帧内容不会出现在重组载荷中
- 重组完成后到达的重复/冲突 CF 同样处理：重传记为迟到证据，冲突把状态升级为
  `SN_CONFLICT` 但保留原载荷

### 3.5 不完整重组

超时/中止/冲突结束的重组**绝不伪装成成功响应**：

- 保留所有已收到的字节（部分载荷）
- `missing` 列出缺失的 SN 与期望字节数
- `status` 为 `TIMEOUT_FC / TIMEOUT_CF / SN_CONFLICT / ABORTED / INVALID`，`errorCode` 给出机器可读码

### 3.6 代次（ECU 重启）隔离

重组的隔离键为 **CAN ID + 方向 + 代次 + 序号 + 虚拟时钟**。

- `#RESET` 或人工边界使对应 CAN ID（或全部 ID）的代次 +1
- 未完成重组在重启时以 `RESET_DURING_REASSEMBLY` 中止
- **跨重启的帧绝不能拼成同一条载荷**；旧代次 FC/CF 无法唤醒新代次重组

---

## 4. 请求/响应配对

- 仅同 capture、同代次、CAN ID 互为 UDS 对端（`7E0 ↔ 7E8`，差值 8）可配对
- 服务识别：请求首字节 SID；正响应为 `SID+0x40`；负响应为 `7F SID NRC`
- 候选按 SID 匹配、完整性、时间邻近度打分
- 两个候选得分接近时标记为 `AMBIGUOUS`，UI 可**保留两个会话归属候选**或人工选定
- 同一响应的后续/迟到拷贝不会冒充新配对：
  - 已被更早请求占用 → `REPLAY_RESPONSE`
  - 无请求 → `ORPHAN_RESPONSE`
  - 无响应的请求 → `ORPHAN_REQUEST`

**为什么“最近请求”不够**：丢帧、重复、FC 超时、重启后重放会让朴素的最近邻配对错连；
本实现要求同代次 + 对端 ID + SID 语义匹配 + 时间序，并把歧义显式暴露给人工。

---

## 5. 异常 / 诊断分类

| 代码 | 级别 | 含义 |
| --- | --- | --- |
| `INVALID_PCI` | WARN | PCI 无法解析（空帧、保留类型、长度字段非法等） |
| `FLOW_CONTROL_TIMEOUT` | ERROR | 半开 FC 窗内未收到有效 FC |
| `CONSECUTIVE_FRAME_TIMEOUT` | ERROR | CF 间隙超时（含端点/之后到达的帧） |
| `FLOW_CONTROL_ABORT` | ERROR | FC=ABORT 或保留 FS |
| `SN_CONFLICT` | ERROR | 同 SN 载荷冲突，拒绝覆盖 |
| `CF_RETRANSMIT` | INFO | 同 SN 同载荷重传 |
| `CF_BEFORE_FLOW_CONTROL` | ERROR | 未收到 FC 即来 CF |
| `UNEXPECTED_FC` / `UNEXPECTED_CF` / `UNEXPECTED_FF` | WARN | 无匹配开放重组 |
| `RESET_DURING_REASSEMBLY` | —（ABORTED 状态） | 重启打断未完成重组 |
| `FRAME_REJECTED` | INFO | 帧被人工拒绝，不进入重组 |
| `GENERATION_BOUNDARY` | INFO | 代次切换事件（类型化，便于回放） |

每条诊断带帧序号、CAN ID、方向、代次与人类可读消息。

---

## 6. 分层与版本化（人工审核不被覆盖）

| 层 | 存储 | 可变性 |
| --- | --- | --- |
| 原始帧层 | `raw_frame` | **永久只读**，导入后不可修改 |
| 机器层 | `machine_run` + `assembly`/`diagnostic`/`req_resp_link`/`frame_assembly` | 每次重解析产生新 run；旧 run 保留 |
| 人工层 | `frame_adjudication` / `link_adjudication` / `reset_boundary(origin=human)` | 仅追加 + 乐观版本号 |

- 重新导入相同文件：按 SHA-256 **幂等**，直接复用既有 capture
- 新解析器重跑：产生新的 machine run，**不会覆盖任何已审核结果**；有效视图始终把人工层叠加在最新机器层之上
- 人工操作：拒绝污染帧、修正发送/接收角色、标记重启边界、选定响应归属、保留双候选
- 每次人工变更追加审计行（`adjudication_audit`）

### 重组作业的原子发布

一次重组运行把**全部**装配、帧映射、诊断、配对写入同一事务：

1. 插入 `machine_run(published=0)` 与所有派生行
2. 同事务内置 `published=1` 并更新 `capture.latest_run_id`
3. 提交——读者只能看到“全部状态 + 诊断一起就绪”的 run；失败则整体回滚

### 并发判定冲突不静默丢失

人工写入使用乐观版本号 `version`：

- 请求可带期望版本；版本不匹配时返回 `409`，操作**不生效**
- 冲突负载（类型、目标、期望/实际版本、请求体）写入 `adjudication_conflict`
- 页面“判定冲突”标签实时展示，保证任何冲突都有痕迹

---

## 7. HTTP API

| 方法 & 路径 | 说明 |
| --- | --- |
| `GET /api/health` | 健康检查 |
| `GET /api/captures` | 捕获列表 |
| `POST /api/captures?filename=...` | 导入（body=文件原文；哈希幂等），导入后自动重组 |
| `GET /api/captures/{id}?playhead=n` | 有效视图；`playhead` 只返回帧级回放可见部分 |
| `POST /api/captures/{id}/reassemble` | 重新跑重组（产生新 machine run） |
| `PUT /api/captures/{id}/frames/adjudication` | 帧判定（拒绝/原因/角色修正；可带 `version`） |
| `PUT /api/captures/{id}/links/adjudication` | 链接判定（选定请求/保留双候选；可带 `version`） |
| `POST /api/captures/{id}/reset-boundary` | 人工重启边界 `{afterSeq, canId?}` |
| `GET /api/captures/{id}/adjudications` | 人工判定清单（含版本） |
| `GET /api/captures/{id}/conflicts` | 持久化的并发冲突记录 |

### 帧判定示例

```bash
curl -X PUT http://127.0.0.1:5358/api/captures/3/frames/adjudication \
  -H 'Content-Type: application/json' \
  -d '{"frameSeq":1,"rejected":true,"rejectReason":"污染帧"}'
```

---

## 8. 页面功能

- **原始帧时间线**：只读展示序号、虚拟时钟、CAN ID、方向、代次、PCI 分类、数据、SN、所属重组
- **回放控制**：逐帧前进/后退、播放、速度、拖动 playhead；视图按虚拟时钟截断
- **重组状态机**：状态、已收/声明字节、部分载荷、缺失 SN、重传/padding/中止证据、错误码
- **请求/响应**：匹配状态（正响应/负响应 NRC/歧义/孤儿/重放）、候选评分、人工选定、保留双候选
- **ECU 代次**：每次重启边界产生的代次事件
- **错误诊断**：按级别/代码分类，随回放时间线出现
- 帧行点击可**拒绝帧**或**修正角色**；顶部按钮可在当前位置**标记重启边界**

---

## 9. Fixtures

| 文件 | 覆盖场景 |
| --- | --- |
| `fixtures/01_restart_and_replay.log` | 全局/局部重启代次、重启后旧响应重放、正/负响应(NRC)、孤儿响应、多帧 VIN |
| `fixtures/02_timeout_boundaries.log` | FC 超时端点（t==deadline）、CF 超时端点、窗内对照成功、FC.ABORT |
| `fixtures/03_duplicates_conflicts_incomplete.log` | 同载荷重传、同 SN 异载荷冲突、CF 乱序、尾部不完整（保留缺失与部分字节）、跨重启拼接防护、非法 PCI |

---

## 10. 存储模型概览

```
capture ─┬─ raw_frame            （原始帧，只读）
         ├─ reset_boundary       （fixture / human）
         └─ machine_run ─┬─ assembly
                         ├─ frame_assembly
                         ├─ diagnostic
                         └─ req_resp_link
frame_adjudication / link_adjudication   （人工层，版本化）
adjudication_audit / adjudication_conflict
```

数据库默认在 `data/cantp.sqlite`（WAL 模式），可通过 `--db` 修改。
