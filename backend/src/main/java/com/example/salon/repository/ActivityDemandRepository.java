package com.example.salon.repository;

import com.example.salon.entity.ActivityDemand;
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
public interface ActivityDemandRepository extends JpaRepository<ActivityDemand, Long> {

    List<ActivityDemand> findByMatchStatus(Integer matchStatus);

    List<ActivityDemand> findByCustomerNameContaining(String customerName);

    List<ActivityDemand> findAllByOrderByCreatedAtDesc();

    List<ActivityDemand> findByLocked(Integer locked);

    List<ActivityDemand> findByLockedVenueIdAndLocked(Long venueId, Integer locked);

    /**
     * 场地改名后，同步仍锁着该场地的需求上的锁定场地名（历史页锁定标签/当天页都读它），只改场地名。
     */
    @Modifying
    @Query("UPDATE ActivityDemand d SET d.lockedVenueName = :newName "
            + "WHERE d.lockedVenueId = :venueId AND d.locked = 1")
    int updateLockedVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);

    /**
     * 行级写锁读取：财务开票先锁住需求行，把同一需求的开票串行化——
     * 两人前后脚给同一需求开票，只有一个能开出有效票。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ActivityDemand d WHERE d.id = :id")
    Optional<ActivityDemand> findByIdForUpdate(@Param("id") Long id);
}
