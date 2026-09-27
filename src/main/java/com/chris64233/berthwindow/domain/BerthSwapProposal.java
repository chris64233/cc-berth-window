package com.chris64233.berthwindow.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 泊位时段互换方案。
 *
 * <p>方案在「提议」阶段冻结双方的泊位、时间、潮汐资料与拖轮安排（{@code sides} 与
 * {@code tideSnapshots}），并立即按交换后的条件完成一次全量校验；「确认」阶段在单事务内
 * 依据冻结快照重新校验，任一方不满足则确认失败（{@link SwapProposalStatus#FAILED}），
 * 原两份批准安排原样保留。</p>
 *
 * <p>{@code swapNo} 为业务幂等键（数据库唯一约束）；{@code version} 乐观锁保证
 * 两个并发确认不会重复交换——方案行写锁串行化同一方案的确认，终态不允许再次交换。</p>
 */
@Entity
@Table(name = "berth_swap_proposal",
        indexes = {
                @Index(name = "idx_swap_app_a", columnList = "application_a_id"),
                @Index(name = "idx_swap_app_b", columnList = "application_b_id")
        })
public class BerthSwapProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 互换业务号（幂等键），全库唯一 */
    @Column(name = "swap_no", nullable = false, unique = true, length = 64)
    private String swapNo;

    @Column(name = "application_a_id", nullable = false)
    private Long applicationAId;

    @Column(name = "application_a_no", nullable = false, length = 64)
    private String applicationANo;

    @Column(name = "application_b_id", nullable = false)
    private Long applicationBId;

    @Column(name = "application_b_no", nullable = false, length = 64)
    private String applicationBNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SwapProposalStatus status = SwapProposalStatus.PROPOSED;

    /** 冻结快照：下标 0 为 A 方、1 为 B 方（含互换前后安排、资料版本与选定拖轮） */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "swap_proposal_id", nullable = false)
    @OrderColumn(name = "position")
    private List<BerthSwapSide> sides = new ArrayList<>();

    /** 冻结的涉及泊位类型全部潮汐窗口（含版本号） */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "swap_proposal_id", nullable = false)
    @OrderColumn(name = "position")
    private List<TideWindowSnapshotEntity> tideSnapshots = new ArrayList<>();

    /** 确认失败原因；成功或仍待确认时为 null */
    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant confirmedAt;

    @Version
    private long version;

    protected BerthSwapProposal() {
    }

    public BerthSwapProposal(String swapNo, BerthSwapSide sideA, BerthSwapSide sideB,
                             List<TideWindowSnapshotEntity> tideSnapshots, Instant createdAt) {
        this.swapNo = swapNo;
        this.applicationAId = sideA.getApplicationId();
        this.applicationANo = sideA.getApplicationNo();
        this.applicationBId = sideB.getApplicationId();
        this.applicationBNo = sideB.getApplicationNo();
        this.sides.add(sideA);
        this.sides.add(sideB);
        this.tideSnapshots = new ArrayList<>(tideSnapshots);
        this.status = SwapProposalStatus.PROPOSED;
        this.createdAt = createdAt;
    }

    /** A 方冻结快照 */
    public BerthSwapSide sideA() {
        return sides.get(0);
    }

    /** B 方冻结快照 */
    public BerthSwapSide sideB() {
        return sides.get(1);
    }

    public void markFailed(String reason, Instant at) {
        this.status = SwapProposalStatus.FAILED;
        this.failureReason = reason;
        this.confirmedAt = at;
    }

    public void markConfirmed(Instant at) {
        this.status = SwapProposalStatus.CONFIRMED;
        this.confirmedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getSwapNo() {
        return swapNo;
    }

    public Long getApplicationAId() {
        return applicationAId;
    }

    public String getApplicationANo() {
        return applicationANo;
    }

    public Long getApplicationBId() {
        return applicationBId;
    }

    public String getApplicationBNo() {
        return applicationBNo;
    }

    public SwapProposalStatus getStatus() {
        return status;
    }

    public List<BerthSwapSide> getSides() {
        return sides;
    }

    public List<TideWindowSnapshotEntity> getTideSnapshots() {
        return tideSnapshots;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public long getVersion() {
        return version;
    }
}
