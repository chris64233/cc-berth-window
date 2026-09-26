package com.chris64233.berthwindow;

import static com.chris64233.berthwindow.TestFixtures.ETD;
import static com.chris64233.berthwindow.TestFixtures.ETA;
import static com.chris64233.berthwindow.TestFixtures.VESSEL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;
import com.chris64233.berthwindow.repo.BerthOccupationRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import com.chris64233.berthwindow.repo.TugAssignmentRepository;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.ApiException;
import com.chris64233.berthwindow.web.ErrorCode;

/**
 * 并发争抢同一泊位/拖轮余量时，成功组合绝不能突破容量；
 * 审批期间潮汐数据被修改时，必须拒绝旧判断结果；业务号并发提交必须幂等。
 */
class BerthWindowConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private BerthWindowService service;
    @Autowired
    private BerthApplicationRepository applicationRepository;
    @Autowired
    private BerthOccupationRepository occupationRepository;
    @Autowired
    private TugAssignmentRepository assignmentRepository;
    @Autowired
    private TideWindowRepository tideWindowRepository;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final BigDecimal draft = new BigDecimal("10.00");
    private final BigDecimal depth = new BigDecimal("12.00");

    /** 在固定大小线程池内执行任务，返回每个任务的成功/业务拒绝计数与意外异常。 */
    private RaceResult runRace(int threads, java.util.function.IntFunction<Callable<Void>> taskFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // 用发车闩让所有线程尽量同时开始，制造真实争抢
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
            int businessRejections = 0;
            List<Throwable> unexpected = new java.util.ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    successes++;
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof ApiException api && isCapacityConflict(api.getCode())) {
                        businessRejections++;
                    } else {
                        unexpected.add(cause);
                    }
                }
            }
            return new RaceResult(successes, businessRejections, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean isCapacityConflict(ErrorCode code) {
        return code == ErrorCode.BERTH_CAPACITY_EXCEEDED
                || code == ErrorCode.INSUFFICIENT_TUGS
                || code == ErrorCode.APPLICATION_ALREADY_APPROVED;
    }

    private record RaceResult(int successes, int businessRejections, List<Throwable> unexpected) {
    }

    @Test
    void concurrentApprovals_neverExceedBerthCapacity() throws Exception {
        TestFixtures.containerBerth(service, "CB", new BigDecimal("13.00"), 2);
        for (int i = 1; i <= 5; i++) {
            TestFixtures.createTug(service, "CT" + i);
        }
        TestFixtures.wideTide(service, depth);
        for (int i = 1; i <= 5; i++) {
            TestFixtures.submit(service, "CB-" + i, 1, draft);
        }

        RaceResult result = runRace(5, i -> () -> {
            service.approve("CB-" + (i + 1));
            return null;
        });

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.successes()).isEqualTo(2);
        assertThat(result.businessRejections()).isEqualTo(3);

        // 数据库最终状态：占用数恰好等于容量
        Berth berth = service.listBerths().get(0);
        assertThat(occupationRepository.count()).isEqualTo(2);
        assertThat(occupationRepository.countOverlapping(berth.getId(), ETA, ETD)).isEqualTo(2);
        assertThat(applicationRepository.findAll().stream()
                .filter(a -> a.getStatus() == ApplicationStatus.APPROVED)
                .count()).isEqualTo(2);
    }

    @Test
    void concurrentApprovals_neverOverbookTugs() throws Exception {
        TestFixtures.containerBerth(service, "CTB", new BigDecimal("13.00"), 10);
        for (int i = 1; i <= 3; i++) {
            TestFixtures.createTug(service, "TT" + i);
        }
        TestFixtures.wideTide(service, depth);
        // 3 份申请各需 2 艘拖轮、同一靠/离泊时刻：总需求 6 > 3
        for (int i = 1; i <= 3; i++) {
            TestFixtures.submit(service, "TUG-" + i, 2, draft);
        }

        RaceResult result = runRace(3, i -> () -> {
            service.approve("TUG-" + (i + 1));
            return null;
        });

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.successes()).isEqualTo(1);
        assertThat(result.businessRejections()).isEqualTo(2);

        // 只有一份申请占用拖轮：2 艘 × 靠/离泊 = 4 条，且 (拖轮,时刻) 无重复
        var approvedApps = applicationRepository.findAll().stream()
                .filter(a -> a.getStatus() == ApplicationStatus.APPROVED)
                .toList();
        assertThat(approvedApps).hasSize(1);
        var assignments = assignmentRepository.findAll();
        assertThat(assignments).hasSize(4);
        long distinctTugTime = assignments.stream()
                .map(a -> a.getTugId() + "@" + a.getActionTime())
                .distinct()
                .count();
        assertThat(distinctTugTime).isEqualTo(assignments.size());
        // 被拒申请保持 PENDING 且没有任何拖轮占用
        applicationRepository.findAll().stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PENDING)
                .forEach(a -> {
                    assertThat(a.getAssignedBerthId()).isNull();
                    assertThat(assignmentRepository.findByApplicationId(a.getId())).isEmpty();
                    assertThat(occupationRepository.findByApplicationId(a.getId())).isEmpty();
                });
    }

    @Test
    void tideChangeDuringOpenApprovalRead_invalidatesStaleJudgment() throws Exception {
        TideWindow window = TestFixtures.wideTide(service, new BigDecimal("12.00"));

        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch updateCommitted = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        // 事务1：以与审批相同的 OPTIMISTIC 锁读取全部潮汐窗口后挂起，
        // 等事务2 把水深改到吃水以下并提交，再尝试提交——版本复验必须失败回滚。
        Thread reader = new Thread(() -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            try {
                tx.executeWithoutResult(status -> {
                    List<TideWindow> windows = tideWindowRepository
                            .findForVerificationByBerthType(TestFixtures.CONTAINER);
                    assertThat(windows).extracting(TideWindow::getId).contains(window.getId());
                    readDone.countDown();
                    try {
                        assertThat(updateCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    // 事务提交前 Hibernate 对 OPTIMISTIC 实体做版本复验 -> 失败整体回滚
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();

        assertThat(readDone.await(5, TimeUnit.SECONDS)).isTrue();
        service.updateTideDepth(window.getId(), new BigDecimal("8.00"), null);
        updateCommitted.countDown();
        reader.join(30000);

        Throwable thrown = failure.get();
        assertThat(thrown).isNotNull();
        assertThat(hasCause(thrown, OptimisticLockException.class,
                ObjectOptimisticLockingFailureException.class)).isTrue();
        assertThat(tideWindowRepository.findById(window.getId()).orElseThrow().getAvailableDepth())
                .isEqualByComparingTo("8.00");
    }

    @Test
    void approve_readingTideThatChangesConcurrently_wholeApprovalRollsBack() throws Exception {
        TestFixtures.containerBerth(service, "OLB", new BigDecimal("13.00"), 1);
        TestFixtures.createTug(service, "OLT");
        TideWindow window = TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "STALE-2", 1, draft);

        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch updateCommitted = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        // 事务1：复刻审批中的潮汐判定步骤（OPTIMISTIC 读窗口并完成水深判断），
        // 在提交点与潮汐更新事务交错，验证旧判断结果无法落地。
        Thread approver = new Thread(() -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            try {
                tx.executeWithoutResult(status -> {
                    List<TideWindow> windows = tideWindowRepository
                            .findForVerificationByBerthType(TestFixtures.CONTAINER);
                    boolean etaOk = windows.stream().anyMatch(w -> w.covers(ETA)
                            && w.getAvailableDepth().compareTo(draft) >= 0);
                    boolean etdOk = windows.stream().anyMatch(w -> w.covers(ETD)
                            && w.getAvailableDepth().compareTo(draft) >= 0);
                    assertThat(etaOk && etdOk).isTrue(); // 基于旧数据判断通过
                    readDone.countDown();
                    try {
                        assertThat(updateCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        approver.start();
        assertThat(readDone.await(5, TimeUnit.SECONDS)).isTrue();
        service.updateTideDepth(window.getId(), new BigDecimal("8.00"), null);
        updateCommitted.countDown();
        approver.join(30000);

        assertThat(failure.get()).isNotNull();
        assertThat(hasCause(failure.get(), OptimisticLockException.class,
                ObjectOptimisticLockingFailureException.class)).isTrue();
        // 即使旧判断“通过”，也没有任何占用落地
        BerthApplication app = applicationRepository.findByApplicationNo("STALE-2").orElseThrow();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(occupationRepository.findAll()).isEmpty();
        assertThat(assignmentRepository.findAll()).isEmpty();
    }

    @Test
    void concurrentApprovalsOfSameApplication_approveExactlyOnce() throws Exception {
        TestFixtures.containerBerth(service, "SAB", new BigDecimal("13.00"), 1);
        TestFixtures.wideTide(service, depth);
        TestFixtures.submit(service, "SAME-1", 0, draft);

        RaceResult result = runRace(4, i -> () -> {
            service.approve("SAME-1");
            return null;
        });

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.successes()).isEqualTo(1);
        assertThat(result.businessRejections()).isEqualTo(3);
        assertThat(occupationRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentSubmitSameBusinessNo_createsExactlyOneApplication() throws Exception {
        TestFixtures.wideTide(service, depth);

        RaceResult result = runRace(4, i -> () -> {
            service.submit("RACE-IDEM", "V", VESSEL_TYPE, ETA, ETD, draft,
                    TestFixtures.CONTAINER, 0);
            return null;
        });

        // 先查后插的竞态由 application_no 唯一约束兜底：其余线程要么幂等返回，
        // 要么触发唯一约束冲突，绝不能产生第二条记录
        assertThat(result.successes() + result.businessRejections()).isEqualTo(4);
        var all = applicationRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getApplicationNo()).isEqualTo("RACE-IDEM");
    }

    @SuppressWarnings("unchecked")
    private static boolean hasCause(Throwable thrown, Class<? extends Throwable>... types) {
        Throwable c = thrown;
        while (c != null) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(c)) {
                    return true;
                }
            }
            c = c.getCause();
        }
        return false;
    }
}
