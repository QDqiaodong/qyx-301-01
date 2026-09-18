package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 踩点试场登记。每条需求至多一档。
 * 试场只占半天（上午/下午），登记时写预留人数，
 * 用于正式活动推荐时折算场地当天的可排容量。
 */
@Entity
@Table(name = "site_visit",
        uniqueConstraints = @UniqueConstraint(name = "uk_site_visit_demand", columnNames = "demand_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SiteVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "venue_name", nullable = false, length = 100)
    private String venueName;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** 时段：MORNING-上午，AFTERNOON-下午，只能二选一 */
    @Column(name = "time_slot", nullable = false, length = 10)
    private String timeSlot;

    @Column(name = "reserved_people", nullable = false)
    private Integer reservedPeople;

    /** 登记时场地是否已有正式活动锁定（快照），预留字段 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
