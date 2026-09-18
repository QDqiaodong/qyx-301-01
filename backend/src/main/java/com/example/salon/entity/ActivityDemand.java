package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "activity_demand")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDemand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "demand_name", nullable = false, length = 200)
    private String demandName;

    @Column(name = "expected_date")
    private LocalDateTime expectedDate;

    @Column(name = "expected_people", nullable = false)
    private Integer expectedPeople;

    @Column(name = "activity_category", nullable = false, length = 100)
    private String activityCategory;

    @Column(name = "budget_min", precision = 10, scale = 2)
    private BigDecimal budgetMin;

    @Column(name = "budget_max", precision = 10, scale = 2)
    private BigDecimal budgetMax;

    @Column(name = "required_facilities", length = 500)
    private String requiredFacilities;

    @Column(name = "special_requirements", length = 1000)
    private String specialRequirements;

    @Column(name = "match_status", nullable = false)
    private Integer matchStatus = 0;

    /**
     * 是否已锁定：0-未锁定，1-已锁定。
     * 锁定期间期望日期、人数、预算上限、必备设施冻结，不能直接修改。
     */
    @Column(name = "locked", nullable = false)
    private Integer locked = 0;

    @Column(name = "locked_venue_id")
    private Long lockedVenueId;

    @Column(name = "locked_venue_name", length = 100)
    private String lockedVenueName;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /**
     * 锁定自行破裂原因；非空表示当前处于「待重配」状态（matchStatus=4）。
     */
    @Column(name = "lock_break_reason", length = 500)
    private String lockBreakReason;

    /**
     * 当前为该需求冻结的押金金额；非空且大于0表示押金已冻结、场地已占。
     * 取消/解除/破裂结算退回后清空。
     */
    @Column(name = "deposit_amount", precision = 12, scale = 2)
    private BigDecimal depositAmount;

    /** 押金冻结流水 ID（财务台账对应记录） */
    @Column(name = "deposit_freeze_id")
    private Long depositFreezeId;

    /** 最近一次押金结算说明（退回比例/金额/没收金额），供页面与历史记录展示 */
    @Column(name = "deposit_refund_summary", length = 500)
    private String depositRefundSummary;

    /**
     * 是否已开场：0-未开场（停在已锁定），1-已开场。
     * 开场当天主值守、机动两岗都签到齐全才能开场；
     * 值守中途撤岗两岗不齐时，已开场的退回已锁定（opened 回到 0），押金冻结不受影响。
     */
    @Column(name = "opened", nullable = false)
    private Integer opened = 0;

    /** 开场时间（退回已锁定时清空） */
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ==================== 当前有效结算发票（只读快照，不落库，由 InvoiceService 挂载） ====================
    // 需求详情、发票台账、押金流水三处必须看到同一张有效票号与同一金额；
    // 旧票作废成红字后这几个字段为空，挂的是新开的票。

    @Transient
    private Long currentInvoiceId;

    @Transient
    private String currentInvoiceNo;

    @Transient
    private BigDecimal currentInvoiceAmount;

    /** VALID-有效票；无有效票（含全部已作废）为 null */
    @Transient
    private String currentInvoiceStatus;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
