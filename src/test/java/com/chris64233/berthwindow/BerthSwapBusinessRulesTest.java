package com.chris64233.berthwindow;

import static com.chris64233.berthwindow.TestFixtures.T0;
import static com.chris64233.berthwindow.TestFixtures.VESSEL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.HistoryAction;
import com.chris64233.berthwindow.domain.SwapProposal;
import com.chris64233.berthwindow.domain.SwapStatus;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.service.BerthSwapService;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 两艘已批准船舶互换泊位时段的业务规则：
 * 交换后按新条件重新校验泊位/潮汐/拖轮、失败保留原安排、版本变化失效、业务幂等与历史记录。
 */
class BerthSwapBusinessRulesTest extends AbstractIntegrationTest {

    @Autowired
    private BerthWindowService service;
    @Autowired
    private BerthSwapService swapService;
    @Autowired
    private BerthOccupationRepository occupationRepository;
    @Autowired
    private TugAssignmentRepository assignmentRepository;

    private static final BigDecimal DRAFT = new BigDecimal("10.00");
    private static final BigDecimal LIGHT_DRAFT = new BigDecimal("8.00");
    private static final BigDecimal DEPTH = new BigDecimal("12.00");

    // A/B 两个不重叠时段（均在同一天内）
    private static final Instant A_ETA = T0.plusSeconds(10 * 3600);
    private static final Instant A_ETD = T0.plusSeconds(12 * 3600);
    private static final Instant B_ETA = T0.plusSeconds(14 * 3600);
    private static final Instant B_ETD = T0.plusSeconds(16 * 3600);

    private Berth berth(String code, BigDecimal maxDraft, int capacity) {
        return berth(code, TestFixtures.CONTAINER, Set.of(VESSEL_TYPE), maxDraft, capacity);
    }

    private Berth berth(String code, String type, Set<String> vesselTypes,
                        BigDecimal maxDraft, int capacity) {
        return service.createBerth(code, code + "-泊位", type, vesselTypes, maxDraft, capacity);
    }

    private void tug(String code) {
        service.createTug(code, code + "-拖轮");
    }

    private BerthApplication approve(String no, Instant eta, Instant etd, int tugs,
                                     BigDecimal draft, String type) {
        service.submit(no, "V-" + no, VESSEL_TYPE, eta, etd, draft, type, tugs);
        return service.approve(no);
    }

    private BerthApplication approveContainer(String no, Instant eta, Instant etd, int tugs) {
        return approve(no, eta, etd, tugs, DRAFT, TestFixtures.CONTAINER);
    }

    /**
     * 建 b1、b2（各容量 1），并放一艘占位船占住 b1 的 B 时段，
     * 使 A 落在 b1、B 被迫落到 b2，构造“双方在不同泊位”的互换场景。
     */
    private Berth[] setupDistinctBerths(String blockerNo, String aNo, String bNo, int tugs,
                                        BigDecimal draftA, BigDecimal draftB) {
        Berth b1 = berth("B1", new BigDecimal("13.00"), 1);
        Berth b2 = berth("B2", new BigDecimal("13.00"), 1);
        // 占位船（小吃水、0 拖轮）占 b1 的 B 时段
        approve(blockerNo, B_ETA, B_ETD, 0, LIGHT_DRAFT, TestFixtures.CONTAINER);
        approve(aNo, A_ETA, A_ETD, tugs, draftA, TestFixtures.CONTAINER);
        approve(bNo, B_ETA, B_ETD, tugs, draftB, TestFixtures.CONTAINER);
        return new Berth[]{b1, b2};
    }

    // ---------- 成功：双方泊位/时段原子交换 ----------

    @Test
    void swap_success_atomicallyExchangesBerthsTimesAndTugs_real() {
        TestFixtures.wideTide(service, DEPTH);
        tug("T1");
        tug("T2");
        Berth b1 = berth("B1", new BigDecimal("13.00"), 1);
        Berth b2 = berth("B2", new BigDecimal("13.00"), 1);
        approve("BLK-1", B_ETA, B_ETD, 0, DRAFT, TestFixtures.CONTAINER);
        BerthApplication a = approveContainer("SW-1A", A_ETA, A_ETD, 1);
        BerthApplication b = approveContainer("SW-1B", B_ETA, B_ETD, 1);

        swapService.propose("PROP-1", "SW-1A", "SW-1B");
        BerthSwapService.Confirmation result = swapService.confirm("PROP-1");

        assertThat(result.success()).isTrue();

        BerthApplication newA = service.getApplication("SW-1A");
        BerthApplication newB = service.getApplication("SW-1B");
        // 时间与泊位整体互换，不只是交换两个时间字段
        assertThat(newA.getEta()).isEqualTo(B_ETA);
        assertThat(newA.getEtd()).isEqualTo(B_ETD);
        assertThat(newA.getAssignedBerthId()).isEqualTo(b2.getId());
        assertThat(newB.getEta()).isEqualTo(A_ETA);
        assertThat(newB.getEtd()).isEqualTo(A_ETD);
        assertThat(newB.getAssignedBerthId()).isEqualTo(b1.getId());

        List<BerthOccupation> occA = occupationRepository.findByApplicationId(a.getId());
        List<BerthOccupation> occB = occupationRepository.findByApplicationId(b.getId());
        assertThat(occA).hasSize(1);
        assertThat(occA.get(0).getBerthId()).isEqualTo(b2.getId());
        assertThat(occA.get(0).getStartTime()).isEqualTo(B_ETA);
        assertThat(occB.get(0).getBerthId()).isEqualTo(b1.getId());
        assertThat(occB.get(0).getStartTime()).isEqualTo(A_ETA);

        assertThat(assignmentRepository.findByApplicationId(a.getId()))
                .extracting(TugAssignment::getActionTime)
                .containsOnly(B_ETA, B_ETD);
        assertThat(assignmentRepository.findByApplicationId(b.getId()))
                .extracting(TugAssignment::getActionTime)
                .containsOnly(A_ETA, A_ETD);

        var historyA = service.findHistory("SW-1A");
        assertThat(historyA).extracting(ChangeHistory::getAction).endsWith(HistoryAction.SWAPPED);
        assertThat(historyA.get(historyA.size() - 1).getDetail())
                .contains("互换前").contains("互换后").contains("B2");
    }

    @Test
    void swap_success_sameBerth_exchangesTimesOnly() {
        // 同一泊位容量 2：双方同在 b1，互换时段（同一艘拖轮不同时刻可复用）
        TestFixtures.wideTide(service, DEPTH);
        tug("T1");
        Berth b1 = berth("B1", new BigDecimal("13.00"), 2);
        approveContainer("SW-2A", A_ETA, A_ETD, 1);
        approveContainer("SW-2B", B_ETA, B_ETD, 1);

        swapService.propose("PROP-2", "SW-2A", "SW-2B");
        BerthSwapService.Confirmation result = swapService.confirm("PROP-2");

        assertThat(result.success()).isTrue();
        assertThat(service.getApplication("SW-2A").getEta()).isEqualTo(B_ETA);
        assertThat(service.getApplication("SW-2B").getEta()).isEqualTo(A_ETA);
        // 仍在同一泊位
        assertThat(service.getApplication("SW-2A").getAssignedBerthId()).isEqualTo(b1.getId());
        assertThat(service.getApplication("SW-2B").getAssignedBerthId()).isEqualTo(b1.getId());
        // 唯一拖轮在 4 个互异时刻各一次，(拖轮,时刻) 无冲突
        List<TugAssignment> all = assignmentRepository.findAll();
        assertThat(all).hasSize(4);
        assertThat(all.stream().map(x -> x.getTugId() + "@" + x.getActionTime()).distinct()).hasSize(4);
    }

    // ---------- 失败：交换后条件不满足，双方原安排保持可用 ----------

    @Test
    void swap_failure_whenTideMissingAtNewTime_keepsBothOriginalArrangements() {
        // A 时段水深 12（覆盖吃水 10 的 A），B 时段水深 9（仅够小吃水 8 的 B）；
        // 交换后 A(吃水10) 进入 B 时段 -> 潮汐不满足
        service.createTideWindow(TestFixtures.CONTAINER, T0.plusSeconds(9 * 3600),
                T0.plusSeconds(13 * 3600), DEPTH);
        service.createTideWindow(TestFixtures.CONTAINER, T0.plusSeconds(13 * 3600),
                T0.plusSeconds(23 * 3600), new BigDecimal("9.00"));
        Berth[] bs = setupDistinctBerths("BLK-3", "SW-3A", "SW-3B", 0, DRAFT, LIGHT_DRAFT);
        Berth b1 = bs[0];
        Berth b2 = bs[1];

        swapService.propose("PROP-3", "SW-3A", "SW-3B");
        BerthSwapService.Confirmation result = swapService.confirm("PROP-3");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);
        assertOriginalArrangementKept("SW-3A", b1.getId(), A_ETA, A_ETD);
        assertOriginalArrangementKept("SW-3B", b2.getId(), B_ETA, B_ETD);
        SwapProposal stored = swapService.getProposal("PROP-3");
        assertThat(stored.getStatus()).isEqualTo(SwapStatus.FAILED);
        assertThat(stored.getFailureCode()).isEqualTo("TIDE_WINDOW_UNAVAILABLE");
        assertThat(stored.getFailureReason()).contains("SW-3A");

        var historyA = service.findHistory("SW-3A");
        assertThat(historyA).extracting(ChangeHistory::getAction)
                .contains(HistoryAction.SWAP_FAILED);
        assertThat(historyA.get(historyA.size() - 1).getDetail())
                .contains("保持可用").contains("失败原因");
    }

    @Test
    void swap_failure_whenDraftExceedsTargetBerth_keepsBothOriginalArrangements() {
        TestFixtures.wideTide(service, DEPTH);
        // 深水泊位先占满（占位船），小吃水 B 落到浅水泊位；大吃水 A 在深水泊位。
        Berth deep = berth("BD", new BigDecimal("13.00"), 1);
        Berth shallow = berth("BS", new BigDecimal("9.00"), 1);
        approve("BLK-5", B_ETA, B_ETD, 0, new BigDecimal("12.00"), TestFixtures.CONTAINER);
        approve("SW-5A", A_ETA, A_ETD, 0, new BigDecimal("12.00"), TestFixtures.CONTAINER);
        // B(吃水8) 进不去深泊位 B 时段（占位船占着），落到浅泊位
        approve("SW-5B", B_ETA, B_ETD, 0, LIGHT_DRAFT, TestFixtures.CONTAINER);

        swapService.propose("PROP-5", "SW-5A", "SW-5B");
        BerthSwapService.Confirmation result = swapService.confirm("PROP-5");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.NO_MATCHING_BERTH);
        assertThat(result.reason()).contains("吃水");
        assertOriginalArrangementKept("SW-5A", deep.getId(), A_ETA, A_ETD);
        assertThat(service.getApplication("SW-5B").getAssignedBerthId())
                .isEqualTo(shallow.getId());
    }

    @Test
    void swap_failure_whenBerthTypeMismatch_keepsBothOriginalArrangements() {
        TestFixtures.wideTide(service, DEPTH);
        service.createTideWindow("BULK", T0.plusSeconds(9 * 3600), T0.plusSeconds(23 * 3600), DEPTH);
        Berth container = berth("BC", TestFixtures.CONTAINER, Set.of(VESSEL_TYPE),
                new BigDecimal("13.00"), 1);
        Berth bulk = berth("BB", "BULK", Set.of(VESSEL_TYPE), new BigDecimal("13.00"), 1);
        approveContainer("SW-6A", A_ETA, A_ETD, 0);
        approve("SW-6B", B_ETA, B_ETD, 0, DRAFT, "BULK");

        swapService.propose("PROP-6", "SW-6A", "SW-6B");
        BerthSwapService.Confirmation result = swapService.confirm("PROP-6");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.NO_MATCHING_BERTH);
        assertThat(result.reason()).contains("泊位类型");
        assertOriginalArrangementKept("SW-6A", container.getId(), A_ETA, A_ETD);
        assertOriginalArrangementKept("SW-6B", bulk.getId(), B_ETA, B_ETD);
    }

    @Test
    void swap_failure_whenTugsInsufficientAtNewTime_keepsBothOriginalArrangements() {
        // b1 容量 2、b2 容量 1；2 艘拖轮。
        // A 需 2 艘在 A 时段；占位船与 C 占住 b1 的 B 时段（C 用 1 艘拖轮），把 B 逼到 b2。
        TestFixtures.wideTide(service, DEPTH);
        tug("T1");
        tug("T2");
        Berth b1 = berth("B1", new BigDecimal("13.00"), 2);
        Berth b2 = berth("B2", new BigDecimal("13.00"), 1);
        approve("BLK-7", B_ETA, B_ETD, 0, DRAFT, TestFixtures.CONTAINER); // 占位 0 拖轮
        approveContainer("SW-7A", A_ETA, A_ETD, 2);
        approveContainer("SW-7C", B_ETA, B_ETD, 1);                       // B 时段占用 1 艘
        approveContainer("SW-7B", B_ETA, B_ETD, 1);                       // 被逼到 b2

        swapService.propose("PROP-7", "SW-7A", "SW-7B");
        BerthSwapService.Confirmation result = swapService.confirm("PROP-7");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.INSUFFICIENT_TUGS);
        assertOriginalArrangementKept("SW-7A", b1.getId(), A_ETA, A_ETD);
        assertOriginalArrangementKept("SW-7B", b2.getId(), B_ETA, B_ETD);
        // A 4 条 + B 2 条 + C 2 条（占位 0），原安排不变
        assertThat(assignmentRepository.findAll()).hasSize(8);
    }

    // ---------- 版本变化：旧方案失效 ----------

    @Test
    void swap_stale_afterReschedule_confirmRejectsAsStale_andKeepsCurrentArrangement() {
        TestFixtures.wideTide(service, DEPTH);
        Berth[] bs = setupDistinctBerths("BLK-8", "SW-8A", "SW-8B", 0, DRAFT, DRAFT);
        swapService.propose("PROP-8", "SW-8A", "SW-8B");

        Instant newEta = A_ETA.plusSeconds(30 * 60);
        Instant newEtd = A_ETD.plusSeconds(30 * 60);
        service.reschedule("SW-8A", newEta, newEtd);

        BerthSwapService.Confirmation result = swapService.confirm("PROP-8");
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.SWAP_PROPOSAL_STALE);
        assertThat(result.reason()).contains("版本");
        assertThat(swapService.getProposal("PROP-8").getStatus()).isEqualTo(SwapStatus.FAILED);
        assertThat(service.getApplication("SW-8A").getEta()).isEqualTo(newEta);
        assertOriginalArrangementKept("SW-8B", bs[1].getId(), B_ETA, B_ETD);
    }

    @Test
    void swap_stale_afterCancellation_confirmRejectsAsStale() {
        TestFixtures.wideTide(service, DEPTH);
        Berth[] bs = setupDistinctBerths("BLK-9", "SW-9A", "SW-9B", 0, DRAFT, DRAFT);
        swapService.propose("PROP-9", "SW-9A", "SW-9B");

        service.cancel("SW-9A");
        assertThat(service.getApplication("SW-9A").getStatus()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(occupationRepository.findByApplicationId(
                service.getApplication("SW-9A").getId())).isEmpty();

        BerthSwapService.Confirmation result = swapService.confirm("PROP-9");
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.SWAP_PROPOSAL_STALE);
        assertOriginalArrangementKept("SW-9B", bs[1].getId(), B_ETA, B_ETD);
    }

    @Test
    void swap_stale_afterTideDataUpdate_confirmRejectsAsStale() {
        var window = TestFixtures.wideTide(service, DEPTH);
        Berth[] bs = setupDistinctBerths("BLK-10", "SW-10A", "SW-10B", 0, DRAFT, DRAFT);
        swapService.propose("PROP-10", "SW-10A", "SW-10B");

        // 冻结后潮汐预报更新（即使新水深仍满足吃水，版本签名已变化）
        service.updateTideDepth(window.getId(), new BigDecimal("12.50"), null);

        BerthSwapService.Confirmation result = swapService.confirm("PROP-10");
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.SWAP_PROPOSAL_STALE);
        assertThat(result.reason()).contains("潮汐");
        assertOriginalArrangementKept("SW-10A", bs[0].getId(), A_ETA, A_ETD);
        assertOriginalArrangementKept("SW-10B", bs[1].getId(), B_ETA, B_ETD);
    }

    // ---------- 幂等 ----------

    @Test
    void duplicatePropose_isIdempotent_returnsSameProposal() {
        TestFixtures.wideTide(service, DEPTH);
        setupDistinctBerths("BLK-11", "SW-11A", "SW-11B", 0, DRAFT, DRAFT);

        SwapProposal first = swapService.propose("PROP-11", "SW-11A", "SW-11B");
        SwapProposal second = swapService.propose("PROP-11", "SW-11A", "SW-11B");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(swapService.listProposals()).hasSize(1);
    }

    @Test
    void duplicatePropose_withDifferentParticipants_conflicts() {
        TestFixtures.wideTide(service, DEPTH);
        setupDistinctBerths("BLK-12", "SW-12A", "SW-12B", 0, DRAFT, DRAFT);
        // 第三艘船放在 A 时段的 b2（b1 被 A 占，b2 在 A 时段空闲）
        approveContainer("SW-12C", A_ETA, A_ETD, 0);

        swapService.propose("PROP-12", "SW-12A", "SW-12B");
        assertThatThrownBy(() -> swapService.propose("PROP-12", "SW-12A", "SW-12C"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_BUSINESS_KEY);
    }

    @Test
    void duplicateConfirm_afterSuccess_isIdempotent_andDoesNotSwapAgain() {
        TestFixtures.wideTide(service, DEPTH);
        Berth[] bs = setupDistinctBerths("BLK-13", "SW-13A", "SW-13B", 0, DRAFT, DRAFT);
        swapService.propose("PROP-13", "SW-13A", "SW-13B");

        BerthSwapService.Confirmation first = swapService.confirm("PROP-13");
        BerthSwapService.Confirmation again = swapService.confirm("PROP-13");
        assertThat(first.success()).isTrue();
        assertThat(again.success()).isTrue();
        // 再次确认不会重复交换（交换两次会还原时间）
        assertThat(service.getApplication("SW-13A").getEta()).isEqualTo(B_ETA);
        assertThat(service.getApplication("SW-13B").getEta()).isEqualTo(A_ETA);
        assertThat(occupationRepository.count()).isEqualTo(3); // A、B + 占位船
        assertThat(bs).isNotEmpty();
    }

    @Test
    void duplicateConfirm_afterFailure_returnsSameFailure_andWritesHistoryOnce() {
        service.createTideWindow(TestFixtures.CONTAINER, T0.plusSeconds(9 * 3600),
                T0.plusSeconds(13 * 3600), DEPTH);
        service.createTideWindow(TestFixtures.CONTAINER, T0.plusSeconds(13 * 3600),
                T0.plusSeconds(23 * 3600), new BigDecimal("9.00"));
        setupDistinctBerths("BLK-14", "SW-14A", "SW-14B", 0, DRAFT, LIGHT_DRAFT);
        swapService.propose("PROP-14", "SW-14A", "SW-14B");

        assertThat(swapService.confirm("PROP-14").code()).isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);
        BerthSwapService.Confirmation again = swapService.confirm("PROP-14");
        assertThat(again.success()).isFalse();
        assertThat(again.code()).isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);
        long failedHistory = service.findHistory("SW-14A").stream()
                .filter(h -> h.getAction() == HistoryAction.SWAP_FAILED).count();
        assertThat(failedHistory).isEqualTo(1);
    }

    // ---------- 前置校验 ----------

    @Test
    void propose_rejectsSameApplication() {
        TestFixtures.wideTide(service, DEPTH);
        berth("B1", new BigDecimal("13.00"), 1);
        approveContainer("SW-15A", A_ETA, A_ETD, 0);

        assertThatThrownBy(() -> swapService.propose("PROP-15", "SW-15A", "SW-15A"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_SAME_APPLICATION);
    }

    @Test
    void propose_rejectsUnapprovedOrMissingApplication() {
        TestFixtures.wideTide(service, DEPTH);
        berth("B1", new BigDecimal("13.00"), 1);
        berth("B2", new BigDecimal("13.00"), 1);
        service.submit("SW-16P", "V", VESSEL_TYPE, A_ETA, A_ETD, DRAFT, TestFixtures.CONTAINER, 0);
        approveContainer("SW-16B", B_ETA, B_ETD, 0);

        assertThatThrownBy(() -> swapService.propose("PROP-16", "SW-16P", "SW-16B"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.APPLICATION_NOT_APPROVED);
        assertThatThrownBy(() -> swapService.propose("PROP-16X", "NO-SUCH", "SW-16B"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.APPLICATION_NOT_FOUND);
    }

    @Test
    void confirm_unknownProposal_returnsNotFound() {
        assertThatThrownBy(() -> swapService.confirm("NO-PROP"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_NOT_FOUND);
    }

    private void assertOriginalArrangementKept(String applicationNo, Long berthId,
                                               Instant eta, Instant etd) {
        BerthApplication app = service.getApplication(applicationNo);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getAssignedBerthId()).isEqualTo(berthId);
        assertThat(app.getEta()).isEqualTo(eta);
        assertThat(app.getEtd()).isEqualTo(etd);
        List<BerthOccupation> occupations = occupationRepository.findByApplicationId(app.getId());
        assertThat(occupations).hasSize(1);
        assertThat(occupations.get(0).getBerthId()).isEqualTo(berthId);
        assertThat(occupations.get(0).getStartTime()).isEqualTo(eta);
        assertThat(occupations.get(0).getEndTime()).isEqualTo(etd);
    }
}
