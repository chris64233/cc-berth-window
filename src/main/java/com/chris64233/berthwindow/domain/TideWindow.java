package com.chris64233.berthwindow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 潮汐窗口：某泊位在 [startTime, endTime] 内可通行的最大吃水。
 * 只有窗口完全覆盖靠泊/离泊作业时段且允许吃水不小于船舶吃水时才可使用。
 */
@Entity
@Table(name = "tide_window")
public class TideWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "berth_id", nullable = false)
    private Berth berth;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "max_draft", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxDraft;

    @Version
    private long version;

    protected TideWindow() {
    }

    public TideWindow(Berth berth, Instant startTime, Instant endTime, BigDecimal maxDraft) {
        this.berth = berth;
        this.startTime = startTime;
        this.endTime = endTime;
        this.maxDraft = maxDraft;
    }

    public Long getId() {
        return id;
    }

    public Berth getBerth() {
        return berth;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public BigDecimal getMaxDraft() {
        return maxDraft;
    }

    public long getVersion() {
        return version;
    }

    public void update(Instant startTime, Instant endTime, BigDecimal maxDraft) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.maxDraft = maxDraft;
    }
}
