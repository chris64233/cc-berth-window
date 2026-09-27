# cc-berth-window

港口泊位窗口服务：在**潮汐**与**作业资源（泊位、拖轮）**约束下，受理船舶泊位窗口申请、
审批占用、整体改期、批准后取消以及**两艘已批准船舶互换泊位时段**，
并提供资源占用与变更历史查询。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1、Spring Data JPA、Bean Validation
- H2 数据库（内存模式，JPA 持久化）

## 快速开始

运行测试：

    ./mvnw clean test

启动服务（默认 `http://localhost:8080`）：

    ./mvnw spring-boot:run

## 领域模型

| 实体 | 说明 |
| --- | --- |
| `Berth` 泊位 | 泊位类型、可接纳船型集合、最大吃水、同时作业能力（可同时挂靠的船舶数） |
| `Tug` 拖轮 | 可调度的拖轮资源，一艘拖轮同一时刻只能协助一艘船 |
| `TideWindow` 潮汐窗口 | 某泊位类型在 `[start,end]` 时段可保证的最小水深，带乐观锁版本号 |
| `BerthApplication` 申请 | 业务申请号、船型/吃水、预计靠离泊时间、所需泊位类型、所需拖轮数、状态与版本号 |
| `BerthOccupation` 泊位占用 | 一次批准对应一段连续泊位时间（半开区间 `[start,end)`） |
| `TugAssignment` 拖轮占用 | 一次靠泊/离泊对某艘拖轮在某一时刻的占用 |
| `BerthSwapProposal` 互换方案 | 业务号、双方申请、状态（PROPOSED/CONFIRMED/FAILED）、冻结快照与失败原因 |
| `BerthSwapSide` 互换方快照 | 一方互换前/后泊位与时段、申请与目标泊位资料版本、拟用拖轮集合 |
| `TideWindowSnapshotEntity` 潮汐快照 | 冻结的潮汐窗口内容与版本号 |
| `ChangeHistory` 变更历史 | 提交 / 审批 / 改期 / 取消 / 互换提议 / 互换成功 / 互换失败记录及明细 |

## 主要业务规则

1. **泊位准入**：申请只能安排到「泊位类型匹配、接纳该船型、最大吃水不小于船舶吃水」的泊位；
   多个候选泊位按 id 顺序选择第一个在申请时段内仍有同时作业余量的泊位。
2. **潮汐约束**：只有**靠泊时刻与离泊时刻都落在水深不小于船舶吃水的潮汐窗口内**时才能批准。
   潮汐窗口以 **OPTIMISTIC 乐观锁**读取，审批事务提交时复验版本号——审批期间潮汐数据被修改，
   本次审批整体失败（`JUDGMENT_STALE`），不允许使用旧判断结果。
3. **泊位 + 拖轮一次性原子占用**：审批在单个数据库事务内完成「全部检查 → 全部占用」。
   拖轮在靠泊、离泊两个时刻各需 `requiredTugs` 艘且必须为同一批可用拖轮；
   **任一资源不足整体拒绝**，不会出现只预留泊位或只预留拖轮的中间状态。
4. **申请业务号幂等**：`application_no` 有数据库唯一约束。同号同内容重复提交返回同一申请；
   同号不同内容返回冲突；并发提交由独立插入事务 + 唯一约束兜底，保证只生成一条记录。
5. **改期（先分配后释放）**：仅已批准且**尚未开始作业**的申请可改期。
   改期时先用新时间完成潮汐、泊位容量、拖轮的全部校验，全部满足后才在同一事务内
   删除旧占用并写入新占用；**失败则事务回滚，原泊位时间与拖轮安排原样保留**。
6. **重叠判定**：泊位时间采用半开区间 `[start,end)`，首尾相接的两段占用不算重叠。
7. **泊位时段互换（先冻结、后确认，失败保留原安排）**：两艘已批准且未开始作业的船舶可发起
   互换。互换**不是交换两个时间字段**——提议时冻结双方泊位、时间、潮汐资料（含版本号）
   与拖轮安排，并立即按交换后的条件重新做泊位准入、潮汐、泊位容量与拖轮校验；
   确认时在独立事务内依据冻结快照**重新校验**，全部满足才在同一事务内
   「删旧占用 → 写新占用 → 更新两份申请」原子切换；**任一方不满足或依据数据已变化，
   则确认整体失败，方案置 FAILED 并记录失败原因，原两份批准安排与资源占用原样保留**。
8. **互换方案随版本变化失效**：方案冻结后，任一方改期、取消（均提升申请 `@Version`），
   或涉及泊位类型的任一潮汐窗口新增/删除/更新，确认时快照版本比对失败（`SWAP_STALE`），
   不会基于旧资料完成交换。
9. **取消**：已批准且未开始作业的申请可取消，单事务内释放泊位占用与拖轮安排（状态
   CANCELLED，不可重复审批）；取消同样令冻结过该申请的在途互换方案失效。

### 容量与唯一性为何不只靠进程内判断

- **泊位容量**：审批/改期/互换对泊位行执行 `SELECT … FOR UPDATE`（悲观写锁），
  同一泊位的并发容量判断在数据库层串行化；涉及两个泊位/两份申请时始终按 id 升序加锁以避免死锁。
- **拖轮容量**：锁定拖轮池行做容量判断，并由数据库唯一约束
  `uk_tug_time(tug_id, action_time)` 作最终硬保护——并发争抢同一艘拖轮同一时刻时，
  后提交者必然触发约束冲突并整体回滚。
- **幂等**：`berth_application.application_no` 与
  `berth_swap_proposal.swap_no` 数据库唯一约束。
- **单一占用**：`uk_occupation_application(application_id)` 保证一次审批至多一条泊位占用。
- **潮汐/申请一致性**：潮汐窗口、申请、泊位均带 `@Version`，审批/改期/互换确认期间
  数据变化即乐观锁失败或快照版本不一致；互换还会把冻结时的潮汐窗口内容与版本号
  持久化为快照，确认时逐项比对（含新增窗口检测）。
- **互换确认并发**：先对方案行加写锁、再按 id 升序锁两份申请行，
  与并发的改期/取消/再次确认在数据库层串行；终态方案（CONFIRMED/FAILED）的重复确认
  直接返回既有结果，不会重复交换或重复释放资源。确认失败留痕在失败事务回滚后
  以独立事务写入，避免与回滚一起丢失。

## REST API

基础资源（写接口用于初始化数据 / 维护潮汐预报）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/resources/berths` | 新建泊位 |
| GET  | `/api/resources/berths`、`/api/resources/berths/{id}` | 查询泊位 |
| POST | `/api/resources/tugs` | 新建拖轮 |
| GET  | `/api/resources/tugs` | 查询拖轮 |
| POST | `/api/resources/tide-windows` | 新建潮汐窗口 |
| GET  | `/api/resources/tide-windows?berthType=` | 查询潮汐窗口 |
| PATCH| `/api/resources/tide-windows/{id}` | 更新水深（可带 `expectedVersion` 做并发控制） |

申请与审批：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/applications` | 提交申请（按 `applicationNo` 幂等） |
| GET  | `/api/applications`、`/api/applications/{applicationNo}` | 查询申请 |
| POST | `/api/applications/{applicationNo}/approve` | 审批（泊位与拖轮原子占用） |
| POST | `/api/applications/{applicationNo}/reschedule` | 整体改期（成功才释放原窗口） |
| POST | `/api/applications/{applicationNo}/cancel` | 取消批准安排（释放泊位/拖轮，并使在途互换失效） |

两艘已批准船舶互换泊位时段：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/swaps` | 提议互换（按 `swapNo` 幂等；冻结双方泊位/时间/潮汐/拖轮并按交换后条件预校验） |
| POST | `/api/swaps/{swapNo}/confirm` | 确认互换（快照重校验通过才原子切换；失败置 FAILED 且原安排保留） |
| GET  | `/api/swaps`、`/api/swaps/{swapNo}` | 查询互换方案（含状态与失败原因） |

查询：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/occupations?berthId=&applicationNo=` | 泊位占用 |
| GET | `/api/tug-assignments?applicationNo=` | 拖轮安排 |
| GET | `/api/change-history?applicationNo=` | 变更历史 |

### 典型流程

```bash
# 1) 初始化：1 个集装箱泊位（最大吃水 13m，同时作业 2 艘）、2 艘拖轮、潮汐窗口
curl -X POST localhost:8080/api/resources/berths -H 'Content-Type: application/json' -d '{
  "code":"B1","name":"1号泊位","berthType":"CONTAINER",
  "acceptedVesselTypes":["FEEDER"],"maxDraft":13.00,"simultaneousCapacity":2}'
curl -X POST localhost:8080/api/resources/tugs -H 'Content-Type: application/json' \
  -d '{"code":"T1","name":"拖轮1"}'
curl -X POST localhost:8080/api/resources/tide-windows -H 'Content-Type: application/json' -d '{
  "berthType":"CONTAINER","windowStart":"2026-10-01T09:00:00Z",
  "windowEnd":"2026-10-01T21:00:00Z","availableDepth":12.00}'

# 2) 提交申请（吃水 10m，需 2 艘拖轮）
curl -X POST localhost:8080/api/applications -H 'Content-Type: application/json' -d '{
  "applicationNo":"APP-001","vesselCode":"V-100","vesselType":"FEEDER",
  "eta":"2026-10-01T10:00:00Z","etd":"2026-10-01T20:00:00Z",
  "draft":10.00,"requiredBerthType":"CONTAINER","requiredTugs":2}'

# 3) 审批（泊位时间 + 靠/离泊拖轮一次性占用）
curl -X POST localhost:8080/api/applications/APP-001/approve

# 4) 作业开始前改期
curl -X POST localhost:8080/api/applications/APP-001/reschedule \
  -H 'Content-Type: application/json' \
  -d '{"newEta":"2026-10-02T10:00:00Z","newEtd":"2026-10-02T20:00:00Z"}'

# 5) 两艘已批准船舶互换泊位时段（先提议冻结、再确认）
curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' -d '{
  "swapNo":"SWAP-001","applicationANo":"APP-001","applicationBNo":"APP-002"}'
curl -X POST localhost:8080/api/swaps/SWAP-001/confirm
# 任一方改期/取消或潮汐更新后再确认 -> 409 SWAP_STALE，原两份安排保留

# 6) 批准后、作业开始前取消（释放占用，并使在途互换方案失效）
curl -X POST localhost:8080/api/applications/APP-001/cancel
```

## 统一错误响应

所有错误均返回如下结构（`@RestControllerAdvice` 统一处理，含参数校验、乐观锁与唯一约束冲突）：

```json
{
  "timestamp": "2026-09-27T00:00:00Z",
  "code": "INSUFFICIENT_TUGS",
  "status": 409,
  "message": "靠泊 …、离泊 … 所需的 2 艘拖轮无法同时满足…，申请整体拒绝",
  "details": []
}
```

| 错误码 | HTTP | 触发场景 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 参数/时间区间非法 |
| `APPLICATION_NOT_FOUND` / `BERTH_NOT_FOUND` / `RESOURCE_NOT_FOUND` | 404 | 资源不存在 |
| `APPLICATION_ALREADY_APPROVED` / `APPLICATION_NOT_APPROVED` | 409 | 重复审批 / 未批准先改期 |
| `NO_MATCHING_BERTH` | 422 | 无类型/船型/吃水匹配的泊位 |
| `BERTH_CAPACITY_EXCEEDED` | 409 | 泊位同时作业能力已满 |
| `INSUFFICIENT_TUGS` | 409 | 拖轮余量不足（含并发争抢唯一约束兜底） |
| `TIDE_WINDOW_UNAVAILABLE` | 422 | 靠/离泊时刻无满足吃水的潮汐窗口 |
| `WINDOW_ALREADY_STARTED` | 409 | 已开始作业的申请不能改期 / 取消 / 互换 |
| `JUDGMENT_STALE` | 409 | 审批依据的潮汐数据在提交前已变化 |
| `DUPLICATE_BUSINESS_KEY` | 409 | 业务号重复且内容/双方不一致 / 唯一约束冲突 |
| `SWAP_NOT_FOUND` | 404 | 互换方案不存在 |
| `SWAP_INVALID_PAIR` | 422 | 互换双方相同，或至少一方未批准/已取消/无泊位安排 |
| `SWAP_STALE` | 409 | 方案冻结后申请改期/取消、潮汐资料更新等导致版本变化，或对已终结方案再次操作 |

## 自动化测试

`./mvnw clean test` 共 54 个测试：

- `BerthWindowBusinessRulesTest`（18 个）：幂等提交、泊位+拖轮原子占用、任一不足整体拒绝、
  潮汐吃水/时刻约束、多泊位选择、改期成功释放旧窗口、改期失败（潮汐/拖轮/泊位）保留原安排、
  查询与参数校验。
- `BerthSwapBusinessRulesTest`（16 个）：提议冻结双方泊位/时间/潮汐版本/拖轮并按交换后条件
  预校验（泊位准入、潮汐、容量、拖轮不满足即拒绝）、同号同双方幂等/同号不同双方冲突、
  确认成功原子互换泊位时段与拖轮、确认幂等不重复交换、确认失败（拖轮被第三方占走）原两份
  安排与占用 id 原样保留并记录失败原因、改期/取消/潮汐更新后确认判 `SWAP_STALE`、
  取消释放占用、互换前后安排与失败原因历史留痕。
- `BerthWindowConcurrencyTest`（6 个，真实多线程）：5 路并发争抢容量为 2 的泊位、
  3 路并发超订拖轮（验证 `uk_tug_time` 兜底）、潮汐在审批读窗口后被修改触发乐观锁失败、
  同申请并发审批恰好成功一次、同业务号并发提交只生成一条申请。
- `BerthSwapConcurrencyTest`（3 个，真实多线程）：4 路并发确认同一方案恰好交换一次且不重复
  释放资源（占用/拖轮艘次/成功历史各仅一份）、确认与改期竞速下最终状态始终自洽（要么交换
  落地要么改期生效，绝无半交换）、同 `swapNo` 并发提议只生成一份方案。
- `ApiContractTest`（10 个，MockMvc）：申请/审批/改期/取消/互换提议/互换确认/互换失败/查询
  接口契约与统一错误响应结构。
