package com.chris64233.berthwindow.domain;

import java.math.BigDecimal;
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
import jakarta.persistence.Version;

/**
 * 船舶泊位窗口申请。
 *
 * <p>{@code applicationNo} 为业务号，数据库唯一约束保证幂等；
 * {@code version} 乐观锁保证并发审批/改期不会基于旧内容判断。</p>
 */
@Entity
@Table(name = "berth_application",
        indexes = @Index(name = "idx_application_status", columnList = "status"))
public class BerthApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务申请号（幂等键），全库唯一 */
    @Column(nullable = false, unique = true, length = 64)
    private String applicationNo;

    @Column(nullable = false, length = 64)
    private String vesselCode;

    @Column(nullable = false, length = 64)
    private String vesselType;

    /** 预计靠泊时间 */
    @Column(nullable = false)
    private Instant eta;

    /** 预计离泊时间 */
    @Column(nullable = false)
    private Instant etd;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal draft;

    /** 所需泊位类型 */
    @Column(nullable = false, length = 64)
    private String requiredBerthType;

    /** 需要的拖轮数量（靠泊、离泊各需该数量的拖轮） */
    @Column(nullable = false)
    private int requiredTugs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApplicationStatus status = ApplicationStatus.PENDING;

    /** 审批通过后实际安排的泊位；未批准时为 null */
    @Column(name = "assigned_berth_id")
    private Long assignedBerthId;

    @Version
    private long version;

    protected BerthApplication() {
    }

    public BerthApplication(String applicationNo, String vesselCode, String vesselType,
                            Instant eta, Instant etd, BigDecimal draft,
                            String requiredBerthType, int requiredTugs) {
        this.applicationNo = applicationNo;
        this.vesselCode = vesselCode;
        this.vesselType = vesselType;
        this.eta = eta;
        this.etd = etd;
        this.draft = draft;
        this.requiredBerthType = requiredBerthType;
        this.requiredTugs = requiredTugs;
        this.status = ApplicationStatus.PENDING;
    }

    public void approve(Long berthId) {
        this.assignedBerthId = berthId;
        this.status = ApplicationStatus.APPROVED;
    }

    public void applyNewSchedule(Instant eta, Instant etd) {
        this.eta = eta;
        this.etd = etd;
    }

    /** 取消申请：已批准申请取消时由服务层先释放全部占用资源。 */
    public void cancel() {
        this.status = ApplicationStatus.CANCELLED;
        this.assignedBerthId = null;
    }

    /** 互换确认成功：占用对方的泊位与时段。 */
    public void applySwap(Long newBerthId, Instant newEta, Instant newEtd) {
        this.assignedBerthId = newBerthId;
        this.eta = newEta;
        this.etd = newEtd;
        this.status = ApplicationStatus.APPROVED;
    }

    public Long getId() {
        return id;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public String getVesselCode() {
        return vesselCode;
    }

    public String getVesselType() {
        return vesselType;
    }

    public Instant getEta() {
        return eta;
    }

    public Instant getEtd() {
        return etd;
    }

    public BigDecimal getDraft() {
        return draft;
    }

    public String getRequiredBerthType() {
        return requiredBerthType;
    }

    public int getRequiredTugs() {
        return requiredTugs;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Long getAssignedBerthId() {
        return assignedBerthId;
    }

    public long getVersion() {
        return version;
    }
}
