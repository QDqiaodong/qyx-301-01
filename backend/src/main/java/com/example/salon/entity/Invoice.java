package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算发票：活动已开场、或押金已全额退完之后，财务为这条需求开的结算发票。
 * 票面金额必须等于该需求已经结清的冻结金额（开场路径）或实退金额（全额退押路径），
 * 客户名与押金账户持有人是同一人（开票时从押金账户带出，不能填别人）。
 * 还在冻结中、没开场也没退完的需求开不了票。
 * 一条需求同时只有一张有效票；重开前必须把旧票红字作废并留下作废原因，
 * 作废后旧票号留痕但不再是有效票，新开的票才是当前有效票。
 */
@Entity
@Table(name = "invoice")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    /** 有效票 */
    public static final String STATUS_VALID = "VALID";
    /** 红字作废：留痕但不再有效 */
    public static final String STATUS_VOID = "VOID";

    /** 结清依据：活动已开场（冻结押金转为已结清） */
    public static final String BASIS_OPENED = "OPENED";
    /** 结清依据：押金已全额退完（实退金额结清） */
    public static final String BASIS_REFUNDED = "REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发票号：全库唯一，作废后也不再复用 */
    @Column(name = "invoice_no", nullable = false, unique = true, length = 40)
    private String invoiceNo;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "demand_name", length = 200)
    private String demandName;

    /** 押金账户 ID：票面客户必须跟该账户持有人同一人 */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    /** 票面金额 = 该需求已结清的冻结或实退金额，与押金流水对得上 */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 结清依据：OPENED-活动已开场；REFUNDED-押金已全额退完 */
    @Column(name = "settle_basis", nullable = false, length = 12)
    private String settleBasis;

    /** 对应的押金冻结流水（开票金额对到这笔流水） */
    @Column(name = "freeze_transaction_id")
    private Long freezeTransactionId;

    /** 全额退押路径对应的结算流水；开场路径为空 */
    @Column(name = "settle_transaction_id")
    private Long settleTransactionId;

    /** VALID-有效；VOID-红字作废（旧票号不能再当有效票去报） */
    @Column(name = "status", nullable = false, length = 8)
    private String status = STATUS_VALID;

    /** 红字作废原因（作废必填，留痕） */
    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    /** 开票的财务操作人 */
    @Column(name = "issued_by", length = 100)
    private String issuedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
