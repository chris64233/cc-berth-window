package com.chris64233.berthwindow.service;

import com.chris64233.berthwindow.domain.ApplicationStatus;
import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthReservation;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.ChangeType;
import com.chris64233.berthwindow.domain.PortResource;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;
import com.chris64233.berthwindow.repo.BerthRepository;
import com.chris64233.berthwindow.repo.BerthReservationRepository;
import com.chris64233.berthwindow.repo.ChangeHistoryRepository;
import com.chris64233.berthwindow.repo.PortResourceRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 泊位窗口申请服务：申请审批（幂等）、改期与查询。
 *
 * 并发与一致性约定：
 * - 审批/改期在同一事务内依次对 港口资源行 → 泊位行 → 潮汐窗口行 加悲观写锁，
 *   容量判断与占用写入在数据库锁保护下完成，不依赖进程内状态；
 * - 申请使用业务号作为幂等键（数据库唯一约束兜底）；
 * - 申请实体的乐观锁版本号用于防止基于旧申请内容的改期。
 */
@Service
public class BerthApplicationService {

    private final BerthApplicationRepository applicationRepository;
    private final BerthRepository berthRepository;
    private final BerthReservationRepository reservationRepository;
    private final TideWindowRepository tideWindowRepository;
    private final PortResourceRepository portResourceRepository;
    private final ChangeHistoryRepository historyRepository;

    public BerthApplicationService(BerthApplicationRepository applicationRepository,
                                   BerthRepository berthRepository,
                                   BerthReservationRepository reservationRepository,
                                   TideWindowRepository tideWindowRepository,
                                   PortResourceRepository portResourceRepository,
                                   ChangeHistoryRepository historyRepository) {
        this.applicationRepository = applicationRepository;
        this.berthRepository = berthRepository;
        this.reservationRepository = reservationRepository;
        this.tideWindowRepository = tideWindowRepository;
        this.portResourceRepository = portResourceRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * 提交申请并同步审批。批准时在同一事务内写入泊位占用记录；
     * 泊位或拖轮任一资源不足则整体拒绝（仅落一条 REJECTED 申请记录，不产生任何占用）。
     * 相同业务号重复提交时：内容一致返回原审批结果，内容不一致返回冲突错误。
     */
    @Transactional
    public BerthApplication apply(String businessNo, String shipName, String shipType, BigDecimal draft,
                                  Instant expectedArrival, Instant expectedDeparture, int requiredTugs) {
        if (!expectedArrival.isBefore(expectedDeparture)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "预计到港时间必须早于预计离港时间");
        }
        var existing = applicationRepository.findByBusinessNo(businessNo);
        if (existing.isPresent()) {
            BerthApplication app = existing.get();
            if (app.sameContentAs(shipName, shipType, draft, expectedArrival, expectedDeparture, requiredTugs)) {
                return app;
            }
            throw new BusinessException(ErrorCode.DUPLICATE_BUSINESS_NO,
                    "业务号已存在且申请内容不一致: " + businessNo);
        }

        PortResource port = portResourceRepository.lockSingleton()
                .orElseThrow(() -> new BusinessException(ErrorCode.PORT_CONFIG_MISSING, "尚未配置港口拖轮总量"));
        List<BerthReservation> allReservations = reservationRepository.findAll();

        List<Berth> candidates =
                berthRepository.findByBerthTypeAndMaxDraftGreaterThanEqualOrderByCode(shipType, draft);
        String rejectReason = null;
        for (Berth candidate : candidates) {
            Berth berth = berthRepository.lockById(candidate.getId()).orElseThrow();
            List<TideWindow> tides = tideWindowRepository.lockByBerthId(berth.getId());
            if (!CapacityChecks.tideCovers(tides, expectedArrival, draft)
                    || !CapacityChecks.tideCovers(tides, expectedDeparture, draft)) {
                rejectReason = "泊位 " + berth.getCode() + " 缺少覆盖靠离泊且满足吃水的潮汐窗口";
                continue;
            }
            if (!CapacityChecks.berthCapacityOk(berth, allReservations,
                    expectedArrival, expectedDeparture, null)) {
                rejectReason = "泊位 " + berth.getCode() + " 在申请时段内同时作业能力已满";
                continue;
            }
            if (!CapacityChecks.tugCapacityOk(port.getTotalTugs(), allReservations,
                    expectedArrival, expectedDeparture, requiredTugs, null)) {
                rejectReason = "泊位 " + berth.getCode() + " 可用，但靠离泊时段拖轮余量不足";
                continue;
            }
            BerthApplication approved = BerthApplication.approved(businessNo, shipName, shipType, draft,
                    expectedArrival, expectedDeparture, requiredTugs, berth.getCode());
            approved = applicationRepository.save(approved);
            reservationRepository.save(new BerthReservation(approved, berth,
                    expectedArrival, expectedDeparture, requiredTugs));
            historyRepository.save(new ChangeHistory(approved, ChangeType.CREATED,
                    "批准：泊位 " + berth.getCode() + "，窗口 " + expectedArrival + " ~ " + expectedDeparture
                            + "，拖轮 " + requiredTugs + " 艘"));
            return approved;
        }

        String reason = candidates.isEmpty()
                ? "没有可接纳船型 " + shipType + " 且吃水 " + draft + " 的泊位"
                : rejectReason;
        BerthApplication rejected = BerthApplication.rejected(businessNo, shipName, shipType, draft,
                expectedArrival, expectedDeparture, requiredTugs, reason);
        rejected = applicationRepository.save(rejected);
        historyRepository.save(new ChangeHistory(rejected, ChangeType.CREATED, "拒绝：" + reason));
        return rejected;
    }

    /**
     * 整体改期：仅在作业尚未开始前允许。新窗口的潮汐、泊位与拖轮校验全部通过后，
     * 在同一事务内更新占用记录（原窗口随事务提交才释放）；任一校验失败抛出异常，
     * 事务回滚，原安排完整保留。
     */
    @Transactional
    public BerthApplication reschedule(String businessNo, Long expectedVersion,
                                       Instant newArrival, Instant newDeparture) {
        if (!newArrival.isBefore(newDeparture)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "预计到港时间必须早于预计离港时间");
        }
        BerthApplication app = applicationRepository.findByBusinessNo(businessNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + businessNo));
        if (expectedVersion != null && expectedVersion != app.getVersion()) {
            throw new BusinessException(ErrorCode.STALE_APPLICATION,
                    "申请已被修改（当前版本 " + app.getVersion() + "），请刷新后重试");
        }
        if (app.getStatus() != ApplicationStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "仅已批准的申请可以改期");
        }
        BerthReservation reservation = reservationRepository.findByApplication_Id(app.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "已批准申请缺少资源占用记录"));
        if (!Instant.now().isBefore(reservation.getStartTime())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "作业已开始，不能改期");
        }

        PortResource port = portResourceRepository.lockSingleton()
                .orElseThrow(() -> new BusinessException(ErrorCode.PORT_CONFIG_MISSING, "尚未配置港口拖轮总量"));
        Berth berth = berthRepository.lockById(reservation.getBerth().getId()).orElseThrow();
        List<TideWindow> tides = tideWindowRepository.lockByBerthId(berth.getId());
        if (!CapacityChecks.tideCovers(tides, newArrival, app.getDraft())
                || !CapacityChecks.tideCovers(tides, newDeparture, app.getDraft())) {
            throw new BusinessException(ErrorCode.TIDE_WINDOW_MISSING, "新时段缺少覆盖靠离泊且满足吃水的潮汐窗口");
        }
        List<BerthReservation> allReservations = reservationRepository.findAll();
        if (!CapacityChecks.berthCapacityOk(berth, allReservations,
                newArrival, newDeparture, reservation.getId())) {
            throw new BusinessException(ErrorCode.BERTH_CAPACITY_EXCEEDED, "新时段泊位同时作业能力已满");
        }
        if (!CapacityChecks.tugCapacityOk(port.getTotalTugs(), allReservations,
                newArrival, newDeparture, reservation.getTugCount(), reservation.getId())) {
            throw new BusinessException(ErrorCode.TUG_CAPACITY_EXCEEDED, "新时段靠离泊拖轮余量不足");
        }

        String detail = "改期：" + reservation.getStartTime() + " ~ " + reservation.getEndTime()
                + " 调整为 " + newArrival + " ~ " + newDeparture;
        reservation.moveTo(newArrival, newDeparture);
        app.reschedule(newArrival, newDeparture);
        historyRepository.save(new ChangeHistory(app, ChangeType.RESCHEDULED, detail));
        return app;
    }

    @Transactional(readOnly = true)
    public BerthApplication getByBusinessNo(String businessNo) {
        return applicationRepository.findByBusinessNo(businessNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND,
                        "申请不存在: " + businessNo));
    }

    @Transactional(readOnly = true)
    public List<ChangeHistory> listHistory(String businessNo) {
        BerthApplication app = getByBusinessNo(businessNo);
        return historyRepository.findByApplication_IdOrderByOccurredAtAsc(app.getId());
    }
}
