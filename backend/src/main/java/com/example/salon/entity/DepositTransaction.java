package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户押金流水台账：每一笔押金冻结、退回、没收、充值都落一条，财务逐笔可见。
 * 冻结（FROZEN）与结算（REFUND）通过 freezeTransactionId 关联，一一对应。
 * 退回比例由后端按取消日期与活动日的距离分档计算，任何接口都不接收手填比例，
 * 销售无法在门口改比例，财务账上比例固定。
 */
@Entity
@Table(name = "deposit_transaction")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepositTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "demand_id")
    private Long demandId;

    @Column(name = "demand_name", length = 200)
    private String demandName;

    @Column(name = "venue_id")
    private Long venueId;

    @Column(name = "venue_name", length = 100)
    private String venueName;

    /**
     * 流水类型：
     * RECHARGE-充值入账；FREEZE-冻结押金；
     * REFUND-取消活动结算退回（含没收部分）；UNFREEZE-解除重配/场地原因全额解冻退回。
     */
    @Column(name = "type", nullable = false, length = 12)
    private String type;

    /** 冻结金额（FREEZE 为正）；结算流水记当时冻结的押金全额，便于财务核对 */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 实际退回客户可用余额的金额 */
    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /** 按分档规则没收的金额（amount - refundAmount） */
    @Column(name = "forfeit_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal forfeitAmount = BigDecimal.ZERO;

    /** 退回比例（%）：100-全额，50-一半，0-不退；由后端按活动日距离分档计算 */
    @Column(name = "refund_rate", precision = 5, scale = 2)
    private BigDecimal refundRate;

    /** 结算原因：含取消日期、活动日期、距活动天数、适用档位说明 */
    @Column(name = "reason", length = 500)
    private String reason;

    /** 结算流水所对应的冻结流水 ID */
    @Column(name = "freeze_transaction_id")
    private Long freezeTransactionId;

    @Column(name = "activity_date")
    private LocalDateTime activityDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ==================== 关联的当前有效结算发票（只读快照，不落库，由 InvoiceService 挂载） ====================
    // 押金流水与发票台账、需求详情看到的必须是同一张有效票号、同一金额；
    // 旧票作废成红字后流水上不再挂旧票号，改挂新票。
    @Transient
    private Long currentInvoiceId;

    @Transient
    private String currentInvoiceNo;

    @Transient
    private BigDecimal currentInvoiceAmount;

    @Transient
    private String currentInvoiceStatus;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
