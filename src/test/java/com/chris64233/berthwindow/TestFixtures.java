package com.chris64233.berthwindow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.domain.Tug;
import com.chris64233.berthwindow.service.BerthWindowService;

/**
 * 测试数据构造辅助：固定在未来的时间基准，保证“尚未开始作业”的改期规则可测。
 */
public final class TestFixtures {

    public static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    public static final Instant ETA = T0.plusSeconds(10 * 3600);
    public static final Instant ETD = T0.plusSeconds(20 * 3600);

    public static final String CONTAINER = "CONTAINER";
    public static final String VESSEL_TYPE = "FEEDER";

    private TestFixtures() {
    }

    public static Berth containerBerth(BerthWindowService service, String code,
                                       BigDecimal maxDraft, int capacity) {
        return service.createBerth(code, code + "-泊位", CONTAINER,
                Set.of(VESSEL_TYPE), maxDraft, capacity);
    }

    public static Berth containerBerth(BerthWindowService service, String code,
                                       BigDecimal maxDraft, int capacity, Set<String> vesselTypes) {
        return service.createBerth(code, code + "-泊位", CONTAINER,
                vesselTypes, maxDraft, capacity);
    }

    public static Tug createTug(BerthWindowService service, String code) {
        return service.createTug(code, code + "-拖轮");
    }

    public static TideWindow wideTide(BerthWindowService service, BigDecimal depth) {
        return service.createTideWindow(CONTAINER, T0.plusSeconds(9 * 3600),
                T0.plusSeconds(23 * 3600), depth);
    }

    public static TideWindow tide(BerthWindowService service, Instant start, Instant end,
                                  BigDecimal depth) {
        return service.createTideWindow(CONTAINER, start, end, depth);
    }

    /** 使用默认窗口与默认船型提交申请 */
    public static com.chris64233.berthwindow.domain.BerthApplication submit(
            BerthWindowService service, String no, int tugsRequired, BigDecimal draft) {
        return service.submit(no, "V-" + no, VESSEL_TYPE, ETA, ETD, draft,
                CONTAINER, tugsRequired);
    }
}
