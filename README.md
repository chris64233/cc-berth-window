# cc-berth-window

港口泊位窗口服务：在**潮汐**与**作业资源（泊位、拖轮）**约束下，受理船舶泊位窗口申请、
审批占用与整体改期，并提供资源占用与变更历史查询。

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
| `ChangeHistory` 变更历史 | 提交 / 审批 / 改期记录及明细 |

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

### 容量与唯一性为何不只靠进程内判断

- **泊位容量**：审批/改期对泊位行执行 `SELECT … FOR UPDATE`（悲观写锁），
  同一泊位的并发容量判断在数据库层串行化；始终按 id 升序加锁以避免死锁。
- **拖轮容量**：锁定拖轮池行做容量判断，并由数据库唯一约束
  `uk_tug_time(tug_id, action_time)` 作最终硬保护——并发争抢同一艘拖轮同一时刻时，
  后提交者必然触发约束冲突并整体回滚。
- **幂等**：`berth_application.application_no` 数据库唯一约束。
- **单一占用**：`uk_occupation_application(application_id)` 保证一次审批至多一条泊位占用。
- **潮汐/申请一致性**：潮汐窗口与申请实体均带 `@Version`，审批期间数据变化即乐观锁失败。

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
| `WINDOW_ALREADY_STARTED` | 409 | 已开始作业的申请不能改期 |
| `JUDGMENT_STALE` | 409 | 审批依据的潮汐/申请数据在提交前已变化 |
| `DUPLICATE_BUSINESS_KEY` | 409 | 业务号重复且内容不一致 / 唯一约束冲突 |

## 自动化测试

`./mvnw clean test` 共 32 个测试：

- `BerthWindowBusinessRulesTest`（18 个）：幂等提交、泊位+拖轮原子占用、任一不足整体拒绝、
  潮汐吃水/时刻约束、多泊位选择、改期成功释放旧窗口、改期失败（潮汐/拖轮/泊位）保留原安排、
  查询与参数校验。
- `BerthWindowConcurrencyTest`（6 个，真实多线程）：5 路并发争抢容量为 2 的泊位、
  3 路并发超订拖轮（验证 `uk_tug_time` 兜底）、潮汐在审批读窗口后被修改触发乐观锁失败、
  同申请并发审批恰好成功一次、同业务号并发提交只生成一条申请。
- `ApiContractTest`（7 个，MockMvc）：申请/审批/改期/查询接口契约与统一错误响应结构。
