# cc-berth-window

港口泊位窗口服务：在**潮汐**与**作业资源（泊位、拖轮）**约束下，受理船舶泊位窗口申请、
审批占用、整体改期、取消与**两艘已批准船舶互换泊位时段**，并提供资源占用与变更历史查询。

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
| `SwapProposal` 互换方案 | 冻结互换双方的泊位、时段、申请版本、潮汐版本签名与拖轮安排，记录确认状态与失败原因 |
| `ChangeHistory` 变更历史 | 提交 / 审批 / 改期 / 取消 / 互换成功 / 互换失败记录及明细 |

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
7. **泊位时段互换（两阶段：冻结 → 确认）**：两艘**已批准且未开始作业**的船舶可以互换泊位时段。
   - **冻结（freeze）**：方案 `SwapProposal` 冻结双方的泊位、时段、申请 `@Version`、
     所需泊位类型的**全部潮汐窗口版本签名**（`id:version` 集合）以及拖轮安排。
   - **确认（confirm）在单事务内按交换后的条件重新校验**：A 进入 B 的泊位与时段、B 进入 A 的，
     重新检查①泊位类型/接纳船型/最大吃水、②泊位同时作业容量、③靠离泊时刻潮汐水深、
     ④拖轮在交换后各时刻的余量（同一艘拖轮在双方**不同时刻**可以复用，同一时刻不能协助两艘船）。
     **互换不是交换两个时间字段**——任一方在新时段/新泊位不满足，整笔交换不做任何变更，
     两份原批准安排原样保留，方案落库 `FAILED` 并记录失败错误码与原因。
   - **原子切换**：全部满足后在同一事务先删后插双方泊位占用与拖轮安排，再更新两份申请，
     由唯一约束 `uk_occupation_application`、`uk_tug_time` 兜底，绝不出现只换一半。
8. **互换失效（版本变化）**：确认时若任一方申请版本已变化（**改期或取消**）、申请已非批准态、
   当前泊位/时段与冻结快照不一致，或**潮汐资料被更新**（版本签名变化、窗口增删），
   方案判定为失效（`SWAP_PROPOSAL_STALE`），拒绝使用旧判断；确认期间潮汐被并发修改时
   OPTIMISTIC 锁在提交点复验失败，整笔回滚。
9. **取消**：已批准且未开始作业的申请可取消，单事务释放泊位与拖轮占用；重复取消幂等。
   取消会推进申请版本，使冻结于此前的互换方案失效。
10. **互换业务幂等**：`swap_proposal.proposal_no` 数据库唯一约束；同号同参与方重复冻结返回同一方案，
    同号不同参与方冲突。方案行以 `SELECT … FOR UPDATE` 加锁，**同一方案的并发确认被数据库串行化**：
    只有一笔真正执行交换，其余返回相同结论（成功或同一失败原因），不会重复交换或重复释放资源；
    失败历史只在首次确认时写入一次。

### 容量与唯一性为何不只靠进程内判断

- **泊位容量**：审批/改期对泊位行执行 `SELECT … FOR UPDATE`（悲观写锁），
  同一泊位的并发容量判断在数据库层串行化；始终按 id 升序加锁以避免死锁。
- **拖轮容量**：锁定拖轮池行做容量判断，并由数据库唯一约束
  `uk_tug_time(tug_id, action_time)` 作最终硬保护——并发争抢同一艘拖轮同一时刻时，
  后提交者必然触发约束冲突并整体回滚。
- **幂等**：`berth_application.application_no` 数据库唯一约束。
- **单一占用**：`uk_occupation_application(application_id)` 保证一次审批至多一条泊位占用。
- **潮汐/申请一致性**：潮汐窗口与申请实体均带 `@Version`，审批期间数据变化即乐观锁失败。
- **互换不重复交换**：方案行 `SELECT … FOR UPDATE` 串行化同方案的并发确认；
  双方申请行、涉及泊位行均按 id 升序加锁避免死锁；拖轮池行锁 + `uk_tug_time` 兜底交换后的并发分配。
- **互换幂等**：`swap_proposal.proposal_no` 唯一约束（独立事务插入暴露冲突后重读）。

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
| POST | `/api/applications/{applicationNo}/cancel` | 取消（释放占用，重复取消幂等） |

泊位时段互换：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/swaps` | 冻结互换方案（按 `proposalNo` 幂等），请求体含 `proposalNo/applicationANo/applicationBNo` |
| POST | `/api/swaps/{proposalNo}/confirm` | 确认互换（交换后重校验，全部满足才原子切换；重复确认幂等） |
| GET  | `/api/swaps`、`/api/swaps/{proposalNo}` | 查询方案（含 `status/failureCode/failureReason` 冻结快照） |

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
```

两艘已批准船舶互换泊位时段（先冻结、后确认）：

```bash
# 两艘已批准船 APP-A（泊位 B1，10:00-12:00）、APP-B（泊位 B2，14:00-16:00）
# 1) 冻结方案：快照双方泊位/时间/潮汐版本/拖轮安排
curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' -d '{
  "proposalNo":"SWAP-001","applicationANo":"APP-A","applicationBNo":"APP-B"}'

# 2) 确认：在交换后的泊位与时段下重新校验泊位条件、潮汐、拖轮，
#    全部满足才原子切换两份安排；任一不满足返回 4xx 且双方原安排保持可用
curl -X POST localhost:8080/api/swaps/SWAP-001/confirm

# 冻结后任一方改期/取消，或潮汐预报更新 -> 确认返回 SWAP_PROPOSAL_STALE
# 重复确认同一方案幂等：已成功返回交换后安排，已失败返回相同错误码与原因
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
| `APPLICATION_ALREADY_APPROVED` / `APPLICATION_NOT_APPROVED` | 409 | 重复审批 / 未批准先改期或互换 |
| `APPLICATION_CANCELLED` | 409 | 已取消的申请再审批 |
| `NO_MATCHING_BERTH` | 422 | 无类型/船型/吃水匹配的泊位（含互换后对方泊位不满足） |
| `BERTH_CAPACITY_EXCEEDED` | 409 | 泊位同时作业能力已满（含互换后新时段容量不足） |
| `INSUFFICIENT_TUGS` | 409 | 拖轮余量不足（含互换后新时刻、并发争抢唯一约束兜底） |
| `TIDE_WINDOW_UNAVAILABLE` | 422 | 靠/离泊时刻无满足吃水的潮汐窗口（含互换后新时刻） |
| `WINDOW_ALREADY_STARTED` | 409 | 已开始作业的申请不能改期/取消/互换 |
| `JUDGMENT_STALE` | 409 | 审批依据的潮汐/申请数据在提交前已变化 |
| `DUPLICATE_BUSINESS_KEY` | 409 | 业务号重复且内容不一致 / 唯一约束冲突 |
| `SWAP_NOT_FOUND` | 404 | 互换方案不存在 |
| `SWAP_SAME_APPLICATION` | 400 | 互换双方是同一份申请 |
| `SWAP_ALREADY_FINALIZED` | 409 | 方案已落定（幂等路径的内部标识） |
| `SWAP_PROPOSAL_STALE` | 409 | 冻结后申请改期/取消或潮汐资料更新，方案版本失效 |

## 自动化测试

`./mvnw clean test` 共 55 个测试：

- `BerthWindowBusinessRulesTest`（18 个）：幂等提交、泊位+拖轮原子占用、任一不足整体拒绝、
  潮汐吃水/时刻约束、多泊位选择、改期成功释放旧窗口、改期失败（潮汐/拖轮/泊位）保留原安排、
  查询与参数校验。
- `BerthWindowConcurrencyTest`（6 个，真实多线程）：5 路并发争抢容量为 2 的泊位、
  3 路并发超订拖轮（验证 `uk_tug_time` 兜底）、潮汐在审批读窗口后被修改触发乐观锁失败、
  同申请并发审批恰好成功一次、同业务号并发提交只生成一条申请。
- `BerthSwapBusinessRulesTest`（16 个）：不同泊位/同泊位互换成功并原子交换泊位、时间与拖轮；
  交换后潮汐/泊位类型/吃水/容量/拖轮不足时双方原安排保留且方案落库 `FAILED`；
  改期、取消、潮汐更新后旧方案版本失效；冻结与确认的业务幂等；互换前后安排与失败原因入历史。
- `BerthSwapConcurrencyTest`（4 个，真实多线程）：4 路并发确认同一方案只交换一次、
  不重复交换或释放资源；确认与取消/改期竞速时只可能线性化为「交换成功」或「方案失效」、
  占用始终一致；同方案号并发冻结只生成一条方案。
- `ApiContractTest`（10 个，MockMvc）：申请/审批/改期/查询、互换冻结/确认成功/确认失败/
  同申请拒绝的接口契约与统一错误响应结构。
