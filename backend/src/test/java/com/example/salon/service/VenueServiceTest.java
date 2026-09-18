package com.example.salon.service;

import com.example.salon.dto.VenueSaveResponse;
import com.example.salon.entity.Venue;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.DepositTransactionRepository;
import com.example.salon.repository.DutySigninRepository;
import com.example.salon.repository.InvoiceRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.OpeningRecordRepository;
import com.example.salon.repository.RecommendResultRepository;
import com.example.salon.repository.SiteVisitRepository;
import com.example.salon.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenueServiceTest {

    @Mock private VenueRepository venueRepository;
    @Mock private LockService lockService;
    @Mock private LockRecordRepository lockRecordRepository;
    @Mock private DepositTransactionRepository depositTransactionRepository;
    @Mock private RecommendResultRepository recommendResultRepository;
    @Mock private ActivityDemandRepository activityDemandRepository;
    @Mock private SiteVisitRepository siteVisitRepository;
    @Mock private DutySigninRepository dutySigninRepository;
    @Mock private OpeningRecordRepository openingRecordRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @InjectMocks private VenueService venueService;

    private Venue existing;

    @BeforeEach
    void setUp() {
        existing = new Venue();
        existing.setId(10L);
        existing.setName("阳光厅");
        existing.setCapacity(50);
        existing.setPricePerDay(new BigDecimal("3000"));
        existing.setFacilities("投影仪,WiFi");
        existing.setActivityTypes("会议培训");
        existing.setStatus(1);
        existing.setVersion(0L);
    }

    private Venue renameInput(String newName) {
        Venue input = new Venue();
        input.setName(newName);
        input.setCapacity(50);
        input.setPricePerDay(new BigDecimal("3000"));
        input.setFacilities("投影仪,WiFi");
        input.setActivityTypes("会议培训");
        input.setStatus(1);
        input.setVersion(0L);
        return input;
    }

    @Test
    void rename_propagatesCurrentName_toLockHistory_depositLedger_andRecommendCards() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(venueRepository.saveAndFlush(any(Venue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lockService.breakLocksForVenueChange(any(), any(), any(), any())).thenReturn(null);

        VenueSaveResponse response = venueService.updateVenue(10L, renameInput("星辰大厅"));

        assertEquals("星辰大厅", response.getVenue().getName());
        // 三处必须一起改成现名，漏一处都不算过
        verify(lockRecordRepository).updateVenueNameByVenueId(10L, "星辰大厅");
        verify(depositTransactionRepository).updateVenueNameByVenueId(10L, "星辰大厅");
        verify(recommendResultRepository).updateVenueNameByVenueId(10L, "星辰大厅");
        // 同事务带上仍锁定该场地的需求与其它引用快照
        verify(activityDemandRepository).updateLockedVenueNameByVenueId(10L, "星辰大厅");
    }

    @Test
    void rename_keepsFrozenDepositRows_intactOnlyNameColumnChanges() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(venueRepository.saveAndFlush(any(Venue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lockService.breakLocksForVenueChange(any(), any(), any(), any())).thenReturn(null);

        venueService.updateVenue(10L, renameInput("星辰大厅"));

        // 改名只触发场地名列更新：旧冻结行的金额/客户/比例不动，绝不允许作废重冻
        // （押金服务既不被调用，也没有第二条冻结流水产生）
        verify(depositTransactionRepository).updateVenueNameByVenueId(eq(10L), eq("星辰大厅"));
        verifyNoMoreInteractions(depositTransactionRepository);
        // 纯改名不触发任何锁定破裂/押金退回
        verify(lockService).breakLocksForVenueChange(any(), eq(1), eq(new BigDecimal("3000")), eq("投影仪,WiFi"));
    }

    @Test
    void unchangedName_doesNotPropagate() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(venueRepository.saveAndFlush(any(Venue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lockService.breakLocksForVenueChange(any(), any(), any(), any())).thenReturn(null);

        venueService.updateVenue(10L, renameInput("阳光厅"));

        verify(lockRecordRepository, never()).updateVenueNameByVenueId(anyLong(), anyString());
        verify(depositTransactionRepository, never()).updateVenueNameByVenueId(anyLong(), anyString());
        verify(recommendResultRepository, never()).updateVenueNameByVenueId(anyLong(), anyString());
    }

    @Test
    void concurrentRename_loserGetsConflict_andSeesLatestName() {
        Venue latest = new Venue();
        latest.setId(10L);
        latest.setName("银河宴会厅");
        latest.setStatus(1);
        latest.setVersion(1L);

        when(venueRepository.findById(10L)).thenReturn(Optional.of(existing), Optional.of(latest));
        when(venueRepository.saveAndFlush(any(Venue.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> venueService.updateVenue(10L, renameInput("星辰大厅")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertNotNull(ex.getReason());
        // 失败方看到名称已被更新成对方留下的最后一次对外名
        assertTrue(ex.getReason().contains("已被其他人更新"));
        assertTrue(ex.getReason().contains("银河宴会厅"));
        // 失败的改名不得落到任何引用处
        verify(lockRecordRepository, never()).updateVenueNameByVenueId(anyLong(), anyString());
        verify(depositTransactionRepository, never()).updateVenueNameByVenueId(anyLong(), anyString());
        verify(recommendResultRepository, never()).updateVenueNameByVenueId(anyLong(), anyString());
    }
}
