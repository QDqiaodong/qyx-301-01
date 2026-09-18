package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户押金账户：客户在系统里可用于冻结押金的余额。
 * availableBalance-可用余额；frozenBalance-已为待办活动冻结的押金。
 * 冻结只在余额充足时成功；退回、没收都走押金流水台账，财务可逐笔核对。
 */
@Entity
@Table(name = "customer_account",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_name", "customer_phone"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    /** 电话可空；与姓名一起作为客户身份键 */
    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    /** 可用余额（尚未冻结的押金） */
    @Column(name = "available_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    /** 已冻结押金（待办活动占着的押金） */
    @Column(name = "frozen_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal frozenBalance = BigDecimal.ZERO;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
