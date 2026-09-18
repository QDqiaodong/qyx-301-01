package com.example.salon.repository;

import com.example.salon.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** 该需求当前的有效票（一条需求同时最多一张有效票） */
    Optional<Invoice> findByDemandIdAndStatus(Long demandId, String status);

    List<Invoice> findByDemandIdOrderByCreatedAtDesc(Long demandId);

    /** 发票台账：有效票与红字作废票逐笔可见 */
    List<Invoice> findAllByOrderByCreatedAtDesc();

    Optional<Invoice> findByInvoiceNo(String invoiceNo);

    /** 生成当天票号：取当天最大票号往下排 */
    Optional<Invoice> findTopByInvoiceNoStartingWithOrderByInvoiceNoDesc(String prefix);

    /** 批量取一批需求的有效票（给押金流水对票号用） */
    List<Invoice> findByDemandIdInAndStatus(Collection<Long> demandIds, String status);
}
