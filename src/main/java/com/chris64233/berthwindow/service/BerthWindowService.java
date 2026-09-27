package com.chris64233.berthwindow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthActionType;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.HistoryAction;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.domain.Tug;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.BerthRepository;
import com.chris64233.berthwindow.repo.ChangeHistoryRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.repo.TugRepository;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 泊位窗口核心业务服务。
 *
 * <p>容量与唯一性保证（不依赖进程内判断）：</p>
 * <ul>
 *   <li>泊位：对泊位行加 {@code SELECT ... FOR UPDATE} 行锁，串行化同泊位并发容量判断；</li>
 *   <li>拖轮：锁定拖轮池行 + 数据库唯一约束 {@code uk_tug_time} 双重保护；</li>
 *   <li>潮汐：以 OPTIMISTIC 版本锁读取，审批提交时复验版本，数据变化即整体失败；</li>
 *   <li>申请：{@code application_no} 数据库唯一约束保证业务号幂等。</li>
 * </ul>
 *
 * <p>审批与改期均在单事务内完成“全部检查 → 全部占用”，任一资源不足整体回滚，
 * 不会出现只预留泊位或只预留拖轮的中间状态。</p>
 */
@Service
public class BerthWindowService {

    private final BerthRepository berthRepository;
    private final TugRepository tugRepository;
    private final TideWindowRepository tideWindowRepository;
    private final BerthApplicationRepository applicationRepository;
    private final BerthOccupationRepository occupationRepository;
    private final TugAssignmentRepository assignmentRepository;
    private final ChangeHistoryRepository historyRepository;
    private final ApplicationInsertGateway insertGateway;

    public BerthWindowService(BerthRepository berthRepository,
                              TugRepository tugRepository,
                              TideWindowRepository tideWindowRepository,
                              BerthApplicationRepository applicationRepository,
                              BerthOccupationRepository occupationRepository,
                              TugAssignmentRepository assignmentRepository,
                              ChangeHistoryRepository historyRepository,
                              ApplicationInsertGateway insertGateway) {
        this.berthRepository = berthRepository;
        this.tugRepository = tugRepository;
        this.tideWindowRepository = tideWindowRepository;
        this.applicationRepository = applicationRepository;
        this.occupationRepository = occupationRepository;
        this.assignmentRepository = assignmentRepository;
        this.historyRepository = historyRepository;
        this.insertGateway = insertGateway;
    }

    // ---------------- 基础资源管理 ----------------

    @Transactional
    public Berth createBerth(String code, String name, String berthType, Set<String> vesselTypes,
                             BigDecimal maxDraft, int simultaneousCapacity) {
        if (simultaneousCapacity <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "泊位同时作业能力必须大于 0");
        }
        return berthRepository.save(new Berth(code, name, berthType,
                new LinkedHashSet<>(vesselTypes), maxDraft, simultaneousCapacity));
    }

    @Transactional(readOnly = true)
    public List<Berth> listBerths() {
        return berthRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Berth getBerth(Long id) {
        return berthRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.BERTH_NOT_FOUND, "泊位不存在: " + id));
    }

    @Transactional
    public Tug createTug(String code, String name) {
        return tugRepository.save(new Tug(code, name));
    }

    @Transactional(readOnly = true)
    public List<Tug> listTugs() {
        return tugRepository.findAll();
    }

    @Transactional
    public TideWindow createTideWindow(String berthType, Instant start, Instant end, BigDecimal depth) {
        validateWindow(start, end);
        return tideWindowRepository.save(new TideWindow(berthType, start, end, depth));
    }

    @Transactional
    public TideWindow updateTideDepth(Long id, BigDecimal depth, Long expectedVersion) {
        TideWindow window = tideWindowRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "潮汐窗口不存在: " + id));
        if (expectedVersion != null && expectedVersion != window.getVersion()) {
            throw new ApiException(ErrorCode.JUDGMENT_STALE, "潮汐窗口版本已变化，请刷新后重试");
        }
        window.setAvailableDepth(depth);
        return tideWindowRepository.save(window);
    }

    @Transactional(readOnly = true)
    public List<TideWindow> listTideWindows(String berthType) {
        if (berthType != null && !berthType.isBlank()) {
            return tideWindowRepository.findByBerthType(berthType);
        }
        return tideWindowRepository.findAll();
    }

    // ---------------- 申请（幂等） ----------------

    /**
     * 提交申请。同一 {@code applicationNo} 重复提交直接返回已有申请（幂等）；
     * 数据库 application_no 唯一约束是并发场景下的最终保证。
     */
    @Transactional
    public BerthApplication submit(String applicationNo, String vesselCode, String vesselType,
                                   Instant eta, Instant etd, BigDecimal draft,
                                   String requiredBerthType, int requiredTugs) {
        validateSchedule(eta, etd);
        if (draft.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "船舶吃水必须大于 0");
        }
        if (requiredTugs < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "所需拖轮数量不能为负");
        }
        return applicationRepository.findByApplicationNo(applicationNo)
                .map(existing -> {
                    ensureSamePayload(existing, vesselCode, vesselType, eta, etd, draft,
                            requiredBerthType, requiredTugs);
                    return existing;
                })
                .orElseGet(() -> {
                    BerthApplication saved;
                    try {
                        // 独立事务执行 insert，立即暴露 application_no 唯一约束冲突
                        saved = insertGateway.insert(new BerthApplication(
                                applicationNo, vesselCode, vesselType, eta, etd, draft,
                                requiredBerthType, requiredTugs));
                    } catch (DataIntegrityViolationException duplicate) {
                        // 并发提交同一业务号：数据库唯一约束兜底，重读既有申请并校验内容一致
                        BerthApplication concurrent = applicationRepository
                                .findByApplicationNo(applicationNo)
                                .orElseThrow(() -> duplicate);
                        ensureSamePayload(concurrent, vesselCode, vesselType, eta, etd, draft,
                                requiredBerthType, requiredTugs);
                        return concurrent;
                    }
                    historyRepository.save(new ChangeHistory(saved.getId(), applicationNo,
                            HistoryAction.SUBMITTED, "申请已提交，等待审批", Instant.now()));
                    return saved;
                });
    }

    private void ensureSamePayload(BerthApplication existing, String vesselCode, String vesselType,
                                   Instant eta, Instant etd, BigDecimal draft,
                                   String requiredBerthType, int requiredTugs) {
        boolean same = existing.getVesselCode().equals(vesselCode)
                && existing.getVesselType().equals(vesselType)
                && existing.getEta().equals(eta)
                && existing.getEtd().equals(etd)
                && existing.getDraft().compareTo(draft) == 0
                && existing.getRequiredBerthType().equals(requiredBerthType)
                && existing.getRequiredTugs() == requiredTugs;
        if (!same) {
            throw new ApiException(ErrorCode.DUPLICATE_BUSINESS_KEY,
                    "业务申请号 " + existing.getApplicationNo() + " 已存在但申请内容不一致");
        }
    }

    @Transactional(readOnly = true)
    public List<BerthApplication> listApplications() {
        return applicationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public BerthApplication getApplication(String applicationNo) {
        return applicationRepository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + applicationNo));
    }

    // ---------------- 审批（泊位 + 拖轮一次性原子占用） ----------------

    @Transactional
    public BerthApplication approve(String applicationNo) {
        // 1. 锁定申请行：同一申请的并发审批在此串行化
        BerthApplication application = applicationRepository.findWithLockByApplicationNo(applicationNo)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + applicationNo));
        if (application.getStatus() != com.chris64233.berthwindow.domain.ApplicationStatus.PENDING) {
            throw new ApiException(ErrorCode.APPLICATION_ALREADY_APPROVED,
                    "申请 " + applicationNo + " 已审批或已取消，不能再次审批");
        }

        Instant eta = application.getEta();
        Instant etd = application.getEtd();

        // 2. 潮汐检查（OPTIMISTIC 版本锁：提交前复验，期间潮汐数据被改则整体失败）
        requireTideCoverage(application.getRequiredBerthType(), application.getDraft(), eta, etd);

        // 3. 选择泊位（逐个加行锁，始终按 id 升序，避免死锁）
        Berth berth = selectBerthWithCapacity(application, eta, etd);

        // 4. 选择拖轮（锁定拖轮池；不足则整体拒绝，此时尚未写入任何占用）
        List<Tug> chosenTugs = selectTugs(application, eta, etd, null);

        // 5. 全部资源满足后才一次性写入占用（单事务，任一写入冲突整体回滚）
        BerthOccupation occupation = occupationRepository.saveAndFlush(
                new BerthOccupation(berth.getId(), application.getId(), eta, etd));

        List<TugAssignment> assignments = new ArrayList<>();
        for (Tug tug : chosenTugs) {
            assignments.add(new TugAssignment(tug.getId(), application.getId(),
                    BerthActionType.BERTHING, eta));
            assignments.add(new TugAssignment(tug.getId(), application.getId(),
                    BerthActionType.UNBERTHING, etd));
        }
        if (!assignments.isEmpty()) {
            assignmentRepository.saveAll(assignments);
            assignmentRepository.flush();
        }

        application.approve(berth.getId());
        historyRepository.save(new ChangeHistory(application.getId(), applicationNo,
                HistoryAction.APPROVED,
                "审批通过：泊位 " + berth.getCode() + "，时段 " + eta + " ~ " + etd
                        + "，拖轮 " + chosenTugs.size() + " 艘（靠/离泊各 " + chosenTugs.size()
                        + " 艘次），占用记录 " + occupation.getId(),
                Instant.now()));
        return application;
    }

    /**
     * 校验靠泊与离泊时刻都落在满足吃水的潮汐窗口内。
     * 以 OPTIMISTIC 锁读取同泊位类型的全部潮汐窗口，事务提交时 Hibernate 复验版本号，
     * 审批期间任一窗口被修改即抛乐观锁失败，拒绝使用旧判断结果。
     */
    private void requireTideCoverage(String berthType, BigDecimal draft, Instant eta, Instant etd) {
        List<TideWindow> windows = tideWindowRepository.findForVerificationByBerthType(berthType);
        boolean etaCovered = windows.stream().anyMatch(w -> w.covers(eta)
                && w.getAvailableDepth().compareTo(draft) >= 0);
        boolean etdCovered = windows.stream().anyMatch(w -> w.covers(etd)
                && w.getAvailableDepth().compareTo(draft) >= 0);
        if (!etaCovered || !etdCovered) {
            List<String> missing = new ArrayList<>();
            if (!etaCovered) {
                missing.add("靠泊时刻 " + eta);
            }
            if (!etdCovered) {
                missing.add("离泊时刻 " + etd);
            }
            throw new ApiException(ErrorCode.TIDE_WINDOW_UNAVAILABLE,
                    String.join("、", missing) + " 没有满足吃水 " + draft + " 米的潮汐窗口");
        }
    }

    /**
     * 在类型、船型、吃水均匹配的候选泊位中，按 id 顺序逐个在数据库行锁保护下
     * 判断同时作业容量，返回第一个有空余能力的泊位。
     */
    private Berth selectBerthWithCapacity(BerthApplication application, Instant eta, Instant etd) {
        List<Berth> candidates = berthRepository
                .findByBerthTypeOrderById(application.getRequiredBerthType())
                .stream()
                .filter(b -> b.accepts(application.getVesselType()))
                .filter(b -> b.getMaxDraft().compareTo(application.getDraft()) >= 0)
                .sorted(Comparator.comparing(Berth::getId))
                .toList();
        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.NO_MATCHING_BERTH,
                    "没有类型为 " + application.getRequiredBerthType()
                            + "、接纳船型 " + application.getVesselType()
                            + " 且最大吃水不小于 " + application.getDraft() + " 米的泊位");
        }
        for (Berth candidate : candidates) {
            Berth locked = berthRepository.findWithLockById(candidate.getId()).orElseThrow();
            long overlapping = occupationRepository.countOverlapping(locked.getId(), eta, etd);
            if (overlapping < locked.getSimultaneousCapacity()) {
                return locked;
            }
        }
        throw new ApiException(ErrorCode.BERTH_CAPACITY_EXCEEDED,
                "匹配的泊位在 " + eta + " ~ " + etd + " 时段同时作业能力已满");
    }

    /**
     * 在靠泊与离泊两个时刻都空闲的拖轮中，按 id 顺序选取所需数量。
     * 调用前所有拖轮行已被本事务锁定；{@code excludeApplicationId} 用于改期排除自身占用。
     */
    private List<Tug> selectTugs(BerthApplication application, Instant eta, Instant etd,
                                 Long excludeApplicationId) {
        int required = application.getRequiredTugs();
        if (required == 0) {
            return List.of();
        }
        List<Tug> allTugs = tugRepository.findAllForUpdate();
        List<Instant> actionTimes = List.of(eta, etd);
        List<Long> busyIds = excludeApplicationId == null
                ? assignmentRepository.findBusyTugIds(actionTimes)
                : assignmentRepository.findBusyTugIdsExcluding(actionTimes, excludeApplicationId);
        Set<Long> busy = new java.util.HashSet<>(busyIds);
        List<Tug> free = allTugs.stream()
                .filter(t -> !busy.contains(t.getId()))
                .sorted(Comparator.comparing(Tug::getId))
                .limit(required)
                .toList();
        if (free.size() < required) {
            throw new ApiException(ErrorCode.INSUFFICIENT_TUGS,
                    "靠泊 " + eta + "、离泊 " + etd + " 所需的 " + required
                            + " 艘拖轮无法同时满足（当前可满足 " + free.size() + " 艘），申请整体拒绝");
        }
        return free;
    }

    // ---------------- 改期（成功后才释放原窗口，失败保留原安排） ----------------

    @Transactional
    public BerthApplication reschedule(String applicationNo, Instant newEta, Instant newEtd) {
        validateSchedule(newEta, newEtd);

        // 1. 锁定申请行并校验状态
        BerthApplication application = applicationRepository.findWithLockByApplicationNo(applicationNo)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + applicationNo));
        if (application.getStatus() != com.chris64233.berthwindow.domain.ApplicationStatus.APPROVED) {
            throw new ApiException(ErrorCode.APPLICATION_NOT_APPROVED,
                    "申请 " + applicationNo + " 尚未审批通过，不能改期");
        }
        Instant oldEta = application.getEta();
        Instant oldEtd = application.getEtd();
        Instant now = Instant.now();
        if (!now.isBefore(oldEta)) {
            throw new ApiException(ErrorCode.WINDOW_ALREADY_STARTED,
                    "原窗口已于 " + oldEta + " 开始作业，不能改期");
        }
        if (!now.isBefore(newEta)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "新的靠泊时间必须晚于当前时间");
        }

        Long berthId = application.getAssignedBerthId();
        Long appId = application.getId();

        // 2. 新窗口的全部资源检查（任何一项失败都抛异常，事务回滚，原安排原样保留）
        requireTideCoverage(application.getRequiredBerthType(), application.getDraft(), newEta, newEtd);

        Berth berth = berthRepository.findWithLockById(berthId).orElseThrow(
                () -> new ApiException(ErrorCode.BERTH_NOT_FOUND, "原安排泊位不存在: " + berthId));
        long others = occupationRepository.countOverlappingExcluding(berthId, appId, newEta, newEtd);
        if (others + 1 > berth.getSimultaneousCapacity()) {
            throw new ApiException(ErrorCode.BERTH_CAPACITY_EXCEEDED,
                    "泊位 " + berth.getCode() + " 在新时段 " + newEta + " ~ " + newEtd
                            + " 同时作业能力不足，改期拒绝，原安排保留");
        }

        List<Tug> chosenTugs = selectTugs(application, newEta, newEtd, appId);

        // 3. 新安排确认可行后，在同一事务内切换占用：
        //    先删除原占用并 flush（唯一约束要求先删后插），再写入新占用；
        //    提交前外部仍只能看到原窗口，提交失败则删除一并回滚。
        occupationRepository.deleteByApplicationId(appId);
        occupationRepository.flush();
        assignmentRepository.deleteByApplicationId(appId);
        assignmentRepository.flush();

        occupationRepository.save(new BerthOccupation(berthId, appId, newEta, newEtd));

        List<TugAssignment> newAssignments = new ArrayList<>();
        for (Tug tug : chosenTugs) {
            newAssignments.add(new TugAssignment(tug.getId(), appId, BerthActionType.BERTHING, newEta));
            newAssignments.add(new TugAssignment(tug.getId(), appId, BerthActionType.UNBERTHING, newEtd));
        }
        if (!newAssignments.isEmpty()) {
            assignmentRepository.saveAll(newAssignments);
        }

        application.applyNewSchedule(newEta, newEtd);

        historyRepository.save(new ChangeHistory(appId, applicationNo,
                HistoryAction.RESCHEDULED,
                "改期成功：" + oldEta + " ~ " + oldEtd + "  ->  " + newEta + " ~ " + newEtd
                        + "；新拖轮安排 " + chosenTugs.size() + " 艘；原窗口已释放",
                Instant.now()));
        return application;
    }

    // ---------------- 取消（释放占用；版本提升使在途互换方案失效） ----------------

    /**
     * 取消已批准且尚未开始作业的申请：在单事务内删除泊位占用与拖轮安排并置为 CANCELLED。
     * 申请实体的 {@code @Version} 随之提升，任何冻结过该申请的互换方案在确认重校验时
     * 都会因版本变化判定失效（{@code SWAP_STALE}），不会基于旧安排完成交换。
     */
    @Transactional
    public BerthApplication cancel(String applicationNo) {
        BerthApplication application = applicationRepository.findWithLockByApplicationNo(applicationNo)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + applicationNo));
        if (application.getStatus() == com.chris64233.berthwindow.domain.ApplicationStatus.CANCELLED) {
            return application;
        }
        if (application.getStatus() != com.chris64233.berthwindow.domain.ApplicationStatus.APPROVED) {
            throw new ApiException(ErrorCode.APPLICATION_NOT_APPROVED,
                    "申请 " + applicationNo + " 尚未审批通过，不能取消");
        }
        Instant oldEta = application.getEta();
        if (!Instant.now().isBefore(oldEta)) {
            throw new ApiException(ErrorCode.WINDOW_ALREADY_STARTED,
                    "原窗口已于 " + oldEta + " 开始作业，不能取消");
        }

        Long appId = application.getId();
        occupationRepository.deleteByApplicationId(appId);
        occupationRepository.flush();
        assignmentRepository.deleteByApplicationId(appId);
        assignmentRepository.flush();

        Instant eta = application.getEta();
        Instant etd = application.getEtd();
        Long berthId = application.getAssignedBerthId();
        application.cancel();

        historyRepository.save(new ChangeHistory(appId, applicationNo,
                HistoryAction.CANCELLED,
                "已取消批准安排：泊位 " + berthId + "，时段 " + eta + " ~ " + etd
                        + "；泊位与拖轮占用已释放",
                Instant.now()));
        return application;
    }

    // ---------------- 占用与历史查询 ----------------

    @Transactional(readOnly = true)
    public List<BerthOccupation> findOccupations(Long berthId, String applicationNo) {
        List<BerthOccupation> result;
        if (applicationNo != null && !applicationNo.isBlank()) {
            BerthApplication app = getApplication(applicationNo);
            result = occupationRepository.findByApplicationId(app.getId());
        } else if (berthId != null) {
            result = occupationRepository.findByBerthIdOrderByStartTimeAsc(berthId);
        } else {
            result = occupationRepository.findAllByOrderByStartTimeAsc();
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<TugAssignment> findAssignments(String applicationNo) {
        if (applicationNo != null && !applicationNo.isBlank()) {
            BerthApplication app = getApplication(applicationNo);
            return assignmentRepository.findByApplicationId(app.getId());
        }
        return assignmentRepository.findAllByOrderByActionTimeAsc();
    }

    @Transactional(readOnly = true)
    public List<ChangeHistory> findHistory(String applicationNo) {
        if (applicationNo != null && !applicationNo.isBlank()) {
            return historyRepository.findByApplicationNoOrderByOccurredAtAscIdAsc(applicationNo);
        }
        return historyRepository.findAllByOrderByOccurredAtAscIdAsc();
    }

    /** 查询用：批量构建 applicationId -> 申请 映射 */
    @Transactional(readOnly = true)
    public Map<Long, BerthApplication> applicationMap(List<Long> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        return applicationRepository.findAllById(applicationIds).stream()
                .collect(Collectors.toMap(BerthApplication::getId, Function.identity()));
    }

    /** 查询用：批量构建 tugId -> 拖轮 映射 */
    @Transactional(readOnly = true)
    public Map<Long, Tug> tugMap(List<Long> tugIds) {
        if (tugIds.isEmpty()) {
            return Map.of();
        }
        return tugRepository.findAllById(tugIds).stream()
                .collect(Collectors.toMap(Tug::getId, Function.identity()));
    }

    // ---------------- 公共校验 ----------------

    private void validateSchedule(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "起止时间不能为空");
        }
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "开始时间必须早于结束时间");
        }
    }

    private void validateWindow(Instant start, Instant end) {
        validateSchedule(start, end);
    }
}
