package com.example.salon.repository;

import com.example.salon.entity.OpeningRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OpeningRecordRepository extends JpaRepository<OpeningRecord, Long> {

    /** 本次锁定当前有效（OPEN）的开场条，最多一条 */
    Optional<OpeningRecord> findByLockRecordIdAndStatus(Long lockRecordId, String status);

    /** 场地改名后同步开场条上的场地名（只改场地名一列，不动开场状态）。 */
    @Modifying
    @Query("UPDATE OpeningRecord o SET o.venueName = :newName WHERE o.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);
}
