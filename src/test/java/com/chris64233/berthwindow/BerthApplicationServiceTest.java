package com.chris64233.berthwindow;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.ChangeType;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;
import com.chris64233.berthwindow.repo.BerthRepository;
import com.chris64233.berthwindow.repo.BerthReservationRepository;
import com.chris64233.berthwindow.repo.ChangeHistoryRepository;
import com.chris64233.berthwindow.repo.PortResourceRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import com.chris64233.berthwindow.service.BerthAdminService;
import com.chris64233.berthwindow.service.BerthApplicationService;
import com.chris64233.berthwindow.service.BusinessException;
import com.chris64233.berthwindow.service.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BerthApplicationServiceTest {

    private static final Instant BASE = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

    @Autowired
    BerthApplicationService applicationService;
    @Autowired
    BerthAdminService adminService;
    @Autowired
    BerthApplicationRepository applicationRepository;
    @Autowired
    BerthReservationRepository reservationRepository;
    @Autowired
    ChangeHistoryRepository historyRepository;
    @Autowired
    TideWindowRepository tideWindowRepository;
    @Autowired
    BerthRepository berthRepository;
    @Autowired
    PortResourceRepository portResourceRepository;

    @BeforeEach
    void cleanDatabase() {
        historyRepository.deleteAll();
        reservationRepository.deleteAll();
        applicationRepository.deleteAll();
        tideWindowRepository.deleteAll();
        berthRepository.deleteAll();
        portResourceRepository.deleteAll();
    }

    private String newBerth(String type, String maxDraft, int capacity) {
        String code = "B-" + UUID.randomUUID().toString().substring(0, 8);
        adminService.createBerth(code, type, new BigDecimal(maxDraft), capacity);
        return code;
    }

    private void tide(String berthCode, Instant start, Instant end, String maxDraft) {
        adminService.addTideWindow(berthCode, start, end, new BigDecimal(maxDraft));
    }

    private void tugs(int total) {
        adminService.setTotalTugs(total);
    }

    private String businessNo() {
        return "BN-" + UUID.randomUUID();
    }

    private BerthApplication apply(String businessNo, String shipType, String draft,
                                   Instant arrival, Instant departure, int requiredTugs) {
        return applicationService.apply(businessNo, "测试船", shipType, new BigDecimal(draft),
                arrival, departure, requiredTugs);
    }

    @Test
    void approvesWhenTideAndResourcesSufficient() {
        String berth = newBerth("CONTAINER", "12.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "13.00");
        tugs(4);

        var app = apply(businessNo(), "CONTAINER", "11.50",
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 2);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getBerthCode()).isEqualTo(berth);
        assertThat(reservationRepository.findAll()).hasSize(1);
        List<ChangeHistory> history = applicationService.listHistory(app.getBusinessNo());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getChangeType()).isEqualTo(ChangeType.CREATED);
    }

    @Test
    void rejectsWhenDraftExceedsTideWindowAllowance() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "10.00");
        tugs(4);

        var app = apply(businessNo(), "CONTAINER", "12.00",
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 1);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getRejectReason()).contains("潮汐窗口");
        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsWhenTideWindowDoesNotCoverDepartureOperation() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        // 潮汐窗口只覆盖到离港时刻，无法完整覆盖离港作业时段（离港时刻起 1 小时）
        tide(berth, BASE, BASE.plus(20, ChronoUnit.HOURS), "15.00");
        tugs(4);

        var app = apply(businessNo(), "CONTAINER", "12.00",
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 1);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsWhenBerthConcurrentCapacityFull() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(10);
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);

        var first = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1);
        var second = apply(businessNo(), "CONTAINER", "10.00", arrival.plus(1, ChronoUnit.HOURS), departure, 1);

        assertThat(first.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(second.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(second.getRejectReason()).contains("同时作业能力已满");
        assertThat(reservationRepository.findAll()).hasSize(1);
    }

    @Test
    void rejectsAtomicallyWhenTugsInsufficientAndLeavesNoReservation() {
        String berth = newBerth("CONTAINER", "15.00", 2);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(2);
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);

        var first = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 2);
        // 泊位能力仍有剩余，但拖轮在靠泊时段已被占满：必须整体拒绝，不能只占泊位
        var second = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1);

        assertThat(first.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(second.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(second.getRejectReason()).contains("拖轮");
        assertThat(reservationRepository.findAll()).hasSize(1);

        // 拖轮补充后，新申请可以成功
        tugs(5);
        var third = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1);
        assertThat(third.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(reservationRepository.findAll()).hasSize(2);
    }

    @Test
    void rejectsWhenNoBerthMatchesShipTypeOrDraft() {
        String berth = newBerth("CONTAINER", "10.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(4);

        var wrongType = apply(businessNo(), "TANKER", "8.00",
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 1);
        var tooDeep = apply(businessNo(), "CONTAINER", "12.00",
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 1);

        assertThat(wrongType.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(tooDeep.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @Test
    void applyIsIdempotentByBusinessNo() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(4);
        String businessNo = businessNo();
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);

        var first = apply(businessNo, "CONTAINER", "10.00", arrival, departure, 1);
        var replay = apply(businessNo, "CONTAINER", "10.00", arrival, departure, 1);

        assertThat(replay.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(applicationRepository.findAll()).hasSize(1);
        assertThat(reservationRepository.findAll()).hasSize(1);

        // 相同业务号但内容不同：冲突
        assertThatThrownBy(() -> apply(businessNo, "CONTAINER", "10.00",
                arrival.plus(2, ChronoUnit.HOURS), departure, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_BUSINESS_NO);
    }

    @Test
    void concurrentApplicationsNeverExceedBerthCapacity() throws Exception {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(100);
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);

        int threads = 6;
        var approved = runConcurrently(threads,
                () -> apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1).getStatus());

        assertThat(approved.get()).isEqualTo(1);
        assertThat(reservationRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentApplicationsNeverExceedTugCapacity() throws Exception {
        String berth = newBerth("CONTAINER", "15.00", 10);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(3);
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);

        int threads = 5;
        var approved = runConcurrently(threads,
                () -> apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 2).getStatus());

        // 每单需 2 艘拖轮，总量 3：同一靠泊时段最多批准 1 单
        assertThat(approved.get()).isEqualTo(1);
        assertThat(reservationRepository.findAll()).hasSize(1);
    }

    private AtomicInteger runConcurrently(int threads, java.util.concurrent.Callable<ApplicationStatus> task)
            throws Exception {
        var executor = Executors.newFixedThreadPool(threads);
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var approved = new AtomicInteger();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                if (task.call() == ApplicationStatus.APPROVED) {
                    approved.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
        return approved;
    }

    @Test
    void rescheduleMovesWindowAndReleasesOldSlotOnlyAfterSuccess() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(4, ChronoUnit.DAYS), "15.00");
        tugs(4);
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);
        var app = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

        Instant newArrival = BASE.plus(30, ChronoUnit.HOURS);
        Instant newDeparture = BASE.plus(46, ChronoUnit.HOURS);
        var moved = applicationService.reschedule(app.getBusinessNo(), null, newArrival, newDeparture);

        assertThat(moved.getExpectedArrival()).isEqualTo(newArrival);
        assertThat(moved.getExpectedDeparture()).isEqualTo(newDeparture);
        var reservation = reservationRepository.findByApplication_Id(app.getId()).orElseThrow();
        assertThat(reservation.getStartTime()).isEqualTo(newArrival);
        assertThat(reservation.getEndTime()).isEqualTo(newDeparture);

        // 原窗口已释放：其他申请可以使用原时段
        var other = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1);
        assertThat(other.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

        List<ChangeHistory> history = applicationService.listHistory(app.getBusinessNo());
        assertThat(history).extracting(ChangeHistory::getChangeType)
                .containsExactly(ChangeType.CREATED, ChangeType.RESCHEDULED);
    }

    @Test
    void failedRescheduleKeepsOriginalArrangement() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(4, ChronoUnit.DAYS), "15.00");
        tugs(4);
        Instant arrival = BASE.plus(4, ChronoUnit.HOURS);
        Instant departure = BASE.plus(20, ChronoUnit.HOURS);
        var first = apply(businessNo(), "CONTAINER", "10.00", arrival, departure, 1);
        var second = apply(businessNo(), "CONTAINER", "10.00",
                arrival.plus(30, ChronoUnit.HOURS), departure.plus(30, ChronoUnit.HOURS), 1);
        assertThat(second.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

        // 第二单想改到与第一单重叠的时段：泊位能力不足，改期必须失败且原安排保留
        assertThatThrownBy(() -> applicationService.reschedule(
                second.getBusinessNo(), null, arrival, departure))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.BERTH_CAPACITY_EXCEEDED);

        var unchanged = applicationService.getByBusinessNo(second.getBusinessNo());
        assertThat(unchanged.getExpectedArrival()).isEqualTo(arrival.plus(30, ChronoUnit.HOURS));
        var reservation = reservationRepository.findByApplication_Id(second.getId()).orElseThrow();
        assertThat(reservation.getStartTime()).isEqualTo(arrival.plus(30, ChronoUnit.HOURS));
        assertThat(reservationRepository.findAll()).hasSize(2);
        // 改期失败不产生改期历史
        assertThat(applicationService.listHistory(second.getBusinessNo()))
                .extracting(ChangeHistory::getChangeType).containsExactly(ChangeType.CREATED);
    }

    @Test
    void rescheduleRejectsStaleVersion() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(4, ChronoUnit.DAYS), "15.00");
        tugs(4);
        var app = apply(businessNo(), "CONTAINER", "10.00",
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 1);

        assertThatThrownBy(() -> applicationService.reschedule(app.getBusinessNo(), 99L,
                BASE.plus(30, ChronoUnit.HOURS), BASE.plus(46, ChronoUnit.HOURS)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STALE_APPLICATION);
    }

    @Test
    void rescheduleRejectedAfterOperationStarted() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        tide(berth, past, past.plus(4, ChronoUnit.DAYS), "15.00");
        tugs(4);
        var app = apply(businessNo(), "CONTAINER", "10.00",
                past.plus(4, ChronoUnit.HOURS), Instant.now().plus(2, ChronoUnit.HOURS), 1);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

        assertThatThrownBy(() -> applicationService.reschedule(app.getBusinessNo(), null,
                Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(2, ChronoUnit.DAYS)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void rescheduleRejectedApplicationNotAllowed() {
        String berth = newBerth("CONTAINER", "15.00", 1);
        tide(berth, BASE, BASE.plus(2, ChronoUnit.DAYS), "15.00");
        tugs(0);
        var app = apply(businessNo(), "CONTAINER", "10.00",
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS), 1);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);

        assertThatThrownBy(() -> applicationService.reschedule(app.getBusinessNo(), null,
                BASE.plus(30, ChronoUnit.HOURS), BASE.plus(46, ChronoUnit.HOURS)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }
}
