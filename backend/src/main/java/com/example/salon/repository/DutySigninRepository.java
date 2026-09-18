package com.example.salon.repository;

import com.example.salon.entity.DutySignin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DutySigninRepository extends JpaRepository<DutySignin, Long> {

    List<DutySignin> findByLockRecordId(Long lockRecordId);

    Optional<DutySignin> findByLockRecordIdAndPost(Long lockRecordId, String post);

    /** 场地改名后同步值守签到记录上的场地名（只改场地名一列，不动在岗状态）。 */
    @Modifying
    @Query("UPDATE DutySignin s SET s.venueName = :newName WHERE s.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);
}
