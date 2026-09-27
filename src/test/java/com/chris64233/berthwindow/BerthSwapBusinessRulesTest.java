package com.chris64233.berthwindow;

import static com.chris64233.berthwindow.TestFixtures.ETD;
import static com.chris64233.berthwindow.TestFixtures.ETA;
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
import com.chris64233.berthwindow.domain.HistoryAction;
import com.chris64233.berthwindow.domain.SwapProposalStatus;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.service.BerthSwapService;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 泊位时段互换业务规则：
 * 提议冻结 + 交换后条件预校验；确认原子交换、失败保留原安排；
 * 改期/取消/潮汐更新使旧方案失效；提议与确认的业务幂等；互换前后安排与失败原因留痕。
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

    private final BigDecimal draft = new BigDecimal("10.00");
    private final BigDecimal depth = new BigDecimal("12.00");

    private final Instant etaB = T0.plusSeconds(12 * 3600);
    private final Instant etdB = T0.plusSeconds(22 * 3600);

    /** 两个容量 1 的集装箱泊位、2 艘拖轮、覆盖全天的潮汐，并批准 SW-A/SW-B 各占一个泊位。 */
    private record ApprovedPair(Berth b1, Berth b2, BerthApplication a, BerthApplication b) {
    }

    private ApprovedPair prepareTwoApprovedOnSeparateBerths() {
        Berth b1 = TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        Berth b2 = TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        BerthApplication a = TestFixtures.submit(service, "SW-A", 1, draft, ETA, ETD);
        BerthApplication b = TestFixtures.submit(service, "SW-B", 1, draft, etaB, etdB);
        service.approve("SW-A");
        service.approve("SW-B");
        return new ApprovedPair(b1, b2, a, b);
    }

    // ---------- 提议：冻结并按交换后条件预校验 ----------

    @Test
    void propose_freezesBothSides_andPrevalidatesSwappedConditions() {
        var pair = prepareTwoApprovedOnSeparateBerths();

        var proposal = swapService.propose("SWAP-1", "SW-A", "SW-B");

        assertThat(proposal.getStatus()).isEqualTo(SwapProposalStatus.PROPOSED);
        assertThat(proposal.sideA().getApplicationNo()).isEqualTo("SW-A");
        assertThat(proposal.sideA().getFromBerthId()).isEqualTo(pair.b1().getId());
        assertThat(proposal.sideA().getToBerthId()).isEqualTo(pair.b2().getId());
        assertThat(proposal.sideA().getToEta()).isEqualTo(etaB);
        assertThat(proposal.sideA().getToEtd()).isEqualTo(etdB);
        assertThat(proposal.sideB().getFromBerthId()).isEqualTo(pair.b2().getId());
        assertThat(proposal.sideB().getToBerthId()).isEqualTo(pair.b1().getId());
        assertThat(proposal.sideB().getToEta()).isEqualTo(ETA);
        // 冻结了潮汐窗口及其版本号
        assertThat(proposal.getTideSnapshots()).hasSize(1);
        assertThat(proposal.getTideSnapshots().get(0).getVersion()).isGreaterThanOrEqualTo(0);
        // 冻结了交换后拖轮安排
        assertThat(proposal.sideA().getTugIds()).hasSize(1);
        assertThat(proposal.sideB().getTugIds()).hasSize(1);
        // 提议阶段原安排不受影响
        assertThat(occupationRepository.findByApplicationId(pair.a().getId()))
                .singleElement().satisfies(o -> {
                    assertThat(o.getBerthId()).isEqualTo(pair.b1().getId());
                    assertThat(o.getStartTime()).isEqualTo(ETA);
                });
    }

    @Test
    void propose_isIdempotent_bySwapNoAndPair() {
        prepareTwoApprovedOnSeparateBerths();

        var first = swapService.propose("SWAP-IDEM", "SW-A", "SW-B");
        var second = swapService.propose("SWAP-IDEM", "SW-B", "SW-A"); // 顺序颠倒视为同一对

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(swapService.listProposals()).hasSize(1);
        // 只有首次提议各写一条历史
        assertThat(service.findHistory("SW-A").stream()
                .filter(h -> h.getAction() == HistoryAction.SWAP_PROPOSED).count()).isEqualTo(1);
    }

    @Test
    void propose_sameNoDifferentPair_isRejected() {
        prepareTwoApprovedOnSeparateBerths();
        swapService.propose("SWAP-DUP", "SW-A", "SW-B");
        // 第三方在不重叠的更晚时段批准（两个容量 1 的泊位都可用）
        TestFixtures.containerBerth(service, "B3", new BigDecimal("13.00"), 1);
        TestFixtures.tide(service, T0.plusSeconds(23 * 3600), T0.plusSeconds(31 * 3600), depth);
        TestFixtures.submit(service, "SW-C", 0, draft,
                T0.plusSeconds(24 * 3600), T0.plusSeconds(30 * 3600));
        service.approve("SW-C");

        assertThatThrownBy(() -> swapService.propose("SWAP-DUP", "SW-A", "SW-C"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_BUSINESS_KEY);
    }

    @Test
    void propose_sameApplicationPair_isRejected() {
        prepareTwoApprovedOnSeparateBerths();

        assertThatThrownBy(() -> swapService.propose("SWAP-SELF", "SW-A", "SW-A"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_INVALID_PAIR);
    }

    @Test
    void propose_whenSideNotApproved_isRejected() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "PEND-A", 0, draft, ETA, ETD);
        TestFixtures.submit(service, "PEND-B", 0, draft, etaB, etdB);
        service.approve("PEND-A");

        assertThatThrownBy(() -> swapService.propose("SWAP-PEND", "PEND-A", "PEND-B"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_INVALID_PAIR);
    }

    @Test
    void propose_whenSwappedBerthDoesNotAcceptVessel_isRejected() {
        // B2 只接纳 PANAMAX：SW-B 以 PANAMAX 批在 B2，SW-A（FEEDER）换过去不满足泊位条件
        service.createBerth("B1", "B1-泊位", TestFixtures.CONTAINER,
                Set.of(VESSEL_TYPE), new BigDecimal("13.00"), 1);
        service.createBerth("B2", "B2-泊位", TestFixtures.CONTAINER,
                Set.of("PANAMAX"), new BigDecimal("13.00"), 1);
        TestFixtures.wideTide(service, depth);
        service.submit("SW-A", "VA", VESSEL_TYPE, ETA, ETD, draft, TestFixtures.CONTAINER, 0);
        service.submit("SW-B", "VB", "PANAMAX", etaB, etdB, draft, TestFixtures.CONTAINER, 0);
        service.approve("SW-A");
        service.approve("SW-B");

        assertThatThrownBy(() -> swapService.propose("SWAP-VT", "SW-A", "SW-B"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.NO_MATCHING_BERTH);
        // 提议失败不留方案、不动占用
        assertThat(swapService.listProposals()).isEmpty();
        assertThat(occupationRepository.findAll()).hasSize(2);
    }

    @Test
    void propose_whenSwappedWindowLacksTideForDraft_isRejected() {
        // SW-B 吃水 9 米，靠 23h~31h 水深 9 米的窗口批在 B2；SW-A 吃水 10 米换过去无满足潮汐
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        TestFixtures.wideTide(service, depth);
        TestFixtures.tide(service, T0.plusSeconds(23 * 3600), T0.plusSeconds(31 * 3600),
                new BigDecimal("9.00"));
        service.submit("SW-A", "VA", VESSEL_TYPE, ETA, ETD, draft, TestFixtures.CONTAINER, 0);
        service.submit("SW-B", "VB", VESSEL_TYPE,
                T0.plusSeconds(24 * 3600), T0.plusSeconds(30 * 3600),
                new BigDecimal("9.00"), TestFixtures.CONTAINER, 0);
        service.approve("SW-A");
        service.approve("SW-B");

        assertThatThrownBy(() -> swapService.propose("SWAP-TIDE", "SW-A", "SW-B"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);
    }

    @Test
    void propose_whenSwappedScheduleCannotGetTugs_isRejected() {
        // SW-A 需 2 艘（10~20），SW-B 需 0 艘（12~22）；第三方 GRAB 在 12 点占走 T1。
        // 互换后 SW-A 要在 12/22 两个时刻同时使用同一批 2 艘拖轮，12 点仅剩 1 艘 -> 拒绝。
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        TestFixtures.containerBerth(service, "B3", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "SW-A", 2, draft, ETA, ETD);
        TestFixtures.submit(service, "SW-B", 0, draft, etaB, etdB);
        TestFixtures.submit(service, "SW-GRAB", 1, draft, etaB, etaB.plusSeconds(3600));
        service.approve("SW-A");
        service.approve("SW-B");
        service.approve("SW-GRAB");

        assertThatThrownBy(() -> swapService.propose("SWAP-TUG", "SW-A", "SW-B"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_TUGS);
        assertThat(swapService.listProposals()).isEmpty();
        // 原两份安排保持不变
        assertThat(service.getApplication("SW-A").getEta()).isEqualTo(ETA);
        assertThat(occupationRepository.findAll()).hasSize(3);
    }

    // ---------- 确认：原子交换 ----------

    @Test
    void confirm_success_atomicallySwapsBerthsTimesAndTugs() {
        var pair = prepareTwoApprovedOnSeparateBerths();
        long versionABefore = pair.a().getVersion();
        long versionBBefore = pair.b().getVersion();
        swapService.propose("SWAP-OK", "SW-A", "SW-B");

        var confirmed = swapService.confirm("SWAP-OK");

        assertThat(confirmed.getStatus()).isEqualTo(SwapProposalStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();

        BerthApplication a = service.getApplication("SW-A");
        BerthApplication b = service.getApplication("SW-B");
        // 双方互换泊位与时段
        assertThat(a.getAssignedBerthId()).isEqualTo(pair.b2().getId());
        assertThat(a.getEta()).isEqualTo(etaB);
        assertThat(a.getEtd()).isEqualTo(etdB);
        assertThat(b.getAssignedBerthId()).isEqualTo(pair.b1().getId());
        assertThat(b.getEta()).isEqualTo(ETA);
        assertThat(b.getEtd()).isEqualTo(ETD);
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        // 申请版本随交换提升
        assertThat(a.getVersion()).isGreaterThan(versionABefore);
        assertThat(b.getVersion()).isGreaterThan(versionBBefore);

        List<BerthOccupation> occA = occupationRepository.findByApplicationId(a.getId());
        List<BerthOccupation> occB = occupationRepository.findByApplicationId(b.getId());
        assertThat(occA).hasSize(1);
        assertThat(occA.get(0).getBerthId()).isEqualTo(pair.b2().getId());
        assertThat(occA.get(0).getStartTime()).isEqualTo(etaB);
        assertThat(occA.get(0).getEndTime()).isEqualTo(etdB);
        assertThat(occB.get(0).getBerthId()).isEqualTo(pair.b1().getId());
        assertThat(occB.get(0).getStartTime()).isEqualTo(ETA);
        assertThat(occB.get(0).getEndTime()).isEqualTo(ETD);

        List<TugAssignment> tugsA = assignmentRepository.findByApplicationId(a.getId());
        assertThat(tugsA).hasSize(2);
        assertThat(tugsA).extracting(TugAssignment::getActionTime).containsOnly(etaB, etdB);
        assertThat(assignmentRepository.findByApplicationId(b.getId()))
                .extracting(TugAssignment::getActionTime).containsOnly(ETA, ETD);

        var historyA = service.findHistory("SW-A");
        assertThat(historyA).extracting(h -> h.getAction()).containsExactly(
                HistoryAction.SUBMITTED, HistoryAction.APPROVED,
                HistoryAction.SWAP_PROPOSED, HistoryAction.SWAP_CONFIRMED);
        assertThat(historyA.get(3).getDetail()).contains("B1").contains("B2");
    }

    @Test
    void confirm_isIdempotent_doesNotSwapTwice() {
        var pair = prepareTwoApprovedOnSeparateBerths();
        swapService.propose("SWAP-RPT", "SW-A", "SW-B");

        var first = swapService.confirm("SWAP-RPT");
        var second = swapService.confirm("SWAP-RPT");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(SwapProposalStatus.CONFIRMED);
        // 只有两份占用、各一条；SWAP_CONFIRMED 每方仅一条
        assertThat(occupationRepository.findAll()).hasSize(2);
        assertThat(occupationRepository.findByApplicationId(pair.a().getId())).hasSize(1);
        assertThat(occupationRepository.findByApplicationId(pair.b().getId())).hasSize(1);
        assertThat(service.findHistory("SW-A").stream()
                .filter(h -> h.getAction() == HistoryAction.SWAP_CONFIRMED).count()).isEqualTo(1);
        assertThat(service.findHistory("SW-B").stream()
                .filter(h -> h.getAction() == HistoryAction.SWAP_CONFIRMED).count()).isEqualTo(1);
    }

    // ---------- 确认失败：原两份安排保持可用，并记录失败原因 ----------

    @Test
    void confirm_whenTugsTakenByThirdParty_fails_andKeepsBothOriginalArrangements() {
        // 3 艘拖轮：SW-A 需 2 艘（10/20 占 T1,T2），SW-B 需 1 艘（12/22 占 T1）。
        // 互换方案冻结 SW-A 换到 12/22 时使用 T1,T2；随后第三方在第三泊位以 12/22 占走 T2。
        Berth b1 = TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        Berth b2 = TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        TestFixtures.containerBerth(service, "B3", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.createTug(service, "T3");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "SW-A", 2, draft, ETA, ETD);
        TestFixtures.submit(service, "SW-B", 1, draft, etaB, etdB);
        service.approve("SW-A");
        service.approve("SW-B");
        swapService.propose("SWAP-FAIL-TUG", "SW-A", "SW-B");
        var pair = new ApprovedPair(b1, b2, service.getApplication("SW-A"), service.getApplication("SW-B"));
        Long occAId = occupationRepository.findByApplicationId(pair.a().getId()).get(0).getId();
        Long occBId = occupationRepository.findByApplicationId(pair.b().getId()).get(0).getId();

        // 第三方在交换后时刻 12:00/22:00 作业（12:00 仅 T2、T3 空闲），占走冻结给 SW-A 的 T2
        service.submit("SW-GRAB", "VG", VESSEL_TYPE, etaB, etdB, draft, TestFixtures.CONTAINER, 1);
        service.approve("SW-GRAB");

        assertThatThrownBy(() -> swapService.confirm("SWAP-FAIL-TUG"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_TUGS);

        // 方案 FAILED 且带原因
        var failed = swapService.getProposal("SWAP-FAIL-TUG");
        assertThat(failed.getStatus()).isEqualTo(SwapProposalStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("拖轮");

        // 原两份安排原样可用：占用 id、泊位、时间不变
        BerthApplication a = service.getApplication("SW-A");
        BerthApplication b = service.getApplication("SW-B");
        assertThat(a.getAssignedBerthId()).isEqualTo(pair.b1().getId());
        assertThat(a.getEta()).isEqualTo(ETA);
        assertThat(b.getAssignedBerthId()).isEqualTo(pair.b2().getId());
        assertThat(b.getEta()).isEqualTo(etaB);
        var occA = occupationRepository.findByApplicationId(a.getId());
        var occB = occupationRepository.findByApplicationId(b.getId());
        assertThat(occA).singleElement().satisfies(o -> assertThat(o.getId()).isEqualTo(occAId));
        assertThat(occB).singleElement().satisfies(o -> assertThat(o.getBerthId()).isEqualTo(pair.b2().getId()));
        assertThat(occB.get(0).getId()).isEqualTo(occBId);
        // 原拖轮安排不变（A 需 2 艘共 4 艘次，B 需 1 艘共 2 艘次，第三方 2 艘次）
        assertThat(assignmentRepository.findByApplicationId(a.getId()))
                .extracting(TugAssignment::getActionTime).containsOnly(ETA, ETD);
        assertThat(assignmentRepository.findAll()).hasSize(8);

        // 双方各留失败历史，含互换前后安排与原因
        var historyA = service.findHistory("SW-A");
        assertThat(historyA).extracting(h -> h.getAction()).endsWith(HistoryAction.SWAP_FAILED);
        assertThat(historyA.get(historyA.size() - 1).getDetail())
                .contains("互换确认失败").contains("原安排保留").contains("拖轮");
        assertThat(service.findHistory("SW-B"))
                .extracting(h -> h.getAction()).endsWith(HistoryAction.SWAP_FAILED);

        // 失败方案再次确认不重复留痕、不交换
        var again = swapService.confirm("SWAP-FAIL-TUG");
        assertThat(again.getStatus()).isEqualTo(SwapProposalStatus.FAILED);
        assertThat(service.findHistory("SW-A").stream()
                .filter(h -> h.getAction() == HistoryAction.SWAP_FAILED).count()).isEqualTo(1);
    }

    // ---------- 版本变化：改期 / 取消 / 潮汐更新使旧方案失效 ----------

    @Test
    void confirm_afterReschedule_isStale_andKeepsOtherSideIntact() {
        var pair = prepareTwoApprovedOnSeparateBerths();
        swapService.propose("SWAP-RS", "SW-A", "SW-B");

        Instant newEta = ETA.plusSeconds(2 * 3600);
        Instant newEtd = ETD.plusSeconds(2 * 3600);
        service.reschedule("SW-A", newEta, newEtd);

        assertThatThrownBy(() -> swapService.confirm("SWAP-RS"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_STALE);

        var proposal = swapService.getProposal("SWAP-RS");
        assertThat(proposal.getStatus()).isEqualTo(SwapProposalStatus.FAILED);
        assertThat(proposal.getFailureReason()).contains("改期");
        // B 方原安排保留
        BerthApplication b = service.getApplication("SW-B");
        assertThat(b.getAssignedBerthId()).isEqualTo(pair.b2().getId());
        assertThat(occupationRepository.findByApplicationId(b.getId()))
                .singleElement().satisfies(o -> {
                    assertThat(o.getStartTime()).isEqualTo(etaB);
                    assertThat(o.getEndTime()).isEqualTo(etdB);
                });
    }

    @Test
    void confirm_afterCancel_isStale_andResourcesReleased() {
        var pair = prepareTwoApprovedOnSeparateBerths();
        swapService.propose("SWAP-CX", "SW-A", "SW-B");

        service.cancel("SW-A");

        assertThat(service.getApplication("SW-A").getStatus()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(occupationRepository.findByApplicationId(pair.a().getId())).isEmpty();

        assertThatThrownBy(() -> swapService.confirm("SWAP-CX"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_STALE);

        // B 方原安排保留
        BerthApplication b = service.getApplication("SW-B");
        assertThat(b.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(occupationRepository.findByApplicationId(b.getId())).hasSize(1);
        assertThat(service.findHistory("SW-A"))
                .extracting(h -> h.getAction()).contains(HistoryAction.CANCELLED);
    }

    @Test
    void cancel_releasesOccupationAndTugs() {
        var pair = prepareTwoApprovedOnSeparateBerths();

        service.cancel("SW-A");

        assertThat(occupationRepository.findByApplicationId(pair.a().getId())).isEmpty();
        assertThat(assignmentRepository.findByApplicationId(pair.a().getId())).isEmpty();
        // 他船仍可用释放出的窗口
        TestFixtures.submit(service, "SW-CANCEL-NEW", 1, draft, ETA, ETD);
        BerthApplication newcomer = service.approve("SW-CANCEL-NEW");
        assertThat(newcomer.getAssignedBerthId()).isEqualTo(pair.b1().getId());
    }

    @Test
    void confirm_afterTideDataUpdate_isStale() {
        prepareTwoApprovedOnSeparateBerths();
        var proposal = swapService.propose("SWAP-TIDEUP", "SW-A", "SW-B");
        TideWindow window = service.listTideWindows(TestFixtures.CONTAINER).get(0);

        // 水深仍满足吃水，但版本已变化——旧判断结果失效
        service.updateTideDepth(window.getId(), new BigDecimal("11.50"), null);

        assertThatThrownBy(() -> swapService.confirm("SWAP-TIDEUP"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_STALE);
        assertThat(swapService.getProposal(proposal.getSwapNo()).getStatus())
                .isEqualTo(SwapProposalStatus.FAILED);
        // 原安排保留
        assertThat(service.getApplication("SW-A").getEta()).isEqualTo(ETA);
        assertThat(occupationRepository.findAll()).hasSize(2);
    }

    @Test
    void confirm_unknownSwapNo_isNotFound() {
        assertThatThrownBy(() -> swapService.confirm("NO-SUCH-SWAP"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SWAP_NOT_FOUND);
    }
}
