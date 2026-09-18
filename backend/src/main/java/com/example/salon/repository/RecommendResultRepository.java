package com.example.salon.repository;

import com.example.salon.entity.RecommendResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendResultRepository extends JpaRepository<RecommendResult, Long> {

    List<RecommendResult> findByDemandIdOrderByRecommendOrder(Long demandId);

    void deleteByDemandId(Long demandId);

    /**
     * 场地改名后，把该场地所有推荐结果卡片上的场地名同步成现名（只改场地名一列，不动评分）。
     */
    @Modifying
    @Query("UPDATE RecommendResult r SET r.venueName = :newName WHERE r.venueId = :venueId")
    int updateVenueNameByVenueId(@Param("venueId") Long venueId, @Param("newName") String newName);
}
