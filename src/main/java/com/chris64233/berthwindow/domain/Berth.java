package com.chris64233.berthwindow.domain;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 泊位：记录可接纳的船型集合、最大吃水以及同时作业能力（可同时挂靠的船舶数）。
 */
@Entity
@Table(name = "berth")
public class Berth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 泊位类型，如 CONTAINER / BULK / OIL，与申请的 requiredBerthType 匹配 */
    @Column(nullable = false, length = 64)
    private String berthType;

    /** 可接纳的船型集合 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "berth_accepted_vessel_type",
            joinColumns = @JoinColumn(name = "berth_id"))
    @Column(name = "vessel_type", nullable = false, length = 64)
    private Set<String> acceptedVesselTypes = new LinkedHashSet<>();

    /** 泊位最大允许吃水（米） */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal maxDraft;

    /** 同时作业能力：同一时段可在泊的最大船舶数 */
    @Column(nullable = false)
    private int simultaneousCapacity;

    @Version
    private long version;

    protected Berth() {
    }

    public Berth(String code, String name, String berthType, Set<String> acceptedVesselTypes,
                 BigDecimal maxDraft, int simultaneousCapacity) {
        this.code = code;
        this.name = name;
        this.berthType = berthType;
        this.acceptedVesselTypes = new LinkedHashSet<>(acceptedVesselTypes);
        this.maxDraft = maxDraft;
        this.simultaneousCapacity = simultaneousCapacity;
    }

    public boolean accepts(String vesselType) {
        return acceptedVesselTypes.contains(vesselType);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getBerthType() {
        return berthType;
    }

    public Set<String> getAcceptedVesselTypes() {
        return acceptedVesselTypes;
    }

    public BigDecimal getMaxDraft() {
        return maxDraft;
    }

    public int getSimultaneousCapacity() {
        return simultaneousCapacity;
    }

    public long getVersion() {
        return version;
    }
}
