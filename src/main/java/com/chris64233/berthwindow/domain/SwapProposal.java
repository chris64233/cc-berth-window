package com.chris64233.berthwindow.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 泊位时段互换方案。
 *
 * <p>创建（freeze）时冻结双方的泊位、时段、申请版本、潮汐窗口版本签名与拖轮安排；
 * 确认时在单事务内按交换后的条件重新校验，全部满足才原子切换两份安排与资源占用。</p>
 *
 * <p>失效依据：双方申请均带 {@code @Version}，任一方改期、取消都会令申请版本变化；
 * 潮汐窗口带 {@code @Version}，资料更新会令潮汐签名变化——确认时快照不一致即判定方案失效。
 * {@code proposalNo} 为业务幂等键，数据库唯一约束兜底并发重复提交。</p>
 */
@Entity
@Table(name = "swap_proposal")
public class SwapProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 互换业务号（幂等键），全库唯一 */
    @Column(name = "proposal_no", nullable = false, unique = true, length = 64)
    private String proposalNo;

    // ---------------- 冻结：A 方 ----------------

    @Column(name = "a_application_id", nullable = false)
    private Long applicationAId;

    @Column(name = "a_application_no", nullable = false, length = 64)
    private String applicationANo;

    @Column(name = "a_berth_id", nullable = false)
    private Long aBerthId;

    @Column(name = "a_eta", nullable = false)
    private Instant aEta;

    @Column(name = "a_etd", nullable = false)
    private Instant aEtd;

    /** 冻结时 A 申请的乐观锁版本，确认时不一致即失效 */
    @Column(name = "a_app_version", nullable = false)
    private long aAppVersion;

    /** 冻结时 A 所需泊位类型的全部潮汐窗口版本签名 */
    @Column(name = "a_tide_signature", nullable = false, length = 2048)
    private String aTideSignature;

    /** 冻结时 A 的拖轮安排（拖轮 id 升序，逗号分隔），用于留存互换前安排 */
    @Column(name = "a_tug_ids", nullable = false, length = 512)
    private String aTugIds;

    // ---------------- 冻结：B 方 ----------------

    @Column(name = "b_application_id", nullable = false)
    private Long applicationBId;

    @Column(name = "b_application_no", nullable = false, length = 64)
    private String applicationBNo;

    @Column(name = "b_berth_id", nullable = false)
    private Long bBerthId;

    @Column(name = "b_eta", nullable = false)
    private Instant bEta;

    @Column(name = "b_etd", nullable = false)
    private Instant bEtd;

    @Column(name = "b_app_version", nullable = false)
    private long bAppVersion;

    @Column(name = "b_tide_signature", nullable = false, length = 2048)
    private String bTideSignature;

    @Column(name = "b_tug_ids", nullable = false, length = 512)
    private String bTugIds;

    // ---------------- 生命周期 ----------------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SwapStatus status = SwapStatus.PROPOSED;

    /** 最近一次确认失败的错误码（{@link com.chris64233.berthwindow.web.ErrorCode} 名称） */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    /** 最近一次确认失败的原因（互换后哪一方、哪项条件不满足或版本失效） */
    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 成功或最终失败落定时间 */
    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Version
    private long version;

    protected SwapProposal() {
    }

    public SwapProposal(String proposalNo,
                        Long applicationAId, String applicationANo, Long aBerthId,
                        Instant aEta, Instant aEtd, long aAppVersion,
                        String aTideSignature, String aTugIds,
                        Long applicationBId, String applicationBNo, Long bBerthId,
                        Instant bEta, Instant bEtd, long bAppVersion,
                        String bTideSignature, String bTugIds) {
        this.proposalNo = proposalNo;
        this.applicationAId = applicationAId;
        this.applicationANo = applicationANo;
        this.aBerthId = aBerthId;
        this.aEta = aEta;
        this.aEtd = aEtd;
        this.aAppVersion = aAppVersion;
        this.aTideSignature = aTideSignature;
        this.aTugIds = aTugIds;
        this.applicationBId = applicationBId;
        this.applicationBNo = applicationBNo;
        this.bBerthId = bBerthId;
        this.bEta = bEta;
        this.bEtd = bEtd;
        this.bAppVersion = bAppVersion;
        this.bTideSignature = bTideSignature;
        this.bTugIds = bTugIds;
        this.status = SwapStatus.PROPOSED;
        this.createdAt = Instant.now();
    }

    public void markConfirmed() {
        this.status = SwapStatus.CONFIRMED;
        this.finalizedAt = Instant.now();
        this.failureCode = null;
        this.failureReason = null;
    }

    public void markFailed(String failureCode, String failureReason) {
        this.status = SwapStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason, 1024);
        this.finalizedAt = Instant.now();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getProposalNo() {
        return proposalNo;
    }

    public Long getApplicationAId() {
        return applicationAId;
    }

    public String getApplicationANo() {
        return applicationANo;
    }

    public Long getABerthId() {
        return aBerthId;
    }

    public Instant getAEta() {
        return aEta;
    }

    public Instant getAEtd() {
        return aEtd;
    }

    public long getAAppVersion() {
        return aAppVersion;
    }

    public String getATideSignature() {
        return aTideSignature;
    }

    public String getATugIds() {
        return aTugIds;
    }

    public Long getApplicationBId() {
        return applicationBId;
    }

    public String getApplicationBNo() {
        return applicationBNo;
    }

    public Long getBBerthId() {
        return bBerthId;
    }

    public Instant getBEta() {
        return bEta;
    }

    public Instant getBEtd() {
        return bEtd;
    }

    public long getBAppVersion() {
        return bAppVersion;
    }

    public String getBTideSignature() {
        return bTideSignature;
    }

    public String getBTugIds() {
        return bTugIds;
    }

    public SwapStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public long getVersion() {
        return version;
    }
}
