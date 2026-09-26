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
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.HistoryAction;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

class BerthWindowBusinessRulesTest extends AbstractIntegrationTest {

    @Autowired
    private BerthWindowService service;
    @Autowired
    private BerthOccupationRepository occupationRepository;
    @Autowired
    private TugAssignmentRepository assignmentRepository;

    private final BigDecimal draft = new BigDecimal("10.00");
    private final BigDecimal depth = new BigDecimal("12.00");

    // ---------- 申请幂等 ----------

    @Test
    void duplicateApplicationNo_isIdempotent_returnsSameApplication() {
        TestFixtures.wideTide(service, depth);
        var first = service.submit("IDEM-1", "V1", VESSEL_TYPE, ETA, ETD, draft,
                TestFixtures.CONTAINER, 1);
        var second = service.submit("IDEM-1", "V1", VESSEL_TYPE, ETA, ETD, draft,
                TestFixtures.CONTAINER, 1);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(service.listApplications()).hasSize(1);
        var history = service.findHistory("IDEM-1");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo(HistoryAction.SUBMITTED);
    }

    @Test
    void duplicateApplicationNo_withDifferentPayload_isRejected() {
        TestFixtures.wideTide(service, depth);
        service.submit("IDEM-2", "V1", VESSEL_TYPE, ETA, ETD, draft,
                TestFixtures.CONTAINER, 1);

        assertThatThrownBy(() -> service.submit("IDEM-2", "V1", VESSEL_TYPE, ETA, ETD,
                new BigDecimal("11.00"), TestFixtures.CONTAINER, 1))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_BUSINESS_KEY);
    }

    // ---------- 审批：泊位 + 拖轮同时占用，任一不足整体拒绝 ----------

    @Test
    void approve_occupiesBerthAndTugsAtomically() {
        var berth = TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "APP-1", 2, draft);

        var approved = service.approve("APP-1");

        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(approved.getAssignedBerthId()).isEqualTo(berth.getId());
        assertThat(occupationRepository.findByApplicationId(approved.getId()))
                .hasSize(1)
                .first()
                .satisfies(o -> {
                    assertThat(o.getStartTime()).isEqualTo(ETA);
                    assertThat(o.getEndTime()).isEqualTo(ETD);
                });
        // 2 艘拖轮 × 靠/离泊两个时刻 = 4 条占用
        List<TugAssignment> assignments = assignmentRepository.findByApplicationId(approved.getId());
        assertThat(assignments).hasSize(4);
        assertThat(assignments).extracting(TugAssignment::getActionTime)
                .containsOnly(ETA, ETD);
    }

    @Test
    void approve_whenTugsInsufficient_rejectsEverything_noBerthReserved() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "APP-2", 2, draft);

        assertThatThrownBy(() -> service.approve("APP-2"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_TUGS);

        var app = service.getApplication("APP-2");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(app.getAssignedBerthId()).isNull();
        assertThat(occupationRepository.findAll()).isEmpty();
        assertThat(assignmentRepository.findAll()).isEmpty();
    }

    @Test
    void approve_whenBerthCapacityFull_rejectsAndDoesNotReserveTugs() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        // APP-3a 先占满唯一泊位
        TestFixtures.submit(service, "APP-3a", 1, draft);
        service.approve("APP-3a");

        TestFixtures.submit(service, "APP-3b", 1, draft);
        assertThatThrownBy(() -> service.approve("APP-3b"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.BERTH_CAPACITY_EXCEEDED);

        assertThat(assignmentRepository.findAll()).hasSize(2); // 仅 3a 的靠/离泊两条
        var app3b = service.getApplication("APP-3b");
        assertThat(app3b.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void approve_picksBerthWithFreeCapacity_whenFirstFull() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        var b2 = TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "APP-4a", 1, draft);
        service.approve("APP-4a");

        TestFixtures.submit(service, "APP-4b", 1, draft);
        var approved = service.approve("APP-4b");

        assertThat(approved.getAssignedBerthId()).isEqualTo(b2.getId());
    }

    @Test
    void approve_rejectsNonMatchingVesselTypeOrDraft() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("9.50"), 2,
                Set.of("PANAMAX"));
        TestFixtures.wideTide(service, depth);
        service.submit("APP-5", "V5", VESSEL_TYPE, ETA, ETD, draft,
                TestFixtures.CONTAINER, 0);

        assertThatThrownBy(() -> service.approve("APP-5"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.NO_MATCHING_BERTH);
    }

    @Test
    void approve_twice_isRejected() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "APP-6", 0, draft);
        service.approve("APP-6");

        assertThatThrownBy(() -> service.approve("APP-6"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.APPLICATION_ALREADY_APPROVED);
    }

    // ---------- 潮汐窗口 ----------

    @Test
    void approve_withoutTideCoveringEta_isRejected() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        // 窗口只覆盖靠泊时刻之前就结束
        TestFixtures.tide(service, T0.plusSeconds(9 * 3600),
                ETA.minusSeconds(60), depth);
        TestFixtures.submit(service, "TIDE-1", 0, draft);

        assertThatThrownBy(() -> service.approve("TIDE-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);
        assertThat(occupationRepository.findAll()).isEmpty();
    }

    @Test
    void approve_withDepthBelowDraft_isRejected() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.wideTide(service, new BigDecimal("9.00")); // 水深小于吃水 10
        TestFixtures.submit(service, "TIDE-2", 0, draft);

        assertThatThrownBy(() -> service.approve("TIDE-2"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);
    }

    // ---------- 改期：成功才释放原窗口，失败保留原安排 ----------

    @Test
    void reschedule_success_releasesOldWindowAndUsesNewOne() {
        var berth = TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "RS-1", 1, draft);
        service.approve("RS-1");

        Instant newEta = ETA.plusSeconds(2 * 3600);
        Instant newEtd = ETD.plusSeconds(2 * 3600);
        var rescheduled = service.reschedule("RS-1", newEta, newEtd);

        assertThat(rescheduled.getEta()).isEqualTo(newEta);
        List<BerthOccupation> occupations = occupationRepository.findByApplicationId(
                rescheduled.getId());
        assertThat(occupations).hasSize(1);
        assertThat(occupations.get(0).getStartTime()).isEqualTo(newEta);
        assertThat(occupations.get(0).getEndTime()).isEqualTo(newEtd);
        assertThat(occupations.get(0).getBerthId()).isEqualTo(berth.getId());
        List<TugAssignment> assignments = assignmentRepository.findByApplicationId(rescheduled.getId());
        assertThat(assignments).hasSize(2);
        assertThat(assignments).extracting(TugAssignment::getActionTime)
                .containsOnly(newEta, newEtd);
        var history = service.findHistory("RS-1");
        assertThat(history).extracting(h -> h.getAction())
                .containsExactly(HistoryAction.SUBMITTED, HistoryAction.APPROVED,
                        HistoryAction.RESCHEDULED);
    }

    @Test
    void reschedule_failure_keepsOriginalArrangement() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "RS-2", 1, draft);
        service.approve("RS-2");
        Long originalOccupationId = occupationRepository.findAll().get(0).getId();

        // 新时段没有潮汐窗口（原窗口在 21h 结束，新靠泊在 22h）
        Instant newEta = T0.plusSeconds(22 * 3600);
        Instant newEtd = T0.plusSeconds(26 * 3600);

        assertThatThrownBy(() -> service.reschedule("RS-2", newEta, newEtd))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.TIDE_WINDOW_UNAVAILABLE);

        BerthApplication unchanged = service.getApplication("RS-2");
        assertThat(unchanged.getEta()).isEqualTo(ETA);
        assertThat(unchanged.getEtd()).isEqualTo(ETD);
        List<BerthOccupation> occupations = occupationRepository.findAll();
        assertThat(occupations).hasSize(1);
        assertThat(occupations.get(0).getId()).isEqualTo(originalOccupationId);
        List<TugAssignment> assignments = assignmentRepository.findAll();
        assertThat(assignments).hasSize(2);
        assertThat(assignments).extracting(TugAssignment::getActionTime)
                .containsOnly(ETA, ETD);
    }

    @Test
    void reschedule_failure_whenTugsBusy_keepsOriginalArrangement() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 2);
        TestFixtures.createTug(service, "T1");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "RS-3", 1, draft);
        service.approve("RS-3");

        // 另一艘船恰好占用新时刻的唯一拖轮
        Instant newEta = ETA.plusSeconds(2 * 3600);
        Instant newEtd = ETD.plusSeconds(2 * 3600);
        service.submit("RS-3-OTHER", "VO", VESSEL_TYPE, newEta, newEtd, draft,
                TestFixtures.CONTAINER, 1);
        service.approve("RS-3-OTHER");

        assertThatThrownBy(() -> service.reschedule("RS-3", newEta, newEtd))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_TUGS);

        List<BerthOccupation> occupations = occupationRepository.findAll();
        assertThat(occupations).hasSize(2);
        BerthApplication unchanged = service.getApplication("RS-3");
        assertThat(unchanged.getEta()).isEqualTo(ETA);
        assertThat(assignmentRepository.findByApplicationId(unchanged.getId())).hasSize(2);
    }

    @Test
    void reschedule_whenBerthFullInNewWindow_isRejectedAndOldKept() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "RS-4", 1, draft);
        service.approve("RS-4");

        // 新窗口与原窗口首尾相接（半开区间，端点相接不重叠），OTHER 先占满新窗口
        Instant newEta = ETD;
        Instant newEtd = ETD.plusSeconds(2 * 3600);
        service.submit("RS-4-OTHER", "VO", VESSEL_TYPE, newEta, newEtd, draft,
                TestFixtures.CONTAINER, 1);
        service.approve("RS-4-OTHER");

        assertThatThrownBy(() -> service.reschedule("RS-4", newEta, newEtd))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.BERTH_CAPACITY_EXCEEDED);

        BerthApplication unchanged = service.getApplication("RS-4");
        assertThat(unchanged.getEta()).isEqualTo(ETA);
        assertThat(occupationRepository.findByApplicationId(unchanged.getId())).hasSize(1);
    }

    @Test
    void reschedule_toNonOverlappingWindow_freesCapacityForOthers() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.createTug(service, "T2");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "RS-5", 1, draft);
        service.approve("RS-5");

        // RS-5 改到更晚的不重叠时段后，原时段应可再安排其他船
        Instant newEta = T0.plusSeconds(24 * 3600);
        Instant newEtd = T0.plusSeconds(30 * 3600);
        TestFixtures.tide(service, T0.plusSeconds(23 * 3600),
                T0.plusSeconds(31 * 3600), depth);
        service.reschedule("RS-5", newEta, newEtd);

        service.submit("RS-5-NEW", "VN", VESSEL_TYPE, ETA, ETD, draft,
                TestFixtures.CONTAINER, 1);
        var approved = service.approve("RS-5-NEW");
        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    void reschedule_unapprovedApplication_isRejected() {
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "RS-6", 0, draft);

        assertThatThrownBy(() -> service.reschedule("RS-6", ETA.plusSeconds(3600),
                ETD.plusSeconds(3600)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.APPLICATION_NOT_APPROVED);
    }

    // ---------- 查询 ----------

    @Test
    void queries_returnOccupationsAssignmentsAndHistory() {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "T1");
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "Q-1", 1, draft);
        service.approve("Q-1");

        assertThat(service.findOccupations(null, "Q-1")).hasSize(1);
        assertThat(service.findAssignments("Q-1")).hasSize(2);
        var history = service.findHistory("Q-1");
        assertThat(history).hasSize(2);
        assertThat(history).extracting(h -> h.getAction())
                .containsExactly(HistoryAction.SUBMITTED, HistoryAction.APPROVED);
        assertThat(history.get(1).getDetail()).contains("B1");
    }

    @Test
    void invalidTimeRange_isRejectedWithValidationError() {
        assertThatThrownBy(() -> service.submit("BAD-1", "V", VESSEL_TYPE, ETD, ETA, draft,
                TestFixtures.CONTAINER, 0))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
