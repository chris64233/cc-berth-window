package com.chris64233.berthwindow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthActionType;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.BerthSwapProposal;
import com.chris64233.berthwindow.domain.BerthSwapSide;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.HistoryAction;
import com.chris64233.berthwindow.domain.SwapProposalStatus;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.domain.TideWindowSnapshotEntity;
import com.chris64233.berthwindow.domain.Tug;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.BerthRepository;
import com.chris64233.berthwindow.repo.BerthSwapProposalRepository;
import com.chris64233.berthwindow.repo.ChangeHistoryRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.repo.TugRepository;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 两艘已批准船舶的泊位时段互换服务。
 *
 * <p>互换不是交换两个时间字段：提议时冻结双方泊位、时间、潮汐资料（含版本号）与拖轮安排，
 * 并立即按交换后的条件完成泊位准入、潮汐、泊位容量、拖轮的全量校验；确认时在独立事务内
 * 依据冻结快照重新校验，任一方不满足或依据数据版本变化，则确认失败并留痕，
 * <b>原两份批准安排与资源占用原样保留</b>。</p>
 *
 * <p>并发保证：确认先对方案行加写锁，再对两份申请行按 id 升序加写锁（与改期/取消互斥）。
 * 并发确认在方案行锁处串行，第二个确认只会看到 CONFIRMED/FAILED 终态，
 * 不会重复交换或重复释放资源。</p>
 */
@Service
public class BerthSwapService {

    private final BerthApplicationRepository applicationRepository;
    private final BerthSwapProposalRepository proposalRepository;
    private final BerthRepository berthRepository;
    private final TideWindowRepository tideWindowRepository;
    private final TugRepository tugRepository;
    private final BerthOccupationRepository occupationRepository;
    private final TugAssignmentRepository assignmentRepository;
    private final ChangeHistoryRepository historyRepository;
    private final SwapProposalGateway proposalGateway;
    private final BerthSwapService self;

    public BerthSwapService(BerthApplicationRepository applicationRepository,
                            BerthSwapProposalRepository proposalRepository,
                            BerthRepository berthRepository,
                            TideWindowRepository tideWindowRepository,
                            TugRepository tugRepository,
                            BerthOccupationRepository occupationRepository,
                            TugAssignmentRepository assignmentRepository,
                            ChangeHistoryRepository historyRepository,
                            SwapProposalGateway proposalGateway,
                            @Lazy BerthSwapService self) {
        this.applicationRepository = applicationRepository;
        this.proposalRepository = proposalRepository;
        this.berthRepository = berthRepository;
        this.tideWindowRepository = tideWindowRepository;
        this.tugRepository = tugRepository;
        this.occupationRepository = occupationRepository;
        this.assignmentRepository = assignmentRepository;
        this.historyRepository = historyRepository;
        this.proposalGateway = proposalGateway;
        this.self = self;
    }

    // ---------------- 提议：冻结快照 + 按交换后条件预校验 ----------------

    /**
     * 提议互换。{@code swapNo} 为业务幂等键：同号同双方重复提议直接返回既有方案；
     * 同号不同双方返回 {@code DUPLICATE_BUSINESS_KEY}。
     */
    @Transactional
    public BerthSwapProposal propose(String swapNo, String applicationANo, String applicationBNo) {
        if (swapNo == null || swapNo.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "互换业务号不能为空");
        }
        if (applicationANo == null || applicationBNo == null
                || applicationANo.isBlank() || applicationBNo.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "互换双方申请号不能为空");
        }
        if (applicationANo.equals(applicationBNo)) {
            throw new ApiException(ErrorCode.SWAP_INVALID_PAIR, "互换双方必须是两份不同的申请");
        }

        BerthSwapProposal existing = proposalRepository.findBySwapNo(swapNo).orElse(null);
        if (existing != null) {
            ensureSamePair(existing, applicationANo, applicationBNo);
            return existing;
        }

        // 1. 锁定双方申请行（按 id 升序，与确认/改期/取消使用一致的加锁顺序）
        List<BerthApplication> both = lockTwoApplications(applicationANo, applicationBNo);
        BerthApplication appA = both.get(0).getApplicationNo().equals(applicationANo) ? both.get(0) : both.get(1);
        BerthApplication appB = both.get(0) == appA ? both.get(1) : both.get(0);

        requireSwappable(appA);
        requireSwappable(appB);

        Instant now = Instant.now();
        if (!now.isBefore(appA.getEta()) || !now.isBefore(appB.getEta())) {
            throw new ApiException(ErrorCode.WINDOW_ALREADY_STARTED,
                    "任一船舶已开始作业的安排不能互换");
        }

        // 2. 锁定双方当前泊位（按 id 升序）并冻结泊位资料
        List<Berth> berths = lockBerthsInIdOrder(
                List.of(appA.getAssignedBerthId(), appB.getAssignedBerthId()));
        Map<Long, Berth> berthById = berths.stream()
                .collect(Collectors.toMap(Berth::getId, b -> b, (x, y) -> x));
        Berth berthA = berthById.get(appA.getAssignedBerthId());
        Berth berthB = berthById.get(appB.getAssignedBerthId());

        // 3. 冻结涉及泊位类型的潮汐资料（OPTIMISTIC 版本锁）
        Set<String> berthTypes = new LinkedHashSet<>(List.of(berthA.getBerthType(), berthB.getBerthType()));
        List<TideWindow> tideWindows = tideWindowRepository.findForVerificationByBerthTypes(berthTypes);

        // 4. 预校验：交换后的泊位准入 + 潮汐 + 泊位容量 + 拖轮
        requireBerthEligibility(appA, berthB);
        requireBerthEligibility(appB, berthA);
        requireTideCoverage(appA, berthB.getBerthType(), appB.getEta(), appB.getEtd(), tideWindows);
        requireTideCoverage(appB, berthA.getBerthType(), appA.getEta(), appA.getEtd(), tideWindows);

        List<Long> bothAppIds = List.of(appA.getId(), appB.getId());
        requireCapacityAfterSwap(appA, appB, berthA, berthB, bothAppIds);

        // 拖轮池加锁后，按交换后时刻为双方选定同一批拖轮（A 先 B 后，动态累计占用）
        List<Tug> allTugs = tugRepository.findAllForUpdate();
        List<Long> tugsForA = selectTugsForSwappedSchedule(allTugs, appA,
                appB.getEta(), appB.getEtd(), bothAppIds, new HashMap<>());
        Map<Long, Set<Instant>> planned = new HashMap<>();
        for (Long tugId : tugsForA) {
            planned.computeIfAbsent(tugId, k -> new HashSet<>()).add(appB.getEta());
            planned.computeIfAbsent(tugId, k -> new HashSet<>()).add(appB.getEtd());
        }
        List<Long> tugsForB = selectTugsForSwappedSchedule(allTugs, appB,
                appA.getEta(), appA.getEtd(), bothAppIds, planned);

        // 5. 冻结快照落库（REQUIRES_NEW：swap_no 唯一约束冲突立即暴露）
        BerthSwapSide sideA = buildSide(appA, berthA, appB, berthB, tugsForA);
        BerthSwapSide sideB = buildSide(appB, berthB, appA, berthA, tugsForB);
        List<TideWindowSnapshotEntity> tideSnapshots = tideWindows.stream()
                .map(w -> new TideWindowSnapshotEntity(w.getId(), w.getBerthType(),
                        w.getWindowStart(), w.getWindowEnd(), w.getAvailableDepth(), w.getVersion()))
                .toList();
        BerthSwapProposal saved;
        try {
            saved = proposalGateway.insert(
                    new BerthSwapProposal(swapNo, sideA, sideB, tideSnapshots, now));
        } catch (DataIntegrityViolationException duplicate) {
            // 并发同号提议：唯一约束兜底，重读既有方案并校验双方一致
            BerthSwapProposal concurrent = proposalRepository.findBySwapNo(swapNo)
                    .orElseThrow(() -> duplicate);
            ensureSamePair(concurrent, applicationANo, applicationBNo);
            return concurrent;
        }

        // 6. 双方各留一条提议历史（含互换前后安排）
        historyRepository.save(new ChangeHistory(appA.getId(), appA.getApplicationNo(),
                HistoryAction.SWAP_PROPOSED,
                proposedDetail(swapNo, appA, berthA, appB, berthB), now));
        historyRepository.save(new ChangeHistory(appB.getId(), appB.getApplicationNo(),
                HistoryAction.SWAP_PROPOSED,
                proposedDetail(swapNo, appB, berthB, appA, berthA), now));
        return saved;
    }

    // ---------------- 确认：依据冻结快照重校验 + 原子交换 ----------------

    /**
     * 确认互换（编排，不开事务）：先在独立事务中做「冻结快照重校验 + 原子交换」，
     * 失败事务回滚后再用独立事务留痕（方案 FAILED + 双方失败历史）。
     *
     * <p>同 swapNo 重复确认幂等：已 CONFIRMED/已 FAILED 直接返回既有方案，
     * 不重复交换、不重复留痕。</p>
     */
    public BerthSwapProposal confirm(String swapNo) {
        BerthSwapProposal proposal = proposalRepository.findBySwapNo(swapNo)
                .orElseThrow(() -> new ApiException(ErrorCode.SWAP_NOT_FOUND, "互换方案不存在: " + swapNo));
        if (proposal.getStatus() != SwapProposalStatus.PROPOSED) {
            return proposal;
        }
        try {
            // REQUIRES_NEW：重校验 + 交换全部在独立事务内，失败则回滚并释放所有行锁
            return self.performConfirm(swapNo);
        } catch (ApiException business) {
            // 失败事务已回滚、锁已释放：在另一个独立事务中留痕（终态保证只记一次）
            self.recordConfirmFailure(swapNo, business.getMessage());
            throw business;
        }
    }

    /**
     * 在独立事务内依据冻结快照重新校验，全部通过后原子切换两份安排与资源占用。
     * 任一方不满足或依据数据已变化即抛异常，事务整体回滚，原两份批准安排保持可用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BerthSwapProposal performConfirm(String swapNo) {
        // 对方案行加写锁：同一方案的并发确认在此数据库层串行
        BerthSwapProposal proposal = proposalRepository.findWithLockBySwapNo(swapNo)
                .orElseThrow(() -> new ApiException(ErrorCode.SWAP_NOT_FOUND, "互换方案不存在: " + swapNo));
        if (proposal.getStatus() != SwapProposalStatus.PROPOSED) {
            return proposal;
        }
        BerthSwapSide sideA = proposal.sideA();
        BerthSwapSide sideB = proposal.sideB();

        // 锁定两份申请行（按 id 升序）：与并发的改期/取消/另一确认互斥
        for (Long id : List.of(sideA.getApplicationId(), sideB.getApplicationId())
                .stream().sorted().toList()) {
            applicationRepository.findWithLockById(id).orElseThrow(
                    () -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "申请不存在: " + id));
        }

        // 逐项重校验（不满足即抛异常，事务回滚，未写任何数据）
        revalidate(proposal);

        // 全部通过：原子切换两份占用、拖轮安排与申请
        applySwap(proposal);

        Instant at = Instant.now();
        proposal.markConfirmed(at);
        historyRepository.save(new ChangeHistory(sideA.getApplicationId(), sideA.getApplicationNo(),
                HistoryAction.SWAP_CONFIRMED, confirmedDetail(sideA), at));
        historyRepository.save(new ChangeHistory(sideB.getApplicationId(), sideB.getApplicationNo(),
                HistoryAction.SWAP_CONFIRMED, confirmedDetail(sideB), at));
        return proposal;
    }

    /**
     * 确认失败留痕（独立事务）：把仍为 PROPOSED 的方案标记为 FAILED，并为双方各写一条
     * SWAP_FAILED 历史（含互换前后安排与失败原因）。并发下只有一个事务能完成终结。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BerthSwapProposal recordConfirmFailure(String swapNo, String reason) {
        BerthSwapProposal proposal = proposalRepository.findWithLockBySwapNo(swapNo).orElse(null);
        if (proposal == null || proposal.getStatus() != SwapProposalStatus.PROPOSED) {
            return proposal;
        }
        Instant at = Instant.now();
        proposal.markFailed(reason, at);
        historyRepository.save(new ChangeHistory(proposal.sideA().getApplicationId(),
                proposal.sideA().getApplicationNo(), HistoryAction.SWAP_FAILED,
                failedDetail(proposal.sideA(), reason), at));
        historyRepository.save(new ChangeHistory(proposal.sideB().getApplicationId(),
                proposal.sideB().getApplicationNo(), HistoryAction.SWAP_FAILED,
                failedDetail(proposal.sideB(), reason), at));
        return proposal;
    }

    /** 依据冻结快照逐项重校验；任一不满足抛 {@link ApiException}（不写任何数据）。 */
    private void revalidate(BerthSwapProposal proposal) {
        BerthSwapSide sideA = proposal.sideA();
        BerthSwapSide sideB = proposal.sideB();
        BerthApplication appA = getManagedApplication(sideA.getApplicationId());
        BerthApplication appB = getManagedApplication(sideB.getApplicationId());

        // 1. 申请资料版本：改期/取消都会提升 @Version，旧方案立即失效
        requireSnapshotCurrent(sideA, appA);
        requireSnapshotCurrent(sideB, appB);

        // 2. 时间流逝保护：互换涉及的窗口均未开始
        Instant now = Instant.now();
        if (!now.isBefore(sideA.getFromEta()) || !now.isBefore(sideB.getFromEta())
                || !now.isBefore(sideA.getToEta()) || !now.isBefore(sideB.getToEta())) {
            throw new ApiException(ErrorCode.WINDOW_ALREADY_STARTED,
                    "互换涉及的窗口已开始作业，方案失效");
        }

        // 3. 泊位资料版本：泊位类型/接纳船型/吃水/容量变化即失效
        List<Berth> targetBerths = lockBerthsInIdOrder(
                List.of(sideA.getToBerthId(), sideB.getToBerthId()));
        Map<Long, Berth> targetById = targetBerths.stream()
                .collect(Collectors.toMap(Berth::getId, b -> b, (x, y) -> x));
        requireBerthSnapshotCurrent(sideA, targetById.get(sideA.getToBerthId()));
        requireBerthSnapshotCurrent(sideB, targetById.get(sideB.getToBerthId()));

        // 4. 潮汐资料：冻结后任何窗口新增/删除/更新都使旧方案失效
        Set<String> berthTypes = new LinkedHashSet<>(
                List.of(sideA.getToBerthType(), sideB.getToBerthType()));
        List<TideWindow> currentWindows =
                tideWindowRepository.findForVerificationByBerthTypes(berthTypes);
        requireTideSnapshotCurrent(proposal, currentWindows);

        // 5. 基于当前数据重算交换后条件（版本一致时结论应与提议时相同，这里不依赖旧判断结果）
        requireBerthEligibility(appA, targetById.get(sideA.getToBerthId()));
        requireBerthEligibility(appB, targetById.get(sideB.getToBerthId()));
        requireTideCoverage(appA, sideA.getToBerthType(), sideA.getToEta(), sideA.getToEtd(),
                currentWindows);
        requireTideCoverage(appB, sideB.getToBerthType(), sideB.getToEta(), sideB.getToEtd(),
                currentWindows);
        requireCapacityFromSnapshot(sideA, sideB, targetById);
        requireFrozenTugsStillAvailable(sideA, sideB);
    }

    /** 删除双方原占用与拖轮安排，写入交换后的占用与拖轮安排，并更新两份申请。 */
    private void applySwap(BerthSwapProposal proposal) {
        BerthSwapSide sideA = proposal.sideA();
        BerthSwapSide sideB = proposal.sideB();
        List<Long> appIds = List.of(sideA.getApplicationId(), sideB.getApplicationId());

        // 先删后插并 flush（uk_occupation_application / uk_tug_time 约束要求）
        occupationRepository.deleteByApplicationIdIn(appIds);
        occupationRepository.flush();
        assignmentRepository.deleteByApplicationIdIn(appIds);
        assignmentRepository.flush();

        occupationRepository.save(new BerthOccupation(sideA.getToBerthId(),
                sideA.getApplicationId(), sideA.getToEta(), sideA.getToEtd()));
        occupationRepository.save(new BerthOccupation(sideB.getToBerthId(),
                sideB.getApplicationId(), sideB.getToEta(), sideB.getToEtd()));

        List<TugAssignment> assignments = new ArrayList<>();
        addAssignments(assignments, sideA);
        addAssignments(assignments, sideB);
        if (!assignments.isEmpty()) {
            assignmentRepository.saveAll(assignments);
        }

        BerthApplication appA = getManagedApplication(sideA.getApplicationId());
        BerthApplication appB = getManagedApplication(sideB.getApplicationId());
        appA.applySwapArrangement(sideA.getToBerthId(), sideA.getToEta(), sideA.getToEtd());
        appB.applySwapArrangement(sideB.getToBerthId(), sideB.getToEta(), sideB.getToEtd());
    }

    private void addAssignments(List<TugAssignment> out, BerthSwapSide side) {
        for (Long tugId : side.getTugIds()) {
            out.add(new TugAssignment(tugId, side.getApplicationId(),
                    BerthActionType.BERTHING, side.getToEta()));
            out.add(new TugAssignment(tugId, side.getApplicationId(),
                    BerthActionType.UNBERTHING, side.getToEtd()));
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public BerthSwapProposal getProposal(String swapNo) {
        return proposalRepository.findBySwapNo(swapNo)
                .orElseThrow(() -> new ApiException(ErrorCode.SWAP_NOT_FOUND, "互换方案不存在: " + swapNo));
    }

    @Transactional(readOnly = true)
    public List<BerthSwapProposal> listProposals() {
        return proposalRepository.findAll();
    }

    // ---------------- 提议阶段校验 ----------------

    private void requireSwappable(BerthApplication app) {
        if (app.getStatus() != ApplicationStatus.APPROVED || app.getAssignedBerthId() == null) {
            throw new ApiException(ErrorCode.SWAP_INVALID_PAIR,
                    "申请 " + app.getApplicationNo() + " 不是已批准且有泊位安排的状态，不能互换");
        }
    }

    private void requireBerthEligibility(BerthApplication app, Berth target) {
        boolean eligible = target.getBerthType().equals(app.getRequiredBerthType())
                && target.accepts(app.getVesselType())
                && target.getMaxDraft().compareTo(app.getDraft()) >= 0;
        if (!eligible) {
            throw new ApiException(ErrorCode.NO_MATCHING_BERTH,
                    "船舶 " + app.getApplicationNo() + " 互换到泊位 " + target.getCode()
                            + " 后不满足泊位类型/接纳船型/最大吃水条件，互换被拒绝");
        }
    }

    private void requireTideCoverage(BerthApplication app, String berthType,
                                     Instant eta, Instant etd, List<TideWindow> windows) {
        List<TideWindow> ofType = windows.stream()
                .filter(w -> w.getBerthType().equals(berthType))
                .toList();
        boolean etaCovered = ofType.stream().anyMatch(w -> w.covers(eta)
                && w.getAvailableDepth().compareTo(app.getDraft()) >= 0);
        boolean etdCovered = ofType.stream().anyMatch(w -> w.covers(etd)
                && w.getAvailableDepth().compareTo(app.getDraft()) >= 0);
        if (!etaCovered || !etdCovered) {
            List<String> missing = new ArrayList<>();
            if (!etaCovered) {
                missing.add("互换后靠泊时刻 " + eta);
            }
            if (!etdCovered) {
                missing.add("互换后离泊时刻 " + etd);
            }
            throw new ApiException(ErrorCode.TIDE_WINDOW_UNAVAILABLE,
                    "船舶 " + app.getApplicationNo() + " " + String.join("、", missing)
                            + " 没有满足吃水 " + app.getDraft() + " 米的潮汐窗口，互换被拒绝");
        }
    }

    /**
     * 泊位容量重算（提议时）：A 驶入 B 的泊位/时段，B 驶入 A 的泊位/时段。
     * 按「泊位 + 目标时段」聚合驶入船数（双方可能在同一泊位互换），统计其它船舶在该时段的
     * 重叠占用，加上驶入船数后不得超过泊位能力。
     */
    private void requireCapacityAfterSwap(BerthApplication appA, BerthApplication appB,
                                          Berth berthA, Berth berthB, List<Long> bothAppIds) {
        Map<SlotKey, SlotDemand> demands = new HashMap<>();
        addDemand(demands, berthB.getId(), appB.getEta(), appB.getEtd()); // A -> B 的时段
        addDemand(demands, berthA.getId(), appA.getEta(), appA.getEtd()); // B -> A 的时段
        Map<Long, Berth> berths = new HashMap<>();
        berths.put(berthA.getId(), berthA);
        berths.put(berthB.getId(), berthB);
        checkCapacities(demands, berths, bothAppIds,
                "互换后泊位在目标时段同时作业能力不足，互换被拒绝");
    }

    private void requireCapacityFromSnapshot(BerthSwapSide sideA, BerthSwapSide sideB,
                                             Map<Long, Berth> targetById) {
        Map<SlotKey, SlotDemand> demands = new HashMap<>();
        addDemand(demands, sideA.getToBerthId(), sideA.getToEta(), sideA.getToEtd());
        addDemand(demands, sideB.getToBerthId(), sideB.getToEta(), sideB.getToEtd());
        List<Long> bothAppIds = List.of(sideA.getApplicationId(), sideB.getApplicationId());
        checkCapacities(demands, targetById, bothAppIds,
                "互换后泊位容量已被其它船舶占用，确认失败，原安排保留");
    }

    private record SlotKey(Long berthId, Instant eta, Instant etd) {
    }

    private record SlotDemand(Long berthId, Instant eta, Instant etd, int incomingShips) {
    }

    private void addDemand(Map<SlotKey, SlotDemand> demands, Long berthId, Instant eta, Instant etd) {
        SlotKey key = new SlotKey(berthId, eta, etd);
        SlotDemand existing = demands.get(key);
        int count = existing == null ? 1 : existing.incomingShips() + 1;
        demands.put(key, new SlotDemand(berthId, eta, etd, count));
    }

    private void checkCapacities(Map<SlotKey, SlotDemand> demands, Map<Long, Berth> berths,
                                 List<Long> excludedAppIds, String message) {
        for (SlotDemand demand : demands.values()) {
            long others = occupationRepository.countOverlappingExcludingApplications(
                    demand.berthId(), excludedAppIds, demand.eta(), demand.etd());
            if (others + demand.incomingShips() > berths.get(demand.berthId()).getSimultaneousCapacity()) {
                throw new ApiException(ErrorCode.BERTH_CAPACITY_EXCEEDED,
                        "泊位 " + berths.get(demand.berthId()).getCode() + " 在 " + demand.eta()
                                + " ~ " + demand.etd() + " " + message);
            }
        }
    }

    /**
     * 为互换后的一艘船选择拖轮：靠/离泊两个时刻必须为同一批拖轮。
     * {@code planned} 累计本方案中另一艘船已选定的 (拖轮, 时刻)，避免互换方案内部撞车。
     */
    private List<Long> selectTugsForSwappedSchedule(List<Tug> allTugs, BerthApplication app,
                                                    Instant eta, Instant etd,
                                                    List<Long> excludedAppIds,
                                                    Map<Long, Set<Instant>> planned) {
        int required = app.getRequiredTugs();
        if (required == 0) {
            return List.of();
        }
        Set<Long> dbBusy = new HashSet<>(assignmentRepository.findBusyTugIdsExcludingApplications(
                List.of(eta, etd), excludedAppIds));
        List<Long> chosen = new ArrayList<>();
        for (Tug tug : allTugs.stream().sorted(Comparator.comparing(Tug::getId)).toList()) {
            if (dbBusy.contains(tug.getId())) {
                continue;
            }
            Set<Instant> plannedTimes = planned.getOrDefault(tug.getId(), Set.of());
            if (plannedTimes.contains(eta) || plannedTimes.contains(etd)) {
                continue;
            }
            chosen.add(tug.getId());
            if (chosen.size() == required) {
                break;
            }
        }
        if (chosen.size() < required) {
            throw new ApiException(ErrorCode.INSUFFICIENT_TUGS,
                    "船舶 " + app.getApplicationNo() + " 互换后靠泊 " + eta + "、离泊 " + etd
                            + " 所需的 " + required + " 艘拖轮无法同时满足（当前可满足 "
                            + chosen.size() + " 艘），互换被拒绝");
        }
        return chosen;
    }

    // ---------------- 确认阶段重校验 ----------------

    private void requireSnapshotCurrent(BerthSwapSide side, BerthApplication app) {
        if (app.getStatus() != ApplicationStatus.APPROVED || app.getAssignedBerthId() == null) {
            throw new ApiException(ErrorCode.SWAP_STALE,
                    "申请 " + side.getApplicationNo()
                            + " 已不是已批准状态（可能已取消），互换方案失效");
        }
        boolean changed = app.getVersion() != side.getApplicationVersion()
                || !app.getAssignedBerthId().equals(side.getFromBerthId())
                || !app.getEta().equals(side.getFromEta())
                || !app.getEtd().equals(side.getFromEtd());
        if (changed) {
            throw new ApiException(ErrorCode.SWAP_STALE,
                    "申请 " + side.getApplicationNo()
                            + " 的安排在方案冻结后发生改期/取消，版本已变化，互换方案失效");
        }
    }

    private void requireBerthSnapshotCurrent(BerthSwapSide side, Berth current) {
        boolean changed = current.getVersion() != side.getToBerthVersion()
                || !current.getBerthType().equals(side.getToBerthType())
                || current.getMaxDraft().compareTo(side.getToBerthMaxDraft()) != 0
                || current.getSimultaneousCapacity() != side.getToBerthCapacity()
                || !current.getAcceptedVesselTypes().equals(side.getToBerthAcceptedVesselTypes());
        if (changed) {
            throw new ApiException(ErrorCode.SWAP_STALE,
                    "互换目标泊位 " + side.getToBerthCode() + " 资料在方案冻结后发生变化，互换方案失效");
        }
    }

    /**
     * 潮汐资料冻结后，涉及泊位类型的任一窗口被新增、删除、更新（版本变化）都使方案失效。
     * 读取本身使用 OPTIMISTIC 锁，提交时再做一次版本复验。
     */
    private void requireTideSnapshotCurrent(BerthSwapProposal proposal, List<TideWindow> currentWindows) {
        Map<Long, TideWindow> currentById = currentWindows.stream()
                .collect(Collectors.toMap(TideWindow::getId, w -> w));
        for (TideWindowSnapshotEntity frozen : proposal.getTideSnapshots()) {
            TideWindow current = currentById.get(frozen.getTideWindowId());
            if (current == null) {
                throw new ApiException(ErrorCode.SWAP_STALE,
                        "潮汐窗口 " + frozen.getTideWindowId() + " 在方案冻结后被删除，互换方案失效");
            }
            if (current.getVersion() != frozen.getVersion()
                    || current.getAvailableDepth().compareTo(frozen.getAvailableDepth()) != 0
                    || !current.getWindowStart().equals(frozen.getWindowStart())
                    || !current.getWindowEnd().equals(frozen.getWindowEnd())
                    || !current.getBerthType().equals(frozen.getBerthType())) {
                throw new ApiException(ErrorCode.SWAP_STALE,
                        "潮汐资料在方案冻结后已更新（窗口 " + frozen.getTideWindowId() + "），互换方案失效");
            }
        }
        if (currentWindows.size() != proposal.getTideSnapshots().size()) {
            throw new ApiException(ErrorCode.SWAP_STALE,
                    "潮汐资料在方案冻结后新增了窗口，互换方案失效");
        }
    }

    /**
     * 冻结的拖轮安排在确认时必须仍可用：方案选定的每艘拖轮在交换后的靠/离泊时刻
     * 未被互换双方以外的船舶占用；方案内部也不得出现（拖轮, 时刻）撞车。
     */
    private void requireFrozenTugsStillAvailable(BerthSwapSide sideA, BerthSwapSide sideB) {
        List<Instant> allTimes = List.of(
                        sideA.getToEta(), sideA.getToEtd(), sideB.getToEta(), sideB.getToEtd())
                .stream().distinct().toList();
        Set<Long> dbBusy = new HashSet<>(assignmentRepository.findBusyTugIdsExcludingApplications(
                allTimes, List.of(sideA.getApplicationId(), sideB.getApplicationId())));

        Map<Long, Set<Instant>> plan = new HashMap<>();
        checkSideTugs(sideA, dbBusy, plan);
        checkSideTugs(sideB, dbBusy, plan);
    }

    private void checkSideTugs(BerthSwapSide side, Set<Long> dbBusy, Map<Long, Set<Instant>> plan) {
        for (Long tugId : side.getTugIds()) {
            if (dbBusy.contains(tugId)) {
                throw new ApiException(ErrorCode.INSUFFICIENT_TUGS,
                        "冻结的拖轮 " + tugId + " 在互换后时刻已被其它船舶占用，确认失败，原安排保留");
            }
            Set<Instant> times = plan.computeIfAbsent(tugId, k -> new HashSet<>());
            for (Instant t : List.of(side.getToEta(), side.getToEtd())) {
                if (!times.add(t)) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_TUGS,
                            "冻结拖轮安排内部冲突：拖轮 " + tugId + " 在 " + t + " 时刻被重复安排");
                }
            }
        }
    }

    // ---------------- 加锁与快照构造 ----------------

    private List<BerthApplication> lockTwoApplications(String noA, String noB) {
        BerthApplication a = applicationRepository.findByApplicationNo(noA)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "申请不存在: " + noA));
        BerthApplication b = applicationRepository.findByApplicationNo(noB)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "申请不存在: " + noB));
        // 始终按 id 升序加行锁，避免互换/改期/取消交叉时的死锁
        List<Long> orderedIds = List.of(a.getId(), b.getId()).stream().sorted().toList();
        Map<Long, BerthApplication> locked = new HashMap<>();
        for (Long id : orderedIds) {
            locked.put(id, applicationRepository.findWithLockById(id).orElseThrow());
        }
        return List.of(locked.get(a.getId()), locked.get(b.getId()));
    }

    private BerthApplication getManagedApplication(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "申请不存在: " + id));
    }

    private List<Berth> lockBerthsInIdOrder(List<Long> berthIds) {
        List<Berth> result = new ArrayList<>();
        for (Long id : berthIds.stream().distinct().sorted().toList()) {
            result.add(berthRepository.findWithLockById(id).orElseThrow(
                    () -> new ApiException(ErrorCode.BERTH_NOT_FOUND, "泊位不存在: " + id)));
        }
        return result;
    }

    /**
     * 构造一方冻结快照：互换前为本船当前泊位/时段，互换后为对方当前泊位/时段，
     * 并冻结本船申请资料版本与目标泊位资料。
     */
    private BerthSwapSide buildSide(BerthApplication self, Berth selfBerth,
                                    BerthApplication other, Berth otherBerth,
                                    List<Long> chosenTugIds) {
        return new BerthSwapSide(
                self.getId(), self.getApplicationNo(),
                selfBerth.getId(), selfBerth.getCode(), self.getEta(), self.getEtd(),
                otherBerth.getId(), otherBerth.getCode(), other.getEta(), other.getEtd(),
                self.getVesselType(), self.getDraft(), self.getRequiredBerthType(),
                self.getRequiredTugs(), self.getVersion(),
                otherBerth.getBerthType(), otherBerth.getAcceptedVesselTypes(),
                otherBerth.getMaxDraft(), otherBerth.getSimultaneousCapacity(), otherBerth.getVersion(),
                chosenTugIds);
    }

    private void ensureSamePair(BerthSwapProposal proposal, String noA, String noB) {
        Set<String> existingPair = Set.of(proposal.getApplicationANo(), proposal.getApplicationBNo());
        Set<String> requestedPair = Set.of(noA, noB);
        if (!existingPair.equals(requestedPair)) {
            throw new ApiException(ErrorCode.DUPLICATE_BUSINESS_KEY,
                    "互换业务号 " + proposal.getSwapNo() + " 已存在但互换双方不一致");
        }
    }

    // ---------------- 历史明细 ----------------

    private String proposedDetail(String swapNo, BerthApplication self, Berth selfBerth,
                                  BerthApplication other, Berth otherBerth) {
        return "互换方案 " + swapNo + " 已冻结：互换前 [泊位 " + selfBerth.getCode()
                + "，" + self.getEta() + " ~ " + self.getEtd() + "]；互换后拟 [泊位 "
                + otherBerth.getCode() + "，" + other.getEta() + " ~ " + other.getEtd()
                + "]，待确认";
    }

    private String confirmedDetail(BerthSwapSide side) {
        return "互换确认成功：[泊位 " + side.getFromBerthCode() + "，" + side.getFromEta() + " ~ "
                + side.getFromEtd() + "] -> [泊位 " + side.getToBerthCode() + "，" + side.getToEta()
                + " ~ " + side.getToEtd() + "]；拖轮 " + side.getTugIds().size()
                + " 艘，原窗口与拖轮安排已切换";
    }

    private String failedDetail(BerthSwapSide side, String reason) {
        return "互换确认失败：互换前 [泊位 " + side.getFromBerthCode() + "，" + side.getFromEta() + " ~ "
                + side.getFromEtd() + "]，互换后拟 [泊位 " + side.getToBerthCode() + "，"
                + side.getToEta() + " ~ " + side.getToEtd() + "]；原因：" + reason
                + "；原安排保留";
    }
}
