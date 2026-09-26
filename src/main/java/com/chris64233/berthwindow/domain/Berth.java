package com.chris64233.berthwindow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * 泊位：记录可接纳船型、最大吃水与同时作业能力（可同时停靠作业的船舶数）。
 */
@Entity
@Table(name = "berth", uniqueConstraints = @UniqueConstraint(name = "uk_berth_code", columnNames = "code"))
public class Berth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "berth_type", nullable = false, length = 32)
    private String berthType;

    @Column(name = "max_draft", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxDraft;

    @Column(name = "concurrent_capacity", nullable = false)
    private int concurrentCapacity;

    protected Berth() {
    }

    public Berth(String code, String berthType, BigDecimal maxDraft, int concurrentCapacity) {
        this.code = code;
        this.berthType = berthType;
        this.maxDraft = maxDraft;
        this.concurrentCapacity = concurrentCapacity;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getBerthType() {
        return berthType;
    }

    public BigDecimal getMaxDraft() {
        return maxDraft;
    }

    public int getConcurrentCapacity() {
        return concurrentCapacity;
    }
}
