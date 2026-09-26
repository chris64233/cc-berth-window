# cc-berth-window

港口泊位与作业资源管理服务：船舶在潮汐与作业资源（泊位同时作业能力、全港拖轮）约束下申请泊位窗口。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1
- Spring Data JPA + H2（内存库，测试 `create-drop`，本地运行 `update`）

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 主要业务规则

### 资源模型

- **泊位（Berth）**：记录泊位代码、可接纳船型、最大吃水、同时作业能力（可同时停靠作业的船舶数）。
- **潮汐窗口（TideWindow）**：某泊位在 `[startTime, endTime]` 内可通行的最大吃水。
- **港口资源（PortResource，单行配置）**：全港可用拖轮总数，通过 `PUT /api/port/tugs` 设置。
- **申请（BerthApplication）**：包含业务号（幂等键）、船名、船型、吃水、预计到/离港时间、所需拖轮数量。
- **占用（BerthReservation）**：批准申请后生成的资源占用记录。

### 审批规则

1. 船舶吃水不得超过泊位最大吃水，且船型须与泊位可接纳船型一致。
2. 靠泊与离泊作业各占用固定 1 小时的作业时段（`CapacityChecks.TUG_OPERATION_DURATION`）。
   只有**完整覆盖**该作业时段、且允许吃水不小于船舶吃水的潮汐窗口，才能用于靠泊/离泊。
3. 批准时在同一事务内同时占用：
   - `[到港, 离港)` 的一段**连续泊位时间**（任意时刻该泊位在泊船舶数不超过同时作业能力）；
   - 靠泊与离泊两个作业时段的**拖轮能力**（任意时刻全港被占用拖轮总数不超过拖轮总量）。
4. 泊位或拖轮任一资源不足时**整体拒绝**：只落一条 `REJECTED` 申请记录（含拒绝原因），不产生任何占用记录，不存在"只占泊位"或"只占拖轮"的中间态。

### 幂等与并发

- **业务号幂等**：`business_no` 有数据库唯一约束。相同业务号重复提交：内容一致返回首次审批结果；内容不一致返回 `409 DUPLICATE_BUSINESS_NO`。
- **容量不超卖**：审批/改期事务内按固定顺序（港口资源行 → 泊位行 → 潮汐窗口行）加**数据库悲观写锁**，
  容量判断与占用写入在锁保护下完成，不依赖进程内判断；业务号唯一性由数据库唯一约束兜底。
- **防止旧判断结果**：申请实体带乐观锁版本号，改期可携带 `expectedVersion`，不一致返回 `409 STALE_APPLICATION`；
  潮汐窗口带乐观锁版本，且审批期间潮汐行被悲观锁定，潮汐数据的并发修改与审批在数据库层面串行；
  提交时发生的乐观锁冲突统一映射为 `409 STALE_APPLICATION`。

### 改期

- 仅 `APPROVED` 且**作业尚未开始**（当前时间早于占用开始时间）的申请可以整体改期。
- 新窗口需重新通过潮汐、泊位能力、拖轮余量全部校验（校验时排除自身原占用）。
- 改期在同一事务内更新占用记录：**成功后原窗口才随事务提交释放**；任一校验失败事务回滚，原安排完整保留，也不产生改期历史。

### 查询与错误响应

- 申请查询：`GET /api/applications/{businessNo}`
- 变更历史（创建/改期留痕）：`GET /api/applications/{businessNo}/history`
- 泊位占用查询：`GET /api/berths/{code}/reservations?from=&to=`
- 所有错误返回统一结构：`{"code", "message", "path", "timestamp"}`，
  由 `GlobalExceptionHandler` 集中处理（业务异常、参数校验、唯一约束冲突、乐观锁冲突、未知异常）。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/berths` | 创建泊位 |
| GET | `/api/berths/{code}` | 查询泊位 |
| POST | `/api/berths/{code}/tide-windows` | 新增潮汐窗口 |
| PUT | `/api/berths/{code}/tide-windows/{id}` | 修改潮汐窗口 |
| GET | `/api/berths/{code}/tide-windows` | 潮汐窗口列表 |
| GET | `/api/berths/{code}/reservations` | 泊位占用查询 |
| PUT | `/api/port/tugs` | 设置全港拖轮总量 |
| GET | `/api/port/tugs` | 查询拖轮总量 |
| POST | `/api/applications` | 提交申请并同步审批（幂等） |
| GET | `/api/applications/{businessNo}` | 查询申请 |
| PUT | `/api/applications/{businessNo}/reschedule` | 整体改期 |
| GET | `/api/applications/{businessNo}/history` | 变更历史 |

## 自动化测试

- `BerthApplicationServiceTest`：审批通过/潮汐不满足/泊位能力满/拖轮不足整体拒绝/无匹配泊位、
  业务号幂等、并发争抢泊位与拖轮不超卖、改期成功释放原窗口、改期失败保留原安排、
  过期版本拒绝、已开工禁止改期、已拒绝申请禁止改期。
- `ApiWebTest`：HTTP 全流程（建泊位 → 潮汐 → 拖轮 → 申请 → 占用查询 → 改期 → 历史）及统一错误响应格式。
