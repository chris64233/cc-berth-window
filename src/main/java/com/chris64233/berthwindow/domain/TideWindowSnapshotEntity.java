package com.chris64233.berthwindow.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 互换方案冻结的单个潮汐窗口快照：窗口内容与冻结时的版本号。
 * 确认时与当前潮汐窗口逐项比对，任一窗口新增/删除/更新即判定方案失效。
 */
@Entity
@Table(name = "berth_swap_tide_snapshot")
public class TideWindowSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tide_window_id", nullable = false)
    private Long tideWindowId;

    @Column(name = "berth_type", nullable = false, length = 64)
    private String berthType;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "available_depth", nullable = false, precision = 6, scale = 2)
    private BigDecimal availableDepth;

    @Column(name = "tide_version", nullable = false)
    private long version;

    protected TideWindowSnapshotEntity() {
    }

    public TideWindowSnapshotEntity(Long tideWindowId, String berthType, Instant windowStart,
                                    Instant windowEnd, BigDecimal availableDepth, long version) {
        this.tideWindowId = tideWindowId;
        this.berthType = berthType;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.availableDepth = availableDepth;
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public Long getTideWindowId() {
        return tideWindowId;
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

    public long getVersion() {
        return version;
    }
}
