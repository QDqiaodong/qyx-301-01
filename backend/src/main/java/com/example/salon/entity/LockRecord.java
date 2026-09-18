package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 场地锁定记录：客户在推荐结果中确认某家场地后生成。
 * 场地被停用、日租金抬过预算上限、必备设施被拆除时锁定自行破裂，
 * 历史记录页可查看每次破裂原因。
 */
@Entity
@Table(name = "lock_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LockRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "demand_name", length = 200)
    private String demandName;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "venue_name", nullable = false, length = 100)
    private String venueName;

    /** LOCKED-有效锁定；BROKEN-已自行破裂；RELEASED-已手动解除；CANCELED-活动已取消（按分档退押） */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    /** 破裂原因（status=BROKEN 时有值） */
    @Column(name = "break_reason", length = 500)
    private String breakReason;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @PrePersist
    protected void onCreate() {
        lockedAt = LocalDateTime.now();
    }
}
