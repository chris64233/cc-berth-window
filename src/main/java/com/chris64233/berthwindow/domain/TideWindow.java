package com.chris64233.berthwindow.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 潮汐窗口：某泊位类型在 [windowStart, windowEnd] 内的可用水深。
 *
 * <p>携带 JPA 乐观锁版本号：审批读取窗口后，若窗口数据在审批提交前被修改，
 * 事务将因版本不匹配而失败，从而拒绝基于旧潮汐数据作出的判断。</p>
 */
@Entity
@Table(name = "tide_window",
        indexes = @Index(name = "idx_tide_window_type_time", columnList = "berthType,windowStart,windowEnd"))
public class TideWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String berthType;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    /** 该时段可保证的最小水深（米），须不小于船舶吃水方可靠离泊 */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal availableDepth;

    @Version
    private long version;

    protected TideWindow() {
    }

    public TideWindow(String berthType, Instant windowStart, Instant windowEnd, BigDecimal availableDepth) {
        this.berthType = berthType;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.availableDepth = availableDepth;
    }

    public boolean covers(Instant point) {
        return !point.isBefore(windowStart) && !point.isAfter(windowEnd);
    }

    public Long getId() {
        return id;
    }

    public String getBerthType() {
        return berthType;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public BigDecimal getAvailableDepth() {
        return availableDepth;
    }

    public void setAvailableDepth(BigDecimal availableDepth) {
        this.availableDepth = availableDepth;
    }

    public long getVersion() {
        return version;
    }
}
