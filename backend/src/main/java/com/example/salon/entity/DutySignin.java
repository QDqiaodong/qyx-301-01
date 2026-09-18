package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开场当天值守签到：每块场地当天有主值守（PRIMARY）、机动（FLEX）两个岗，
 * 两岗都签到齐全，场地才能从已锁定变成可开场。
 * 同一次锁定（lock_record_id）每个岗只有一行：两人前后脚抢同一岗时，
 * 数据库唯一约束只放行一个，失败方收到「该岗已被占用」。
 * 解除/取消/破裂后重新锁定是新的锁定记录，旧签到自然失效，不会带进新一场。
 */
@Entity
@Table(name = "duty_signin", uniqueConstraints = {
        @UniqueConstraint(name = "uk_duty_lock_post", columnNames = {"lock_record_id", "post"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DutySignin {

    /** 主值守岗 */
    public static final String POST_PRIMARY = "PRIMARY";
    /** 机动岗 */
    public static final String POST_FLEX = "FLEX";

    /** 在岗 */
    public static final String STATUS_SIGNED = "SIGNED";
    /** 已撤岗（该岗空出，可重新签到） */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "demand_name", length = 200)
    private String demandName;

    /** 本次锁定的锁定记录ID：签到挂在这一次锁定上，重新锁定时旧签到不生效 */
    @Column(name = "lock_record_id", nullable = false)
    private Long lockRecordId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "venue_name", nullable = false, length = 100)
    private String venueName;

    /** 活动日（开场当天） */
    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    /** PRIMARY-主值守；FLEX-机动 */
    @Column(name = "post", nullable = false, length = 10)
    private String post;

    @Column(name = "staff_name", nullable = false, length = 50)
    private String staffName;

    /** SIGNED-在岗；WITHDRAWN-已撤岗 */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @PrePersist
    protected void onCreate() {
        if (signedAt == null) {
            signedAt = LocalDateTime.now();
        }
    }
}
