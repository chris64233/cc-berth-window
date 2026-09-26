package com.chris64233.berthwindow.service;

import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthReservation;
import com.chris64233.berthwindow.domain.PortResource;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.repo.BerthRepository;
import com.chris64233.berthwindow.repo.BerthReservationRepository;
import com.chris64233.berthwindow.repo.PortResourceRepository;
import com.chris64233.berthwindow.repo.TideWindowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 泊位、潮汐窗口与港口拖轮配置的管理服务。
 */
@Service
public class BerthAdminService {

    private final BerthRepository berthRepository;
    private final TideWindowRepository tideWindowRepository;
    private final PortResourceRepository portResourceRepository;
    private final BerthReservationRepository reservationRepository;

    public BerthAdminService(BerthRepository berthRepository,
                             TideWindowRepository tideWindowRepository,
                             PortResourceRepository portResourceRepository,
                             BerthReservationRepository reservationRepository) {
        this.berthRepository = berthRepository;
        this.tideWindowRepository = tideWindowRepository;
        this.portResourceRepository = portResourceRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional
    public Berth createBerth(String code, String berthType, BigDecimal maxDraft, int concurrentCapacity) {
        berthRepository.findByCode(code).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DUPLICATE_BERTH_CODE, "泊位代码已存在: " + code);
        });
        return berthRepository.save(new Berth(code, berthType, maxDraft, concurrentCapacity));
    }

    @Transactional(readOnly = true)
    public Berth getBerth(String code) {
        return berthRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.BERTH_NOT_FOUND, "泊位不存在: " + code));
    }

    @Transactional
    public TideWindow addTideWindow(String berthCode, Instant start, Instant end, BigDecimal maxDraft) {
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "潮汐窗口开始时间必须早于结束时间");
        }
        Berth berth = getBerth(berthCode);
        return tideWindowRepository.save(new TideWindow(berth, start, end, maxDraft));
    }

    /**
     * 修改潮汐窗口。审批事务会对潮汐行加悲观写锁，因此修改与审批在数据库层面串行，
     * 不会出现审批使用已被覆盖的旧潮汐判断结果。
     */
    @Transactional
    public TideWindow updateTideWindow(String berthCode, Long tideWindowId,
                                       Instant start, Instant end, BigDecimal maxDraft) {
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "潮汐窗口开始时间必须早于结束时间");
        }
        Berth berth = getBerth(berthCode);
        TideWindow window = tideWindowRepository.findById(tideWindowId)
                .filter(w -> w.getBerth().getId().equals(berth.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.TIDE_WINDOW_NOT_FOUND,
                        "潮汐窗口不存在: " + tideWindowId));
        window.update(start, end, maxDraft);
        return tideWindowRepository.save(window);
    }

    @Transactional(readOnly = true)
    public List<TideWindow> listTideWindows(String berthCode) {
        Berth berth = getBerth(berthCode);
        return tideWindowRepository.findByBerth_IdOrderByStartTime(berth.getId());
    }

    @Transactional
    public PortResource setTotalTugs(int totalTugs) {
        PortResource resource = portResourceRepository.lockSingleton()
                .orElseGet(() -> new PortResource(0));
        resource.setTotalTugs(totalTugs);
        return portResourceRepository.save(resource);
    }

    @Transactional(readOnly = true)
    public PortResource getPortResource() {
        return portResourceRepository.findById(PortResource.SINGLETON_ID)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORT_CONFIG_MISSING, "尚未配置港口拖轮总量"));
    }

    @Transactional(readOnly = true)
    public List<BerthReservation> listOccupancy(String berthCode, Instant from, Instant to) {
        getBerth(berthCode);
        return reservationRepository.findOccupancy(berthCode, from, to);
    }
}
