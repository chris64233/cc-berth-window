package com.chris64233.berthwindow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 港口级作业资源（单行配置，id 固定为 1）：全港可用拖轮总数。
 * 审批时对该行加悲观写锁，保证并发审批不会突破拖轮总量。
 */
@Entity
@Table(name = "port_resource")
public class PortResource {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "total_tugs", nullable = false)
    private int totalTugs;

    @Version
    private long version;

    protected PortResource() {
    }

    public PortResource(int totalTugs) {
        this.id = SINGLETON_ID;
        this.totalTugs = totalTugs;
    }

    public Long getId() {
        return id;
    }

    public int getTotalTugs() {
        return totalTugs;
    }

    public void setTotalTugs(int totalTugs) {
        this.totalTugs = totalTugs;
    }
}
