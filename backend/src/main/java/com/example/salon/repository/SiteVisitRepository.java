package com.example.salon.repository;

import com.example.salon.entity.SiteVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SiteVisitRepository extends JpaRepository<SiteVisit, Long> {

    Optional<SiteVisit> findByDemandId(Long demandId);

    List<SiteVisit> findByVenueIdAndVisitDate(Long venueId, LocalDate visitDate);

    /** 场地改名后同步踩点试场登记上的场地名（只改场地名一列）。 */
    @Modifying
    @Query("UPDATE SiteVisit v SET v.venueName = :newName WHERE v.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);
}
