package com.chris64233.berthwindow.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 泊位占用：一次已批准申请对应一段连续的泊位时间（半开区间 [start, end)）。
 *
 * <p>容量由服务层在数据库行锁保护下判断；{@code (application_id)} 的唯一约束
 * 保证同一申请在同一时刻至多存在一条占用记录（改期为先删后插）。</p>
 */
@Entity
@Table(name = "berth_occupation",
        uniqueConstraints = @UniqueConstraint(name = "uk_occupation_application",
                columnNames = "application_id"),
        indexes = @Index(name = "idx_occupation_berth_time",
                columnList = "berth_id,startTime,endTime"))
public class BerthOccupation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "berth_id", nullable = false)
    private Long berthId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    protected BerthOccupation() {
    }

    public BerthOccupation(Long berthId, Long applicationId, Instant startTime, Instant endTime) {
        this.berthId = berthId;
        this.applicationId = applicationId;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getId() {
        return id;
    }

    public Long getBerthId() {
        return berthId;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }
}
