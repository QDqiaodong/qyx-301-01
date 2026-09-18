package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当天开场条：主值守、机动两岗签到齐全后点开场生成，场地从已锁定变成已开场。
 * 值守中途撤岗导致两岗不齐时，已开场的退回已锁定，当天开场条作废（status=VOID），
 * 但撤岗不动押金冻结——押金仍冻结在客户账户里，不随撤岗冲掉。
 * active_flag 仅 OPEN 行取 1、VOID 行为 NULL，配合唯一索引保证
 * 一次锁定最多一条有效开场条（MySQL 唯一索引允许多个 NULL）。
 */
@Entity
@Table(name = "opening_record", uniqueConstraints = {
        @UniqueConstraint(name = "uk_opening_lock_active", columnNames = {"lock_record_id", "active_flag"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpeningRecord {

    /** 已开场（有效开场条） */
    public static final String STATUS_OPEN = "OPEN";
    /** 已作废（值守撤岗两岗不齐，退回已锁定） */
    public static final String STATUS_VOID = "VOID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "demand_name", length = 200)
    private String demandName;

    /** 本次锁定的锁定记录ID */
    @Column(name = "lock_record_id", nullable = false)
    private Long lockRecordId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "venue_name", nullable = false, length = 100)
    private String venueName;

    /** 活动日（开场当天） */
    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    /** OPEN-已开场；VOID-已作废 */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    /** 有效开场标记：OPEN=1，VOID=null；唯一索引保证一次锁定只有一条有效开场条 */
    @Column(name = "active_flag")
    private Integer activeFlag;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    /** 作废原因（值守撤岗说明） */
    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @PrePersist
    protected void onCreate() {
        if (openedAt == null) {
            openedAt = LocalDateTime.now();
        }
    }
}
