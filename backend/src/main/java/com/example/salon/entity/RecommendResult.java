package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommend_result")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "venue_name", length = 100)
    private String venueName;

    @Column(name = "match_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal matchScore;

    @Column(name = "capacity_score", precision = 5, scale = 2)
    private BigDecimal capacityScore;

    @Column(name = "facility_score", precision = 5, scale = 2)
    private BigDecimal facilityScore;

    @Column(name = "activity_type_score", precision = 5, scale = 2)
    private BigDecimal activityTypeScore;

    @Column(name = "budget_score", precision = 5, scale = 2)
    private BigDecimal budgetScore;

    @Column(name = "recommend_order")
    private Integer recommendOrder;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
