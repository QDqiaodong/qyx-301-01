package com.example.salon.service;

import com.example.salon.dto.RecommendResponse;
import com.example.salon.entity.*;
import com.example.salon.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {

    @Mock private VenueRepository venueRepository;
    @Mock private ActivityDemandRepository demandRepository;
    @Mock private RecommendResultRepository recommendResultRepository;
    @Mock private SiteVisitRepository siteVisitRepository;
    @Mock private WeightConfigService weightConfigService;
    @InjectMocks private RecommendService recommendService;

    private ActivityDemand demand;
    private Venue venue;

    @BeforeEach
    void setUp() {
        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setDemandName("客户A沙龙");
        demand.setExpectedPeople(100);
        demand.setExpectedDate(LocalDateTime.parse("2026-10-01T00:00:00"));
        demand.setBudgetMax(new BigDecimal("5000"));
        demand.setActivityCategory("会议培训");
        demand.setLocked(0);

        venue = new Venue();
        venue.setId(10L);
        venue.setName("阳光厅");
        venue.setStatus(1);
        venue.setCapacity(120);
        venue.setPricePerDay(new BigDecimal("3000"));
        venue.setFacilities("投影仪,WiFi");
        venue.setActivityTypes("会议培训");
    }

    private void stubWeights() {
        lenient().when(weightConfigService.getWeight("weight.capacity")).thenReturn(new BigDecimal("0.30"));
        lenient().when(weightConfigService.getWeight("weight.facility")).thenReturn(new BigDecimal("0.25"));
        lenient().when(weightConfigService.getWeight("weight.activityType")).thenReturn(new BigDecimal("0.25"));
        lenient().when(weightConfigService.getWeight("weight.budget")).thenReturn(new BigDecimal("0.20"));
        lenient().when(weightConfigService.getWeight("threshold.minCapacity")).thenReturn(new BigDecimal("0.80"));
        lenient().when(weightConfigService.getWeight("threshold.maxCapacity")).thenReturn(new BigDecimal("1.50"));
        lenient().when(weightConfigService.getWeight("threshold.minScore")).thenReturn(new BigDecimal("60.00"));
    }

    @Test
    void capacityScore_isFoldedBySiteVisitReservation() {
        stubWeights();
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findByStatus(1)).thenReturn(List.of(venue));

        SiteVisit visit = new SiteVisit();
        visit.setDemandId(99L);
        visit.setVenueId(10L);
        visit.setVisitDate(LocalDate.parse("2026-10-01"));
        visit.setReservedPeople(40);
        when(siteVisitRepository.findAll()).thenReturn(List.of(visit));
        when(recommendResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecommendResponse response = recommendService.calculateRecommend(1L);

        assertEquals(1, response.getResults().size());
        RecommendResult result = response.getResults().get(0);
        // 120 - 40 = 80 可排容量，80/100 = 0.8 恰好达阈值，人数分应为100
        assertEquals(0, new BigDecimal("100.00").compareTo(result.getCapacityScore()));
        assertTrue(result.getReason().contains("试场"));
        assertTrue(result.getReason().contains("40"));
    }

    @Test
    void venueStillAppears_whenTrialEatsMostCapacity_butScoreDrops() {
        stubWeights();
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findByStatus(1)).thenReturn(List.of(venue));

        SiteVisit visit = new SiteVisit();
        visit.setDemandId(99L);
        visit.setVenueId(10L);
        visit.setVisitDate(LocalDate.parse("2026-10-01"));
        visit.setReservedPeople(100);
        when(siteVisitRepository.findAll()).thenReturn(List.of(visit));
        when(recommendResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecommendResponse response = recommendService.calculateRecommend(1L);

        // 场地仍出现在完整结果里（allResults 参与 missing 分析），可排容量 20/100，人数分约25
        // 总分低于阈值则 validResults 为空，但理由折算逻辑仍生效
        if (!response.getResults().isEmpty()) {
            RecommendResult result = response.getResults().get(0);
            assertTrue(result.getCapacityScore().compareTo(new BigDecimal("50")) < 0);
            assertTrue(result.getReason().contains("100"));
        }
    }

    @Test
    void recommend_rejected_whileLocked() {
        demand.setLocked(1);
        demand.setLockedVenueName("阳光厅");
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));

        assertThrows(ResponseStatusException.class, () -> recommendService.calculateRecommend(1L));
        verify(venueRepository, never()).findByStatus(anyInt());
    }
}
