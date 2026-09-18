package com.example.salon.repository;

import com.example.salon.entity.DepositTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepositTransactionRepository extends JpaRepository<DepositTransaction, Long> {

    List<DepositTransaction> findByDemandIdOrderByCreatedAtDesc(Long demandId);

    List<DepositTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<DepositTransaction> findAllByOrderByCreatedAtDesc();

    /**
     * 场地改名后同步押金流水上的场地名。
     * 只改场地名一列：冻结行的金额、客户、退回比例、冻结/结算时间一律保留，
     * 财务留住改名前冻过押金的那一笔，绝不为对齐名称把旧冻结作废重冻。
     */
    @Modifying
    @Query("UPDATE DepositTransaction t SET t.venueName = :newName WHERE t.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);
}
