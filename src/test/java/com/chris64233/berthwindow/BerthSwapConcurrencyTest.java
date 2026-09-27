package com.chris64233.berthwindow;

import static com.chris64233.berthwindow.TestFixtures.ETD;
import static com.chris64233.berthwindow.TestFixtures.ETA;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.SwapProposalStatus;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.service.BerthSwapService;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.ApiException;

/**
 * 互换并发：两个确认请求并发时只能交换一次、不能重复释放资源；
 * 确认与改期/取消并发时，要么交换成功要么原安排保留，绝不出现半交换状态；
 * 同 swapNo 并发提议只生成一份方案。
 */
class BerthSwapConcurrencyTest extends AbstractIntegrationTest {

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
    private final Instant etaB = TestFixtures.T0.plusSeconds(12 * 3600);
    private final Instant etdB = TestFixtures.T0.plusSeconds(22 * 3600);

    private void preparePair(String noA, String noB, int tugs) {
        TestFixtures.containerBerth(service, "B1", new BigDecimal("13.00"), 1);
        TestFixtures.containerBerth(service, "B2", new BigDecimal("13.00"), 1);
        for (int i = 1; i <= Math.max(2, tugs); i++) {
            TestFixtures.createTug(service, "T" + i);
        }
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, noA, tugs, draft, ETA, ETD);
        TestFixtures.submit(service, noB, tugs, draft, etaB, etdB);
        service.approve(noA);
        service.approve(noB);
    }

    private record RaceOutcome(int successes, int conflicts, List<Throwable> unexpected) {
    }

    private RaceOutcome runRace(int threads, java.util.function.IntFunction<Callable<Void>> taskFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        start.await();
                        return taskFactory.apply(i).call();
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();

            int successes = 0;
            int conflicts = 0;
            List<Throwable> unexpected = new java.util.ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    successes++;
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof ApiException) {
                        conflicts++;
                    } else {
                        unexpected.add(cause);
                    }
                }
            }
            return new RaceOutcome(successes, conflicts, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentConfirms_swapExactlyOnce_andNeverReleaseTwice() throws Exception {
        preparePair("CC-A", "CC-B", 1);
        swapService.propose("CC-SWAP", "CC-A", "CC-B");

        RaceOutcome outcome = runRace(4, i -> () -> {
            swapService.confirm("CC-SWAP");
            return null;
        });

        // 其余 3 个并发确认读到 CONFIRMED 终态后幂等返回（成功），但交换只发生一次：
        assertThat(outcome.unexpected()).isEmpty();
        var proposal = swapService.getProposal("CC-SWAP");
        assertThat(proposal.getStatus()).isEqualTo(SwapProposalStatus.CONFIRMED);

        // 恰好两份占用、每份一条，分别落在对方泊位
        var occupations = occupationRepository.findAll();
        assertThat(occupations).hasSize(2);
        BerthApplication a = service.getApplication("CC-A");
        BerthApplication b = service.getApplication("CC-B");
        assertThat(occupationRepository.findByApplicationId(a.getId())).hasSize(1);
        assertThat(occupationRepository.findByApplicationId(b.getId())).hasSize(1);
        // 双方确实完成交换
        assertThat(a.getEta()).isEqualTo(etaB);
        assertThat(b.getEta()).isEqualTo(ETA);
        // 拖轮 (拖轮,时刻) 唯一约束下无重复；每方仍各 2 艘次
        var assignments = assignmentRepository.findAll();
        assertThat(assignments).hasSize(4);
        long distinctTugTime = assignments.stream()
                .map(x -> x.getTugId() + "@" + x.getActionTime())
                .distinct().count();
        assertThat(distinctTugTime).isEqualTo(assignments.size());
        // SWAP_CONFIRMED 历史每方恰好一条
        assertThat(service.findHistory("CC-A").stream()
                .filter(h -> h.getAction() == com.chris64233.berthwindow.domain.HistoryAction.SWAP_CONFIRMED)
                .count()).isEqualTo(1);
        assertThat(service.findHistory("CC-B").stream()
                .filter(h -> h.getAction() == com.chris64233.berthwindow.domain.HistoryAction.SWAP_CONFIRMED)
                .count()).isEqualTo(1);
    }

    @Test
    void confirmRacingAgainstReschedule_eitherSwappedOrOriginalKept_neverHalfSwapped() throws Exception {
        preparePair("CR-A", "CR-B", 1);
        swapService.propose("CR-SWAP", "CR-A", "CR-B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Callable<Void> confirmTask = () -> {
            start.await();
            try {
                swapService.confirm("CR-SWAP");
            } catch (ApiException expected) {
                // 改期先提交则确认必然 SWAP_STALE
            }
            return null;
        };
        Instant newEta = ETA.plusSeconds(3 * 3600);
        Instant newEtd = ETD.plusSeconds(3 * 3600);
        Callable<Void> rescheduleTask = () -> {
            start.await();
            try {
                service.reschedule("CR-A", newEta, newEtd);
            } catch (ApiException expected) {
                // 交换先提交则改期必然失败（占用在对方时段）
            }
            return null;
        };
        var f1 = pool.submit(confirmTask);
        var f2 = pool.submit(rescheduleTask);
        start.countDown();
        try {
            f1.get(60, TimeUnit.SECONDS);
            f2.get(60, TimeUnit.SECONDS);
        } catch (Throwable t) {
            errors.set(t);
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors.get()).isNull();

        // 最终状态必须自洽（交换与改期在申请行锁处串行，二者之一先提交；
        // 交换成功后改期仍可能在交换后的新安排上成功，因此只校验不变量）：
        // 每份申请恰好一条占用、总数 2、不存在半交换；方案终态与 B 的安排一致。
        BerthApplication a = service.getApplication("CR-A");
        BerthApplication b = service.getApplication("CR-B");
        assertThat(occupationRepository.findByApplicationId(a.getId())).hasSize(1);
        assertThat(occupationRepository.findByApplicationId(b.getId())).hasSize(1);
        assertThat(occupationRepository.findAll()).hasSize(2);
        var assignments = assignmentRepository.findAll();
        long distinctTugTime = assignments.stream()
                .map(x -> x.getTugId() + "@" + x.getActionTime())
                .distinct().count();
        assertThat(distinctTugTime).isEqualTo(assignments.size());

        var proposal = swapService.getProposal("CR-SWAP");
        var occB = occupationRepository.findByApplicationId(b.getId()).get(0);
        if (proposal.getStatus() == SwapProposalStatus.CONFIRMED) {
            // 交换已落地：B 必在 B1 的 10:00 时段；A 在交换后时段（可能随后改期成功）
            assertThat(b.getEta()).isEqualTo(ETA);
            assertThat(occB.getStartTime()).isEqualTo(ETA);
            assertThat(occupationRepository.findByApplicationId(a.getId()).get(0).getStartTime())
                    .isIn(etaB, newEta);
        } else {
            // 改期先提交，交换因版本变化失效：B 原安排保留
            assertThat(proposal.getStatus()).isEqualTo(SwapProposalStatus.FAILED);
            assertThat(b.getEta()).isEqualTo(etaB);
            assertThat(occB.getStartTime()).isEqualTo(etaB);
            assertThat(a.getEta()).isIn(ETA, newEta);
        }
    }

    @Test
    void concurrentProposalsWithSameSwapNo_createExactlyOne() throws Exception {
        preparePair("CP-A", "CP-B", 0);

        RaceOutcome outcome = runRace(4, i -> () -> {
            swapService.propose("CP-SWAP", "CP-A", "CP-B");
            return null;
        });

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(swapService.listProposals()).hasSize(1);
        assertThat(swapService.getProposal("CP-SWAP").getStatus())
                .isEqualTo(SwapProposalStatus.PROPOSED);
        // 只写了一次提议历史（每方一条）
        assertThat(service.findHistory("CP-A").stream()
                .filter(h -> h.getAction() == com.chris64233.berthwindow.domain.HistoryAction.SWAP_PROPOSED)
                .count()).isEqualTo(1);
    }
}
