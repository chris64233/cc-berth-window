package com.chris64233.berthwindow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.BerthActionType;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.HistoryAction;
import com.chris64233.berthwindow.domain.SwapProposal;
import com.chris64233.berthwindow.domain.SwapStatus;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.domain.Tug;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.BerthRepository;
import com.chris64233.berthwindow.repo.ChangeHistoryRepository;
import com.chris64233.berthwindow.repo.SwapProposalRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.repo.TugRepository;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 两艘已批准船舶的泊位时段互换服务。
 *
 * <p>互换不是交换两个时间字段：方案（{@link SwapProposal}）冻结双方泊位、时段、申请版本、
 * 潮汐窗口版本签名与拖轮安排；确认时在单事务内对双方在<strong>交换后的时段与泊位</strong>下
 * 重新执行泊位准入、容量、潮汐、拖轮全部校验，全部满足才原子切换两份申请与资源占用，
 * 任一方不满足则不做任何变更，两份原批准安排保持可用。</p>
 *
 * <p>失效与并发保证：</p>
 * <ul>
 *   <li>双方申请带 {@code @Version}：任一方改期、取消都会使冻结快照的申请版本失效；</li>
 *   <li>潮汐窗口带 {@code @Version}：资料更新使冻结的潮汐签名失效；确认事务内另以
 *       OPTIMISTIC 锁复验，确认期间潮汐被改则整笔回滚；</li>
 *   <li>方案行加 {@code SELECT ... FOR UPDATE}：同一方案的并发确认被数据库串行化，
 *       只有一笔执行交换，其余幂等返回，绝不重复交换或重复释放资源；</li>
 *   <li>{@code proposal_no} 唯一约束 + 独立插入事务保证方案号幂等；</li>
 *   <li>申请行按 id 升序、泊位行按 id 升序加锁，避免跨事务死锁。</li>
 * </ul>
 */
@Service
public class BerthSwapService {

    private final BerthApplicationRepository applicationRepository;
    private final BerthOccupationRepository occupationRepository;
    private final TugAssignmentRepository assignmentRepository;
    private final BerthRepository berthRepository;
    private final TugRepository tugRepository;
    private final TideWindowRepository tideWindowRepository;
    private final SwapProposalRepository proposalRepository;
    private final ChangeHistoryRepository historyRepository;
    private final SwapProposalInsertGateway insertGateway;
    private final TransactionTemplate transactionTemplate;

    public BerthSwapService(BerthApplicationRepository applicationRepository,
                            BerthOccupationRepository occupationRepository,
                            TugAssignmentRepository assignmentRepository,
                            BerthRepository berthRepository,
                            TugRepository tugRepository,
                            TideWindowRepository tideWindowRepository,
                            SwapProposalRepository proposalRepository,
                            ChangeHistoryRepository historyRepository,
                            SwapProposalInsertGateway insertGateway,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.applicationRepository = applicationRepository;
        this.occupationRepository = occupationRepository;
        this.assignmentRepository = assignmentRepository;
        this.berthRepository = berthRepository;
        this.tugRepository = tugRepository;
        this.tideWindowRepository = tideWindowRepository;
        this.proposalRepository = proposalRepository;
        this.historyRepository = historyRepository;
        this.insertGateway = insertGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 确认结果：success=false 时 code/reason 给出失败错误码与原因（方案已落库为 FAILED）。 */
    public record Confirmation(SwapProposal proposal, BerthApplication applicationA,
                               BerthApplication applicationB, boolean success,
                               ErrorCode code, String reason) {
    }

    // ---------------- 方案冻结 ----------------

    /**
     * 冻结互换方案。同一 {@code proposalNo} 重复提交且双方一致时幂等返回原方案；
     * 同号不同参与方冲突。
     */
    @Transactional
    public SwapProposal propose(String proposalNo, String applicationANo, String applicationBNo) {
        if (proposalNo == null || proposalNo.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "互换方案号不能为空");
        }
        if (applicationANo == null || applicationBNo == null
                || applicationANo.isBlank() || applicationBNo.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "互换双方申请号不能为空");
        }
        if (applicationANo.equals(applicationBNo)) {
            throw new ApiException(ErrorCode.SWAP_SAME_APPLICATION,
                    "互换双方不能是同一份申请: " + applicationANo);
        }

        SwapProposal existing = proposalRepository.findByProposalNo(proposalNo).orElse(null);
        if (existing != null) {
            ensureSameParticipants(existing, applicationANo, applicationBNo);
            return existing;
        }

        // 锁定双方申请（按业务号定位后，实际加锁仍按 id 顺序）
        BerthApplication a = requireApprovedApplication(applicationANo);
        BerthApplication b = requireApprovedApplication(applicationBNo);
        List<BerthApplication> ordered = orderById(a, b);
        a = applicationRepository.findWithLockById(ordered.get(0).getId()).orElseThrow();
        b = applicationRepository.findWithLockById(ordered.get(1).getId()).orElseThrow();
        // 重新按业务号归位
        BerthApplication appA = a.getApplicationNo().equals(applicationANo) ? a : b;
        BerthApplication appB = b.getApplicationNo().equals(applicationBNo) ? b : a;
        requireApprovableForSwap(appA, "A");
        requireApprovableForSwap(appB, "B");

        requireSingleOccupation(appA);
        requireSingleOccupation(appB);

        String aTugIds = snapshotTugIds(appA.getId());
        String bTugIds = snapshotTugIds(appB.getId());

        SwapProposal proposal = new SwapProposal(proposalNo,
                appA.getId(), appA.getApplicationNo(), appA.getAssignedBerthId(),
                appA.getEta(), appA.getEtd(), appA.getVersion(),
                tideSignature(appA.getRequiredBerthType()), aTugIds,
                appB.getId(), appB.getApplicationNo(), appB.getAssignedBerthId(),
                appB.getEta(), appB.getEtd(), appB.getVersion(),
                tideSignature(appB.getRequiredBerthType()), bTugIds);
        try {
            // 独立事务 insert，立即暴露 proposal_no 唯一约束冲突，不污染当前事务
            return insertGateway.insert(proposal);
        } catch (DataIntegrityViolationException duplicate) {
            SwapProposal concurrent = proposalRepository.findByProposalNo(proposalNo)
                    .orElseThrow(() -> duplicate);
            ensureSameParticipants(concurrent, applicationANo, applicationBNo);
            return concurrent;
        }
    }

    private void ensureSameParticipants(SwapProposal proposal, String applicationANo, String applicationBNo) {
        boolean same = proposal.getApplicationANo().equals(applicationANo)
                && proposal.getApplicationBNo().equals(applicationBNo);
        if (!same) {
            throw new ApiException(ErrorCode.DUPLICATE_BUSINESS_KEY,
                    "互换方案号 " + proposal.getProposalNo() + " 已存在但参与方不一致");
        }
    }

    private BerthApplication requireApprovedApplication(String applicationNo) {
        return applicationRepository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + applicationNo));
    }

    private void requireApprovableForSwap(BerthApplication app, String side) {
        if (app.getStatus() != ApplicationStatus.APPROVED) {
            throw new ApiException(ErrorCode.APPLICATION_NOT_APPROVED,
                    "互换 " + side + " 方申请 " + app.getApplicationNo() + " 当前状态为 "
                            + app.getStatus() + "，只有已批准申请可以互换");
        }
        if (app.getAssignedBerthId() == null) {
            throw new ApiException(ErrorCode.APPLICATION_NOT_APPROVED,
                    "互换 " + side + " 方申请 " + app.getApplicationNo() + " 缺少已安排泊位");
        }
        if (!Instant.now().isBefore(app.getEta())) {
            throw new ApiException(ErrorCode.WINDOW_ALREADY_STARTED,
                    "互换 " + side + " 方申请 " + app.getApplicationNo() + " 的窗口已于 "
                            + app.getEta() + " 开始作业，不能互换");
        }
    }

    private void requireSingleOccupation(BerthApplication app) {
        if (occupationRepository.findByApplicationId(app.getId()).size() != 1) {
            throw new ApiException(ErrorCode.APPLICATION_NOT_APPROVED,
                    "申请 " + app.getApplicationNo() + " 没有唯一的泊位占用安排");
        }
    }

    private String snapshotTugIds(Long applicationId) {
        return assignmentRepository.findByApplicationId(applicationId).stream()
                .map(TugAssignment::getTugId)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * 某泊位类型全部潮汐窗口的版本签名（id:version，按 id 升序）。
     * 窗口新增、删除、水深更新都会改变签名；读取本身带 OPTIMISTIC 锁。
     */
    private String tideSignature(String berthType) {
        return tideWindowRepository.findForVerificationByBerthType(berthType).stream()
                .sorted(Comparator.comparing(TideWindow::getId))
                .map(w -> w.getId() + ":" + w.getVersion())
                .collect(Collectors.joining(";"));
    }

    private static List<BerthApplication> orderById(BerthApplication x, BerthApplication y) {
        return x.getId() < y.getId() ? List.of(x, y) : List.of(y, x);
    }

    // ---------------- 方案确认（单事务：重校验通过才原子切换） ----------------

    /**
     * 确认互换。整笔确认（含失败落库）在一个数据库事务内执行：
     * 所有校验先于任何资源变更，因此校验失败时可以在同一事务把方案标记为 FAILED
     * 并写入失败历史后提交——两份原安排一行未改。
     */
    public Confirmation confirm(String proposalNo) {
        SwapProposal proposal = proposalRepository.findByProposalNo(proposalNo)
                .orElseThrow(() -> new ApiException(ErrorCode.SWAP_NOT_FOUND,
                        "互换方案不存在: " + proposalNo));
        return transactionTemplate.execute(status -> doConfirm(proposal.getId()));
    }

    private Confirmation doConfirm(Long proposalId) {
        // 1. 锁定方案行：同一方案的并发确认在此串行化
        SwapProposal proposal = proposalRepository.findWithLockById(proposalId).orElseThrow();

        if (proposal.getStatus() == SwapStatus.CONFIRMED) {
            // 幂等：另一笔并发/重复确认已经完成交换，直接返回当前安排
            BerthApplication a = applicationRepository.findById(proposal.getApplicationAId()).orElseThrow();
            BerthApplication b = applicationRepository.findById(proposal.getApplicationBId()).orElseThrow();
            return new Confirmation(proposal, a, b, true, null, null);
        }
        if (proposal.getStatus() == SwapStatus.FAILED) {
            // 幂等返回同一次失败结论
            ErrorCode code = resolveCode(proposal.getFailureCode());
            return new Confirmation(proposal,
                    applicationRepository.findById(proposal.getApplicationAId()).orElseThrow(),
                    applicationRepository.findById(proposal.getApplicationBId()).orElseThrow(),
                    false, code, proposal.getFailureReason());
        }

        // 2. 按 id 升序锁定双方申请
        BerthApplication first = applicationRepository.findWithLockById(
                Math.min(proposal.getApplicationAId(), proposal.getApplicationBId())).orElseThrow();
        BerthApplication second = applicationRepository.findWithLockById(
                Math.max(proposal.getApplicationAId(), proposal.getApplicationBId())).orElseThrow();
        BerthApplication appA = first.getId().equals(proposal.getApplicationAId()) ? first : second;
        BerthApplication appB = first.getId().equals(proposal.getApplicationAId()) ? second : first;

        // 3. 快照失效检查：申请版本/状态、潮汐签名、当前泊位占用
        String stale = detectStaleness(proposal, appA, appB);
        if (stale != null) {
            return failAndCommit(proposal, appA, appB, ErrorCode.SWAP_PROPOSAL_STALE, stale);
        }

        // 交换后的安排：A 进入 B 的泊位/时段，B 进入 A 的泊位/时段
        SwapSlot slotA = new SwapSlot(appA, proposal.getBBerthId(), proposal.getBEta(), proposal.getBEtd(), "A");
        SwapSlot slotB = new SwapSlot(appB, proposal.getABerthId(), proposal.getAEta(), proposal.getAEtd(), "B");

        // 4. 锁定涉及泊位（id 升序）并做泊位准入 + 容量校验。
        // 双方可能安排在同一泊位（同泊位换时段），Set.copyOf 会对重复泊位去重。
        Map<Long, Berth> berths = lockBerths(
                Set.copyOf(List.of(proposal.getABerthId(), proposal.getBBerthId())));
        try {
            validateBerth(proposal, appA, appB, slotA, berths, List.of(slotA, slotB));
            validateBerth(proposal, appA, appB, slotB, berths, List.of(slotA, slotB));
        } catch (ApiException ex) {
            return failAndCommit(proposal, appA, appB, ex.getCode(), ex.getMessage());
        }

        // 5. 潮汐重校验（OPTIMISTIC 读：提交时复验版本，期间被改整笔回滚）
        try {
            requireTideCoverage(appA, slotA.start(), slotA.end());
            requireTideCoverage(appB, slotB.start(), slotB.end());
        } catch (ApiException ex) {
            return failAndCommit(proposal, appA, appB, ex.getCode(), ex.getMessage());
        }
        String currentSignatureA = tideSignature(appA.getRequiredBerthType());
        String currentSignatureB = tideSignature(appB.getRequiredBerthType());
        if (!currentSignatureA.equals(proposal.getATideSignature())
                || !currentSignatureB.equals(proposal.getBTideSignature())) {
            return failAndCommit(proposal, appA, appB, ErrorCode.SWAP_PROPOSAL_STALE,
                    "冻结后潮汐资料已更新，互换方案失效，请基于最新潮汐重新发起");
        }

        // 6. 锁定拖轮池并为双方在交换后的时刻统一分配（任一方不足整体失败）
        List<Tug> allTugs = tugRepository.findAllForUpdate();
        Set<Long> bothAppIds = Set.of(appA.getId(), appB.getId());
        List<Instant> allActionTimes = List.of(slotA.start(), slotA.end(), slotB.start(), slotB.end());
        // 拖轮 -> 已占用时刻集合：初始为互换双方以外的既有占用，随后累计本次新分配
        Map<Long, Set<Instant>> busyTimes = assignmentRepository
                .findAssignmentsExcludingBoth(allActionTimes, bothAppIds).stream()
                .collect(Collectors.groupingBy(TugAssignment::getTugId,
                        Collectors.mapping(TugAssignment::getActionTime, Collectors.toSet())));
        List<Tug> tugsForA;
        List<Tug> tugsForB;
        try {
            // 按申请 id 顺序分配，结果确定
            List<SwapSlot> allocationOrder = orderById(appA, appB).get(0).getId().equals(appA.getId())
                    ? List.of(slotA, slotB) : List.of(slotB, slotA);
            List<Allocated> allocated = new ArrayList<>();
            for (SwapSlot slot : allocationOrder) {
                List<Tug> chosen = allocateTugs(allTugs, busyTimes, slot);
                allocated.add(new Allocated(slot, chosen));
            }
            tugsForA = allocated.stream().filter(x -> x.slot().side().equals("A"))
                    .findFirst().orElseThrow().tugs();
            tugsForB = allocated.stream().filter(x -> x.slot().side().equals("B"))
                    .findFirst().orElseThrow().tugs();
        } catch (ApiException ex) {
            return failAndCommit(proposal, appA, appB, ex.getCode(), ex.getMessage());
        }

        // 7. 全部满足：原子切换双方占用（先删后插，遵守唯一约束）
        occupationRepository.deleteByApplicationId(appA.getId());
        occupationRepository.deleteByApplicationId(appB.getId());
        occupationRepository.flush();
        assignmentRepository.deleteByApplicationId(appA.getId());
        assignmentRepository.deleteByApplicationId(appB.getId());
        assignmentRepository.flush();

        occupationRepository.save(new BerthOccupation(slotA.berthId(), appA.getId(),
                slotA.start(), slotA.end()));
        occupationRepository.save(new BerthOccupation(slotB.berthId(), appB.getId(),
                slotB.start(), slotB.end()));

        List<TugAssignment> newAssignments = new ArrayList<>();
        for (Tug tug : tugsForA) {
            newAssignments.add(new TugAssignment(tug.getId(), appA.getId(),
                    BerthActionType.BERTHING, slotA.start()));
            newAssignments.add(new TugAssignment(tug.getId(), appA.getId(),
                    BerthActionType.UNBERTHING, slotA.end()));
        }
        for (Tug tug : tugsForB) {
            newAssignments.add(new TugAssignment(tug.getId(), appB.getId(),
                    BerthActionType.BERTHING, slotB.start()));
            newAssignments.add(new TugAssignment(tug.getId(), appB.getId(),
                    BerthActionType.UNBERTHING, slotB.end()));
        }
        if (!newAssignments.isEmpty()) {
            assignmentRepository.saveAll(newAssignments);
        }

        appA.applySwap(slotA.berthId(), slotA.start(), slotA.end());
        appB.applySwap(slotB.berthId(), slotB.start(), slotB.end());
        proposal.markConfirmed();

        historyRepository.save(new ChangeHistory(appA.getId(), appA.getApplicationNo(),
                HistoryAction.SWAPPED,
                        truncate(swapSuccessDetail(proposal, appA, berths, tugsForA, true)), Instant.now()));
        historyRepository.save(new ChangeHistory(appB.getId(), appB.getApplicationNo(),
                HistoryAction.SWAPPED,
                        truncate(swapSuccessDetail(proposal, appB, berths, tugsForB, false)), Instant.now()));
        return new Confirmation(proposal, appA, appB, true, null, null);
    }

    /** 交换后某一方在对方泊位/时段上的槽位。 */
    private record SwapSlot(BerthApplication app, Long berthId, Instant start, Instant end,
                            String side) {
    }

    private record Allocated(SwapSlot slot, List<Tug> tugs) {
    }

    private String detectStaleness(SwapProposal proposal, BerthApplication appA, BerthApplication appB) {
        if (appA.getStatus() != ApplicationStatus.APPROVED || appB.getStatus() != ApplicationStatus.APPROVED) {
            return "互换方案涉及的申请已不是已批准状态（可能已取消），方案失效";
        }
        if (appA.getVersion() != proposal.getAAppVersion()
                || appB.getVersion() != proposal.getBAppVersion()) {
            return "冻结后申请安排已发生改期或取消，申请版本变化，互换方案失效";
        }
        if (!appA.getAssignedBerthId().equals(proposal.getABerthId())
                || !appB.getAssignedBerthId().equals(proposal.getBBerthId())) {
            return "冻结后泊位安排已变化，互换方案失效";
        }
        List<BerthOccupation> occA = occupationRepository.findByApplicationId(appA.getId());
        List<BerthOccupation> occB = occupationRepository.findByApplicationId(appB.getId());
        if (occA.size() != 1 || occB.size() != 1
                || !occA.get(0).getStartTime().equals(proposal.getAEta())
                || !occA.get(0).getEndTime().equals(proposal.getAEtd())
                || !occB.get(0).getStartTime().equals(proposal.getBEta())
                || !occB.get(0).getEndTime().equals(proposal.getBEtd())) {
            return "冻结后泊位时段占用已变化，互换方案失效";
        }
        if (!Instant.now().isBefore(proposal.getBEta())
                || !Instant.now().isBefore(proposal.getAEta())) {
            return "互换后的时段已有一方开始作业，方案失效";
        }
        return null;
    }

    private Map<Long, Berth> lockBerths(Set<Long> berthIds) {
        return berthIds.stream().sorted()
                .map(id -> berthRepository.findWithLockById(id).orElseThrow(
                        () -> new ApiException(ErrorCode.BERTH_NOT_FOUND, "互换涉及的泊位不存在: " + id)))
                .collect(Collectors.toMap(Berth::getId, b -> b));
    }

    /**
     * 校验交换后某一方在目标泊位上的准入条件（类型/船型/吃水）与容量。
     * 容量统计排除互换双方（双方占用都将被重写），再叠加交换后落在该泊位上的新槽位数。
     */
    private void validateBerth(SwapProposal proposal, BerthApplication appA, BerthApplication appB,
                               SwapSlot slot, Map<Long, Berth> berths, List<SwapSlot> newSlots) {
        Berth target = berths.get(slot.berthId());
        String sideLabel = slot.side().equals("A") ? "A 方 " : "B 方 ";
        if (!target.getBerthType().equals(slot.app().getRequiredBerthType())) {
            throw new ApiException(ErrorCode.NO_MATCHING_BERTH,
                    "互换后" + sideLabel + slot.app().getApplicationNo() + " 所需泊位类型 "
                            + slot.app().getRequiredBerthType() + " 与对方泊位类型 "
                            + target.getBerthType() + " 不匹配，互换拒绝，原安排保留");
        }
        if (!target.accepts(slot.app().getVesselType())) {
            throw new ApiException(ErrorCode.NO_MATCHING_BERTH,
                    "互换后" + sideLabel + slot.app().getApplicationNo() + " 的船型 "
                            + slot.app().getVesselType() + " 不被对方泊位 " + target.getCode()
                            + " 接纳，互换拒绝，原安排保留");
        }
        if (target.getMaxDraft().compareTo(slot.app().getDraft()) < 0) {
            throw new ApiException(ErrorCode.NO_MATCHING_BERTH,
                    "互换后" + sideLabel + slot.app().getApplicationNo() + " 吃水 "
                            + slot.app().getDraft() + " 米超过对方泊位 " + target.getCode()
                            + " 最大吃水 " + target.getMaxDraft() + " 米，互换拒绝，原安排保留");
        }
        long others = occupationRepository.countOverlappingExcludingBoth(target.getId(),
                Set.of(proposal.getApplicationAId(), proposal.getApplicationBId()),
                slot.start(), slot.end());
        // 交换后落在同一泊位、且与本槽位时段重叠的槽位数（含本槽位自身）；
        // 同泊位但时段不重叠的槽位不占同时作业能力。
        long overlappingNew = newSlots.stream()
                .filter(s -> s.berthId().equals(target.getId()))
                .filter(s -> s.start().isBefore(slot.end()) && s.end().isAfter(slot.start()))
                .count();
        if (others + overlappingNew > target.getSimultaneousCapacity()) {
            throw new ApiException(ErrorCode.BERTH_CAPACITY_EXCEEDED,
                    "互换后" + sideLabel + slot.app().getApplicationNo() + " 在泊位 "
                            + target.getCode() + " 的新时段 " + slot.start() + " ~ " + slot.end()
                            + " 同时作业能力不足，互换拒绝，原安排保留");
        }
    }

    private void requireTideCoverage(BerthApplication app, Instant eta, Instant etd) {
        List<TideWindow> windows =
                tideWindowRepository.findForVerificationByBerthType(app.getRequiredBerthType());
        boolean etaCovered = windows.stream().anyMatch(w -> w.covers(eta)
                && w.getAvailableDepth().compareTo(app.getDraft()) >= 0);
        boolean etdCovered = windows.stream().anyMatch(w -> w.covers(etd)
                && w.getAvailableDepth().compareTo(app.getDraft()) >= 0);
        if (!etaCovered || !etdCovered) {
            List<String> missing = new ArrayList<>();
            if (!etaCovered) {
                missing.add("靠泊时刻 " + eta);
            }
            if (!etdCovered) {
                missing.add("离泊时刻 " + etd);
            }
            throw new ApiException(ErrorCode.TIDE_WINDOW_UNAVAILABLE,
                    "互换后申请 " + app.getApplicationNo() + " 的"
                            + String.join("、", missing) + " 没有满足吃水 " + app.getDraft()
                            + " 米的潮汐窗口，互换拒绝，原安排保留");
        }
    }

    /**
     * 在交换后的靠/离泊时刻为一方选取所需数量拖轮：同一艘拖轮不能在同一时刻协助两艘船，
     * 但双方时刻不同时可以复用；排除互换双方以外的既有占用与本次已分配占用。
     */
    private List<Tug> allocateTugs(List<Tug> allTugs, Map<Long, Set<Instant>> busyTimes,
                                   SwapSlot slot) {
        int required = slot.app().getRequiredTugs();
        if (required == 0) {
            return List.of();
        }
        List<Tug> free = allTugs.stream()
                .filter(t -> {
                    Set<Instant> taken = busyTimes.getOrDefault(t.getId(), Set.of());
                    return !taken.contains(slot.start()) && !taken.contains(slot.end());
                })
                .sorted(Comparator.comparing(Tug::getId))
                .limit(required)
                .toList();
        if (free.size() < required) {
            throw new ApiException(ErrorCode.INSUFFICIENT_TUGS,
                    "互换后" + slot.side() + " 方申请 " + slot.app().getApplicationNo()
                            + " 靠泊 " + slot.start() + "、离泊 " + slot.end() + " 所需的 "
                            + required + " 艘拖轮无法同时满足（当前可满足 " + free.size()
                            + " 艘），互换拒绝，原安排保留");
        }
        // 被选中的拖轮对本槽位两个时刻视为占用，供另一方分配时避让
        for (Tug tug : free) {
            busyTimes.computeIfAbsent(tug.getId(), k -> new HashSet<>()).add(slot.start());
            busyTimes.get(tug.getId()).add(slot.end());
        }
        return free;
    }

    /**
     * 校验失败落库：此时尚未做任何资源变更，在同一事务内把方案标记 FAILED、
     * 写入双方失败历史（含失败原因）并随事务提交。
     */
    private Confirmation failAndCommit(SwapProposal proposal, BerthApplication appA,
                                       BerthApplication appB, ErrorCode code, String reason) {
        proposal.markFailed(code.name(), reason);
        String detailA = "互换方案 " + proposal.getProposalNo()
                + " 确认失败，原安排（泊位 " + proposal.getABerthId() + "，时段 "
                + proposal.getAEta() + " ~ " + proposal.getAEtd() + "，拖轮 ["
                + proposal.getATugIds() + "]）保持可用；失败原因：" + reason;
        String detailB = "互换方案 " + proposal.getProposalNo()
                + " 确认失败，原安排（泊位 " + proposal.getBBerthId() + "，时段 "
                + proposal.getBEta() + " ~ " + proposal.getBEtd() + "，拖轮 ["
                + proposal.getBTugIds() + "]）保持可用；失败原因：" + reason;
        historyRepository.save(new ChangeHistory(appA.getId(), appA.getApplicationNo(),
                HistoryAction.SWAP_FAILED, truncate(detailA), Instant.now()));
        historyRepository.save(new ChangeHistory(appB.getId(), appB.getApplicationNo(),
                HistoryAction.SWAP_FAILED, truncate(detailB), Instant.now()));
        return new Confirmation(proposal, appA, appB, false, code, reason);
    }

    private static String truncate(String value) {
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private String swapSuccessDetail(SwapProposal proposal, BerthApplication app,
                                     Map<Long, Berth> berths, List<Tug> tugs, boolean isPartyA) {
        long oldBerthId = isPartyA ? proposal.getABerthId() : proposal.getBBerthId();
        Instant oldEta = isPartyA ? proposal.getAEta() : proposal.getBEta();
        Instant oldEtd = isPartyA ? proposal.getAEtd() : proposal.getBEtd();
        String oldTugs = isPartyA ? proposal.getATugIds() : proposal.getBTugIds();
        Berth newBerth = berths.get(app.getAssignedBerthId());
        String tugCodes = tugs.stream().map(Tug::getCode).collect(Collectors.joining(","));
        return "互换方案 " + proposal.getProposalNo() + " 确认成功：互换前 泊位 "
                + berthCode(berths, oldBerthId) + "，" + oldEta + " ~ " + oldEtd + "，拖轮 id ["
                + oldTugs + "]；互换后 泊位 " + newBerth.getCode() + "，" + app.getEta() + " ~ "
                + app.getEtd() + "，拖轮 " + tugs.size() + " 艘（" + tugCodes + "）";
    }

    private String berthCode(Map<Long, Berth> berths, Long berthId) {
        Berth berth = berths.get(berthId);
        return berth == null ? String.valueOf(berthId) : berth.getCode();
    }

    private static ErrorCode resolveCode(String name) {
        if (name == null) {
            return ErrorCode.SWAP_ALREADY_FINALIZED;
        }
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return ErrorCode.SWAP_ALREADY_FINALIZED;
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public SwapProposal getProposal(String proposalNo) {
        return proposalRepository.findByProposalNo(proposalNo)
                .orElseThrow(() -> new ApiException(ErrorCode.SWAP_NOT_FOUND,
                        "互换方案不存在: " + proposalNo));
    }

    @Transactional(readOnly = true)
    public List<SwapProposal> listProposals() {
        return proposalRepository.findAll();
    }
}
