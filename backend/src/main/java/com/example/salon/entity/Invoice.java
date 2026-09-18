package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算发票台账：只有押金已经结清的需求才能开票——
 *   活动已经开场（冻结押金随开场确认结清），或冻结押金已全额实退回客户账户。
 * 票面金额必须等于押金流水里那笔已经结清的冻结金额或实退金额，客户名取押金账户户主。
 * 一条需求同一时刻最多一张有效票：active_flag 仅 VALID 行取 1、作废行为 NULL，
 * 配合唯一索引（MySQL 唯一索引允许多个 NULL），两人前后脚开也只放行一张。
 * 旧票作废为红字（RED_VOID）后才能再开新票，作废必须留下原因；
 * 作废后旧票号不再是有效票，新开的票才是当前有效票。
 */
@Entity
@Table(name = "settlement_invoice", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_no", columnNames = {"invoice_no"}),
        @UniqueConstraint(name = "uk_invoice_demand_active", columnNames = {"demand_id", "active_flag"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    /** 有效票（可报销/对账的当前有效票） */
    public static final String STATUS_VALID = "VALID";
    /** 已作废的红字票：旧票号留痕但不能再当有效票报 */
    public static final String STATUS_RED_VOID = "RED_VOID";

    /** 开票依据：活动已开场，冻结押金按冻结额结清 */
    public static final String BASIS_FROZEN_OPENED = "FROZEN_OPENED";
    /** 开票依据：冻结押金已 100% 实退回客户账户，按实退额结清 */
    public static final String BASIS_FULLY_REFUNDED = "FULLY_REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发票号：FP + 开票日 + 全局流水号，全局唯一 */
    @Column(name = "invoice_no", nullable = false, length = 32)
    private String invoiceNo;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "demand_name", nullable = false, length = 200)
    private String demandName;

    /** 押金账户ID：票面上的客户必须与押金账户户主同一人 */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "venue_id")
    private Long venueId;

    @Column(name = "venue_name", length = 100)
    private String venueName;

    @Column(name = "activity_date")
    private LocalDateTime activityDate;

    /** 票面金额：等于押金流水中已结清的冻结金额或实退金额，开票时由流水核算，不接受手填 */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 开票依据：FROZEN_OPENED-已开场按冻结额；FULLY_REFUNDED-全额退完按实退额 */
    @Column(name = "basis_type", nullable = false, length = 20)
    private String basisType;

    /** 作为开票依据的冻结流水ID */
    @Column(name = "freeze_transaction_id")
    private Long freezeTransactionId;

    /** 作为开票依据的结算（退回）流水ID；已开场冻结未退时为空 */
    @Column(name = "settlement_transaction_id")
    private Long settlementTransactionId;

    /** 开票依据说明：结清方式、对应流水号与金额，便于台账与流水对账 */
    @Column(name = "basis_reason", length = 500)
    private String basisReason;

    /** VALID-有效票；RED_VOID-已作废红字票 */
    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** 有效票标记：VALID=1，作废=null；唯一索引保证一条需求最多一张有效票 */
    @Column(name = "active_flag")
    private Integer activeFlag;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /** 开票操作人（财务） */
    @Column(name = "issued_by", length = 50)
    private String issuedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    /** 作废操作人（财务） */
    @Column(name = "voided_by", length = 50)
    private String voidedBy;

    /** 红字作废原因：作废必填，旧票留痕可查 */
    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @PrePersist
    protected void onCreate() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }
}
