package com.example.salon.repository;

import com.example.salon.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** 一条需求当前的有效票（最多一条，唯一索引兜底） */
    Optional<Invoice> findByDemandIdAndStatus(Long demandId, String status);

    List<Invoice> findByDemandIdOrderByIssuedAtDesc(Long demandId);

    List<Invoice> findAllByOrderByIssuedAtDesc();

    /** 一批需求当前的有效票（批量挂载，避免列表逐条查） */
    List<Invoice> findByDemandIdInAndStatus(Collection<Long> demandIds, String status);

    /**
     * 找出租用了给定押金流水（冻结行或结算行）的有效发票：
     * 台账上每张有效票必须能在对应冻结/退回流水行看到同一个票号。
     */
    @Query("SELECT i FROM Invoice i WHERE i.status = :status AND ("
            + "i.freezeTransactionId IN :txIds OR i.settlementTransactionId IN :txIds)")
    List<Invoice> findValidLinkedToTransactions(@Param("txIds") Collection<Long> txIds,
                                                @Param("status") String status);

    /**
     * 场地改名后同步发票台账上的场地名（只改场地名一列，票号、金额、客户、状态都不动）。
     */
    @Modifying
    @Query("UPDATE Invoice i SET i.venueName = :newName WHERE i.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);
}
