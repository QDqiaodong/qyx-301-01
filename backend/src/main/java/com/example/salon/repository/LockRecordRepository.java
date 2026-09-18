package com.example.salon.repository;

import com.example.salon.entity.LockRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LockRecordRepository extends JpaRepository<LockRecord, Long> {

    List<LockRecord> findByDemandIdOrderByLockedAtDesc(Long demandId);

    List<LockRecord> findByVenueIdAndStatus(Long venueId, String status);

    /**
     * 场地改名后，把该场地所有锁定历史（含已冻结过押金的锁定行）上的场地名同步成现名。
     * 只改场地名一列，不动状态、金额、客户，历史上冻过的那一笔不允许作废重冻。
     */
    @Modifying
    @Query("UPDATE LockRecord l SET l.venueName = :newName WHERE l.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);

    /**
     * 行级写锁读取：值守签到/撤岗/开场先锁住本次锁定记录，把同一锁定下的
     * 值守操作串行化——两人前后脚签同一岗，只有一个能签上，另一个看到该岗已被占用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM LockRecord l WHERE l.id = :id")
    Optional<LockRecord> findByIdForUpdate(@Param("id") Long id);
}
