package com.example.salon.service;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SiteVisitServiceTest {

    @Mock private SiteVisitRepository siteVisitRepository;
    @Mock private ActivityDemandRepository demandRepository;
    @Mock private VenueRepository venueRepository;
    @Mock private LockRecordRepository lockRecordRepository;
    @InjectMocks private SiteVisitService siteVisitService;

    private ActivityDemand demand;
    private Venue venue;

    @BeforeEach
    void setUp() {
        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setDemandName("客户A沙龙");
        demand.setExpectedPeople(50);
        demand.setExpectedDate(LocalDateTime.parse("2026-10-01T00:00:00"));

        venue = new Venue();
        venue.setId(10L);
        venue.setName("阳光厅");
        venue.setStatus(1);
    }

    @Test
    void register_success_whenNoLockThatDay() {
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));
        when(lockRecordRepository.findByVenueIdAndStatus(10L, "LOCKED")).thenReturn(List.of());
        when(siteVisitRepository.findByDemandId(1L)).thenReturn(Optional.empty());
        when(siteVisitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SiteVisit request = new SiteVisit();
        request.setVenueId(10L);
        request.setVisitDate(LocalDate.parse("2026-09-20"));
        request.setTimeSlot("上午");

        SiteVisit saved = siteVisitService.register(1L, request);

        assertEquals("MORNING", saved.getTimeSlot());
        assertEquals(50, saved.getReservedPeople());
    }

    @Test
    void register_fails_andNamesBlockingDemand_whenVenueLockedThatDay() {
        ActivityDemand blockerDemand = new ActivityDemand();
        blockerDemand.setId(2L);
        blockerDemand.setLocked(1);
        blockerDemand.setExpectedDate(LocalDateTime.parse("2026-09-20T00:00:00"));

        LockRecord lock = new LockRecord();
        lock.setId(100L);
        lock.setDemandId(2L);
        lock.setVenueId(10L);
        lock.setDemandName("客户B发布会");
        lock.setStatus("LOCKED");

        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));
        when(lockRecordRepository.findByVenueIdAndStatus(10L, "LOCKED")).thenReturn(List.of(lock));
        when(demandRepository.findById(2L)).thenReturn(Optional.of(blockerDemand));

        SiteVisit request = new SiteVisit();
        request.setVenueId(10L);
        request.setVisitDate(LocalDate.parse("2026-09-20"));
        request.setTimeSlot("AFTERNOON");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> siteVisitService.register(1L, request));
        assertTrue(ex.getReason().contains("客户B发布会"));
        assertTrue(ex.getReason().contains("2"));
        verify(siteVisitRepository, never()).save(any());
    }

    @Test
    void register_fails_whenSlotMissing() {
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));

        SiteVisit request = new SiteVisit();
        request.setVenueId(10L);
        request.setVisitDate(LocalDate.parse("2026-09-20"));
        request.setTimeSlot("晚上");

        assertThrows(ResponseStatusException.class, () -> siteVisitService.register(1L, request));
    }
}
