package com.example.salon.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 发票号流水计数器：开票时在独立事务里对该行加行级写锁递增，
 * 得到当天/全局不重号的票号流水。单行（scope='DAILY'），与具体发票解耦。
 */
@Entity
@Table(name = "invoice_no_sequence")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceNoSequence {

    @Id
    @Column(name = "scope_key", length = 16)
    private String scopeKey;

    @Column(name = "next_value", nullable = false)
    private Long nextValue = 1L;
}
