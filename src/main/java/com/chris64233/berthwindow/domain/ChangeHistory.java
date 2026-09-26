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

/**
 * 申请变更历史：记录提交、审批、改期等关键动作及资源安排明细。
 */
@Entity
@Table(name = "change_history",
        indexes = @Index(name = "idx_history_application", columnList = "application_id"))
public class ChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(nullable = false, length = 64)
    private String applicationNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HistoryAction action;

    @Column(nullable = false, length = 1024)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt;

    protected ChangeHistory() {
    }

    public ChangeHistory(Long applicationId, String applicationNo, HistoryAction action,
                         String detail, Instant occurredAt) {
        this.applicationId = applicationId;
        this.applicationNo = applicationNo;
        this.action = action;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public HistoryAction getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
