package com.chris64233.berthwindow.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * 互换方案中一方的冻结快照：互换前安排、互换后安排（对方的泊位与时段）、
 * 申请资料版本、目标泊位资料版本，以及交换后靠/离泊拟使用的同一批拖轮。
 */
@Entity
@Table(name = "berth_swap_side")
public class BerthSwapSide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "application_no", nullable = false, length = 64)
    private String applicationNo;

    // 互换前安排
    @Column(name = "from_berth_id", nullable = false)
    private Long fromBerthId;

    @Column(name = "from_berth_code", nullable = false, length = 64)
    private String fromBerthCode;

    @Column(name = "from_eta", nullable = false)
    private Instant fromEta;

    @Column(name = "from_etd", nullable = false)
    private Instant fromEtd;

    // 互换后安排（对方当前泊位与时段，需重新满足本船条件）
    @Column(name = "to_berth_id", nullable = false)
    private Long toBerthId;

    @Column(name = "to_berth_code", nullable = false, length = 64)
    private String toBerthCode;

    @Column(name = "to_eta", nullable = false)
    private Instant toEta;

    @Column(name = "to_etd", nullable = false)
    private Instant toEtd;

    // 冻结的申请资料
    @Column(name = "vessel_type", nullable = false, length = 64)
    private String vesselType;

    @Column(name = "draft", nullable = false, precision = 6, scale = 2)
    private BigDecimal draft;

    @Column(name = "required_berth_type", nullable = false, length = 64)
    private String requiredBerthType;

    @Column(name = "required_tugs", nullable = false)
    private int requiredTugs;

    @Column(name = "application_version", nullable = false)
    private long applicationVersion;

    // 冻结的目标泊位资料
    @Column(name = "to_berth_type", nullable = false, length = 64)
    private String toBerthType;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "berth_swap_side_vessel_type",
            joinColumns = @JoinColumn(name = "swap_side_id"))
    @Column(name = "vessel_type", nullable = false, length = 64)
    private Set<String> toBerthAcceptedVesselTypes = new LinkedHashSet<>();

    @Column(name = "to_berth_max_draft", nullable = false, precision = 6, scale = 2)
    private BigDecimal toBerthMaxDraft;

    @Column(name = "to_berth_capacity", nullable = false)
    private int toBerthCapacity;

    @Column(name = "to_berth_version", nullable = false)
    private long toBerthVersion;

    /** 交换后靠/离泊使用的同一批拖轮 id */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "berth_swap_side_tug",
            joinColumns = @JoinColumn(name = "swap_side_id"))
    @OrderColumn(name = "position")
    @Column(name = "tug_id", nullable = false)
    private List<Long> tugIds = new ArrayList<>();

    protected BerthSwapSide() {
    }

    public BerthSwapSide(Long applicationId, String applicationNo,
                         Long fromBerthId, String fromBerthCode, Instant fromEta, Instant fromEtd,
                         Long toBerthId, String toBerthCode, Instant toEta, Instant toEtd,
                         String vesselType, BigDecimal draft, String requiredBerthType,
                         int requiredTugs, long applicationVersion,
                         String toBerthType, Set<String> toBerthAcceptedVesselTypes,
                         BigDecimal toBerthMaxDraft, int toBerthCapacity, long toBerthVersion,
                         List<Long> tugIds) {
        this.applicationId = applicationId;
        this.applicationNo = applicationNo;
        this.fromBerthId = fromBerthId;
        this.fromBerthCode = fromBerthCode;
        this.fromEta = fromEta;
        this.fromEtd = fromEtd;
        this.toBerthId = toBerthId;
        this.toBerthCode = toBerthCode;
        this.toEta = toEta;
        this.toEtd = toEtd;
        this.vesselType = vesselType;
        this.draft = draft;
        this.requiredBerthType = requiredBerthType;
        this.requiredTugs = requiredTugs;
        this.applicationVersion = applicationVersion;
        this.toBerthType = toBerthType;
        this.toBerthAcceptedVesselTypes = new LinkedHashSet<>(toBerthAcceptedVesselTypes);
        this.toBerthMaxDraft = toBerthMaxDraft;
        this.toBerthCapacity = toBerthCapacity;
        this.toBerthVersion = toBerthVersion;
        this.tugIds = new ArrayList<>(tugIds);
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

    public Long getFromBerthId() {
        return fromBerthId;
    }

    public String getFromBerthCode() {
        return fromBerthCode;
    }

    public Instant getFromEta() {
        return fromEta;
    }

    public Instant getFromEtd() {
        return fromEtd;
    }

    public Long getToBerthId() {
        return toBerthId;
    }

    public String getToBerthCode() {
        return toBerthCode;
    }

    public Instant getToEta() {
        return toEta;
    }

    public Instant getToEtd() {
        return toEtd;
    }

    public String getVesselType() {
        return vesselType;
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

    public long getApplicationVersion() {
        return applicationVersion;
    }

    public String getToBerthType() {
        return toBerthType;
    }

    public Set<String> getToBerthAcceptedVesselTypes() {
        return toBerthAcceptedVesselTypes;
    }

    public BigDecimal getToBerthMaxDraft() {
        return toBerthMaxDraft;
    }

    public int getToBerthCapacity() {
        return toBerthCapacity;
    }

    public long getToBerthVersion() {
        return toBerthVersion;
    }

    public List<Long> getTugIds() {
        return tugIds;
    }
}
