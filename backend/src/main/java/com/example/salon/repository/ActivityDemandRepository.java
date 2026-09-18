package com.example.salon.repository;

import com.example.salon.entity.ActivityDemand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
