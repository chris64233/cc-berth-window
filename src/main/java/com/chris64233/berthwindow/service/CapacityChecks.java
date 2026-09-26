package com.chris64233.berthwindow.service;

import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthReservation;
import com.chris64233.berthwindow.domain.TideWindow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * 容量与潮汐校验的纯函数实现。所有调用都必须在持有相应数据库行锁的事务内进行，
 * 这里的计算结果才对应数据库中一致的容量状态。
 */
final class CapacityChecks {

    /** 靠泊/离泊作业占用拖轮的固定时长。 */
    static final Duration TUG_OPERATION_DURATION = Duration.ofHours(1);

    private CapacityChecks() {
    }

    /**
     * 是否存在一个潮汐窗口完整覆盖 [operationStart, operationStart + 作业时长]
     * 且允许吃水不小于船舶吃水。
     */
    static boolean tideCovers(List<TideWindow> windows, Instant operationStart, BigDecimal draft) {
        Instant operationEnd = operationStart.plus(TUG_OPERATION_DURATION);
        return windows.stream().anyMatch(w ->
                !w.getStartTime().isAfter(operationStart)
                        && !w.getEndTime().isBefore(operationEnd)
                        && w.getMaxDraft().compareTo(draft) >= 0);
    }

    /**
     * 在 [start, end) 内任意时刻，泊位上同时作业的船舶数（含本次申请）不超过泊位同时作业能力。
     */
    static boolean berthCapacityOk(Berth berth, List<BerthReservation> allReservations,
                                   Instant start, Instant end, Long excludeReservationId) {
        List<BerthReservation> overlapping = allReservations.stream()
                .filter(r -> r.getBerth().getId().equals(berth.getId()))
                .filter(r -> excludeReservationId == null || !r.getId().equals(excludeReservationId))
                .filter(r -> r.getStartTime().isBefore(end) && r.getEndTime().isAfter(start))
                .toList();
        NavigableSet<Instant> points = new TreeSet<>();
        points.add(start);
        points.add(end);
        for (BerthReservation r : overlapping) {
            if (r.getStartTime().isAfter(start) && r.getStartTime().isBefore(end)) {
                points.add(r.getStartTime());
            }
            if (r.getEndTime().isAfter(start) && r.getEndTime().isBefore(end)) {
                points.add(r.getEndTime());
            }
        }
        for (Instant t : points) {
            long count = overlapping.stream()
                    .filter(r -> !r.getStartTime().isAfter(t) && r.getEndTime().isAfter(t))
                    .count();
            if (!t.isBefore(start) && t.isBefore(end)) {
                count++;
            }
            if (count > berth.getConcurrentCapacity()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 靠泊与离泊两个作业时段内，任意时刻全港被占用的拖轮总数（含本次申请）不超过拖轮总量。
     */
    static boolean tugCapacityOk(int totalTugs, List<BerthReservation> allReservations,
                                 Instant arrival, Instant departure, int requiredTugs,
                                 Long excludeReservationId) {
        List<OpWindow> existing = new ArrayList<>();
        for (BerthReservation r : allReservations) {
            if (excludeReservationId != null && r.getId().equals(excludeReservationId)) {
                continue;
            }
            existing.add(new OpWindow(r.getStartTime(), r.getStartTime().plus(TUG_OPERATION_DURATION), r.getTugCount()));
            existing.add(new OpWindow(r.getEndTime(), r.getEndTime().plus(TUG_OPERATION_DURATION), r.getTugCount()));
        }
        List<OpWindow> requested = List.of(
                new OpWindow(arrival, arrival.plus(TUG_OPERATION_DURATION), requiredTugs),
                new OpWindow(departure, departure.plus(TUG_OPERATION_DURATION), requiredTugs));
        for (OpWindow nw : requested) {
            NavigableSet<Instant> points = new TreeSet<>();
            points.add(nw.start());
            for (OpWindow w : existing) {
                if (w.start().isAfter(nw.start()) && w.start().isBefore(nw.end())) {
                    points.add(w.start());
                }
            }
            for (Instant t : points) {
                int used = requiredTugs;
                for (OpWindow w : existing) {
                    if (!w.start().isAfter(t) && w.end().isAfter(t)) {
                        used += w.tugs();
                    }
                }
                if (used > totalTugs) {
                    return false;
                }
            }
        }
        return true;
    }

    private record OpWindow(Instant start, Instant end, int tugs) {
    }
}
