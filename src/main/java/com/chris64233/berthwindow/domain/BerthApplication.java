package com.chris64233.berthwindow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 泊位窗口申请。businessNo 为幂等键（数据库唯一约束），
 * version 为乐观锁，用于防止基于旧申请内容做出的审批/改期判断。
 */
@Entity
@Table(name = "berth_application",
        uniqueConstraints = @UniqueConstraint(name = "uk_application_business_no", columnNames = "business_no"))
public class BerthApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_no", nullable = false, length = 64)
    private String businessNo;

    @Column(name = "ship_name", nullable = false, length = 128)
    private String shipName;

    @Column(name = "ship_type", nullable = false, length = 32)
    private String shipType;

    @Column(name = "draft", nullable = false, precision = 6, scale = 2)
    private BigDecimal draft;

    @Column(name = "expected_arrival", nullable = false)
    private Instant expectedArrival;

    @Column(name = "expected_departure", nullable = false)
    private Instant expectedDeparture;

    @Column(name = "required_tugs", nullable = false)
    private int requiredTugs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ApplicationStatus status;

    @Column(name = "berth_code", length = 32)
    private String berthCode;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BerthApplication() {
    }

    private BerthApplication(String businessNo, String shipName, String shipType, BigDecimal draft,
                             Instant expectedArrival, Instant expectedDeparture, int requiredTugs,
                             ApplicationStatus status, String berthCode, String rejectReason) {
        this.businessNo = businessNo;
        this.shipName = shipName;
        this.shipType = shipType;
        this.draft = draft;
        this.expectedArrival = expectedArrival;
        this.expectedDeparture = expectedDeparture;
        this.requiredTugs = requiredTugs;
        this.status = status;
        this.berthCode = berthCode;
        this.rejectReason = rejectReason;
    }

    public static BerthApplication approved(String businessNo, String shipName, String shipType, BigDecimal draft,
                                            Instant arrival, Instant departure, int requiredTugs, String berthCode) {
        return new BerthApplication(businessNo, shipName, shipType, draft, arrival, departure, requiredTugs,
                ApplicationStatus.APPROVED, berthCode, null);
    }

    public static BerthApplication rejected(String businessNo, String shipName, String shipType, BigDecimal draft,
                                            Instant arrival, Instant departure, int requiredTugs, String reason) {
        return new BerthApplication(businessNo, shipName, shipType, draft, arrival, departure, requiredTugs,
                ApplicationStatus.REJECTED, null, reason);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * 幂等重放判断：除审批结果外的申请内容完全一致。
     */
    public boolean sameContentAs(String shipName, String shipType, BigDecimal draft,
                                 Instant arrival, Instant departure, int requiredTugs) {
        return this.shipName.equals(shipName)
                && this.shipType.equals(shipType)
                && this.draft.compareTo(draft) == 0
                && this.expectedArrival.equals(arrival)
                && this.expectedDeparture.equals(departure)
                && this.requiredTugs == requiredTugs;
    }

    public void reschedule(Instant newArrival, Instant newDeparture) {
        this.expectedArrival = newArrival;
        this.expectedDeparture = newDeparture;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public String getShipName() {
        return shipName;
    }

    public String getShipType() {
        return shipType;
    }

    public BigDecimal getDraft() {
        return draft;
    }

    public Instant getExpectedArrival() {
        return expectedArrival;
    }

    public Instant getExpectedDeparture() {
        return expectedDeparture;
    }

    public int getRequiredTugs() {
        return requiredTugs;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public String getBerthCode() {
        return berthCode;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
