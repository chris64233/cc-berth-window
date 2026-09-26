package com.chris64233.berthwindow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 资源占用记录：一次批准同时占用 [startTime, endTime) 的连续泊位时间，
 * 以及靠泊（startTime 起）与离泊（endTime 起）两个作业时段的拖轮能力。
 */
@Entity
@Table(name = "berth_reservation",
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_application", columnNames = "application_id"))
public class BerthReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private BerthApplication application;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "berth_id", nullable = false)
    private Berth berth;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "tug_count", nullable = false)
    private int tugCount;

    protected BerthReservation() {
    }

    public BerthReservation(BerthApplication application, Berth berth,
                            Instant startTime, Instant endTime, int tugCount) {
        this.application = application;
        this.berth = berth;
        this.startTime = startTime;
        this.endTime = endTime;
        this.tugCount = tugCount;
    }

    public void moveTo(Instant newStart, Instant newEnd) {
        this.startTime = newStart;
        this.endTime = newEnd;
    }

    public Long getId() {
        return id;
    }

    public BerthApplication getApplication() {
        return application;
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

    public int getTugCount() {
        return tugCount;
    }
}
