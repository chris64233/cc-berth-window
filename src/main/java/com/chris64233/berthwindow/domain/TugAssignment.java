package com.chris64233.berthwindow.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 拖轮占用：一艘拖轮在某一时刻为某次靠泊/离泊提供协助。
 *
 * <p>数据库唯一约束 {@code (tug_id, action_time)} 是拖轮容量的最终保证——
 * 并发申请争抢同一艘拖轮同一时刻时，后提交者必然触发约束冲突而整体回滚，
 * 不依赖进程内判断。</p>
 */
@Entity
@Table(name = "tug_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_tug_time",
                columnNames = {"tug_id", "action_time"}),
        indexes = {
                @Index(name = "idx_tug_assignment_app", columnList = "application_id"),
                @Index(name = "idx_tug_assignment_time", columnList = "action_time")
        })
public class TugAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tug_id", nullable = false)
    private Long tugId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 16)
    private BerthActionType actionType;

    @Column(name = "action_time", nullable = false)
    private Instant actionTime;

    protected TugAssignment() {
    }

    public TugAssignment(Long tugId, Long applicationId, BerthActionType actionType, Instant actionTime) {
        this.tugId = tugId;
        this.applicationId = applicationId;
        this.actionType = actionType;
        this.actionTime = actionTime;
    }

    public Long getId() {
        return id;
    }

    public Long getTugId() {
        return tugId;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public BerthActionType getActionType() {
        return actionType;
    }

    public Instant getActionTime() {
        return actionTime;
    }
}
