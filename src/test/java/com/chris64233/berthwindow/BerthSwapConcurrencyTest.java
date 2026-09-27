package com.chris64233.berthwindow;

import static com.chris64233.berthwindow.TestFixtures.T0;
import static com.chris64233.berthwindow.TestFixtures.VESSEL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.SwapProposal;
import com.chris64233.berthwindow.domain.SwapStatus;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.service.BerthSwapService;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 互换并发安全：
 * 同一方案的两个确认并发执行时，只能发生一次交换，不能重复交换或重复释放资源；
 * 确认与取消/改期并发时，方案要么正常成功、要么识别为失效，绝不破坏既有占用一致性。
 */
class BerthSwapConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private BerthWindowService service;
    @Autowired
    private BerthSwapService swapService;
    @Autowired
    private BerthOccupationRepository occupationRepository;

    private static final BigDecimal DRAFT = new BigDecimal("10.00");
    private static final BigDecimal DEPTH = new BigDecimal("12.00");

    private static final Instant A_ETA = T0.plusSeconds(10 * 3600);
    private static final Instant A_ETD = T0.plusSeconds(12 * 3600);
    private static final Instant B_ETA = T0.plusSeconds(14 * 3600);
    private static final Instant B_ETD = T0.plusSeconds(16 * 3600);

    private void setupTwoApprovedOnSeparateBerths(String aNo, String bNo, int tugs) {
        service.createBerth("B1", "B1 泊位", TestFixtures.CONTAINER,
                Set.of(VESSEL_TYPE), new BigDecimal("13.00"), 1);
        service.createBerth("B2", "B2 泊位", TestFixtures.CONTAINER,
                Set.of(VESSEL_TYPE), new BigDecimal("13.00"), 1);
        for (int i = 1; i <= 4; i++) {
            service.createTug("T" + i, "拖轮" + i);
        }
        TestFixtures.wideTide(service, DEPTH);
        // 占位船占住 B1 的 B 时段，把 bNo 逼到 B2，保证互换双方在不同泊位
        service.submit("BLK-" + aNo, "V-BLK", VESSEL_TYPE, B_ETA, B_ETD, DRAFT,
                TestFixtures.CONTAINER, 0);
        service.approve("BLK-" + aNo);
        service.submit(aNo, "V-" + aNo, VESSEL_TYPE, A_ETA, A_ETD, DRAFT,
                TestFixtures.CONTAINER, tugs);
        service.approve(aNo);
        service.submit(bNo, "V-" + bNo, VESSEL_TYPE, B_ETA, B_ETD, DRAFT,
                TestFixtures.CONTAINER, tugs);
        service.approve(bNo);
    }

    private record RaceOutcome(int successes, int idempotentReplays, int failures,
                               List<Throwable> unexpected) {
    }

    /** 两个线程同时对同一方案发起确认。 */
    private RaceOutcome runConcurrentConfirm(String proposalNo, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<BerthSwapService.Confirmation>> futures = IntStream.range(0, threads)
                    .<Callable<BerthSwapService.Confirmation>>mapToObj(i -> () -> {
                        start.await();
                        return swapService.confirm(proposalNo);
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();

            int successes = 0;
            int idempotentReplays = 0;
            int failures = 0;
            List<Throwable> unexpected = new java.util.ArrayList<>();
            for (Future<BerthSwapService.Confirmation> future : futures) {
                try {
                    BerthSwapService.Confirmation c = future.get(60, TimeUnit.SECONDS);
                    if (c.success()) {
                        successes++;
                    } else {
                        failures++;
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof ApiException api
                            && api.getCode() == ErrorCode.SWAP_ALREADY_FINALIZED) {
                        idempotentReplays++;
                    } else {
                        unexpected.add(cause);
                    }
                }
            }
            return new RaceOutcome(successes, idempotentReplays, failures, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentConfirms_swapExactlyOnce_noDoubleSwapOrRelease() throws Exception {
        setupTwoApprovedOnSeparateBerths("RC-1A", "RC-1B", 1);
        swapService.propose("RC-PROP-1", "RC-1A", "RC-1B");

        RaceOutcome outcome = runConcurrentConfirm("RC-PROP-1", 4);

        assertThat(outcome.unexpected()).isEmpty();
        // 恰好一笔真正完成交换；其余并发确认全部走已落定方案的幂等路径
        assertThat(outcome.successes()).isEqualTo(4);

        // 最终状态：双方时间恰好交换一次（交换两次会还原）
        BerthApplication a = service.getApplication("RC-1A");
        BerthApplication b = service.getApplication("RC-1B");
        assertThat(a.getEta()).isEqualTo(B_ETA);
        assertThat(a.getEtd()).isEqualTo(B_ETD);
        assertThat(b.getEta()).isEqualTo(A_ETA);
        assertThat(b.getEtd()).isEqualTo(A_ETD);
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(b.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

        // 资源占用：每方仍恰好一条泊位占用、两条拖轮占用，没有重复释放/残留
        // （另有一条占位船的泊位占用，故共 3 条）
        List<BerthOccupation> occupations = occupationRepository.findAll();
        assertThat(occupations).hasSize(3);
        assertThat(occupations).extracting(BerthOccupation::getApplicationId)
                .contains(a.getId(), b.getId());
        SwapProposal proposal = swapService.getProposal("RC-PROP-1");
        assertThat(proposal.getStatus()).isEqualTo(SwapStatus.CONFIRMED);
    }

    @Test
    void confirmRacingWithCancellation_eitherSwapsOrBecomesStale_neverCorrupts() throws Exception {
        setupTwoApprovedOnSeparateBerths("RC-2A", "RC-2B", 0);
        swapService.propose("RC-PROP-2", "RC-2A", "RC-2B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var fConfirm = pool.submit((Callable<BerthSwapService.Confirmation>) () -> {
                start.await();
                return swapService.confirm("RC-PROP-2");
            });
            var fCancel = pool.submit((Callable<BerthApplication>) () -> {
                start.await();
                return service.cancel("RC-2A");
            });
            start.countDown();

            BerthSwapService.Confirmation confirmation = fConfirm.get(60, TimeUnit.SECONDS);
            BerthApplication cancelled = fCancel.get(60, TimeUnit.SECONDS);

            // 取消一定生效（若交换先提交，取消作用于交换后的安排，仍是合法线性化）
            assertThat(cancelled.getStatus()).isEqualTo(ApplicationStatus.CANCELLED);

            SwapProposal proposal = swapService.getProposal("RC-PROP-2");
            BerthApplication finalB = service.getApplication("RC-2B");
            if (confirmation.success()) {
                // 交换先提交：B 已进入 A 的原时段；随后 A 的（交换后）安排被取消
                assertThat(proposal.getStatus()).isEqualTo(SwapStatus.CONFIRMED);
                assertThat(finalB.getEta()).isEqualTo(A_ETA);
            } else {
                // 取消先提交：确认必须识别失效
                assertThat(confirmation.code()).isEqualTo(ErrorCode.SWAP_PROPOSAL_STALE);
                assertThat(proposal.getStatus()).isEqualTo(SwapStatus.FAILED);
                assertThat(finalB.getEta()).isEqualTo(B_ETA);
            }

            // 无论哪种次序：A 占用已释放，剩 B 与占位船两条占用，不存在重复删除/残留
            assertThat(occupationRepository.findAll()).hasSize(2);
            assertThat(occupationRepository.findByApplicationId(
                    service.getApplication("RC-2A").getId())).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void confirmRacingWithReschedule_eitherSwapsOrBecomesStale_arrangementsStayConsistent()
            throws Exception {
        setupTwoApprovedOnSeparateBerths("RC-3A", "RC-3B", 0);
        swapService.propose("RC-PROP-3", "RC-3A", "RC-3B");
        Instant movedEta = A_ETA.plusSeconds(60 * 60);
        Instant movedEtd = A_ETD.plusSeconds(60 * 60);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var fConfirm = pool.submit((Callable<BerthSwapService.Confirmation>) () -> {
                start.await();
                return swapService.confirm("RC-PROP-3");
            });
            var fReschedule = pool.submit((Callable<BerthApplication>) () -> {
                start.await();
                return service.reschedule("RC-3A", movedEta, movedEtd);
            });
            start.countDown();

            BerthSwapService.Confirmation confirmation = fConfirm.get(60, TimeUnit.SECONDS);
            BerthApplication rescheduled = fReschedule.get(60, TimeUnit.SECONDS);

            // 两种线性化都合法；改期在任一次序下都能成功（交换先成功则改期作用于交换后安排）
            assertThat(rescheduled.getEta()).isEqualTo(movedEta);
            assertThat(service.getApplication("RC-3A").getEta()).isEqualTo(movedEta);

            SwapProposal proposal = swapService.getProposal("RC-PROP-3");
            BerthApplication finalB = service.getApplication("RC-3B");
            if (confirmation.success()) {
                // 交换先提交：B 进入 A 原时段，随后 A 被改期
                assertThat(proposal.getStatus()).isEqualTo(SwapStatus.CONFIRMED);
                assertThat(finalB.getEta()).isEqualTo(A_ETA);
            } else {
                // 改期先提交：确认识别版本失效，B 原安排不动
                assertThat(confirmation.code()).isEqualTo(ErrorCode.SWAP_PROPOSAL_STALE);
                assertThat(proposal.getStatus()).isEqualTo(SwapStatus.FAILED);
                assertThat(finalB.getEta()).isEqualTo(B_ETA);
            }
            // 占用始终为 A、B 与占位船三条、每方一条，无重复交换或资源泄漏
            assertThat(occupationRepository.count()).isEqualTo(3);
            assertThat(occupationRepository.findByApplicationId(
                    service.getApplication("RC-3A").getId())).hasSize(1);
            assertThat(occupationRepository.findByApplicationId(finalB.getId())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentProposeSameBusinessNo_createsExactlyOneProposal() throws Exception {
        setupTwoApprovedOnSeparateBerths("RC-4A", "RC-4B", 0);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SwapProposal>> futures = IntStream.range(0, 4)
                    .<Callable<SwapProposal>>mapToObj(i -> () -> {
                        start.await();
                        return swapService.propose("RC-PROP-4", "RC-4A", "RC-4B");
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();

            Long id = null;
            for (Future<SwapProposal> future : futures) {
                SwapProposal proposal = future.get(60, TimeUnit.SECONDS);
                if (id == null) {
                    id = proposal.getId();
                } else {
                    assertThat(proposal.getId()).isEqualTo(id);
                }
            }
            assertThat(swapService.listProposals()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
