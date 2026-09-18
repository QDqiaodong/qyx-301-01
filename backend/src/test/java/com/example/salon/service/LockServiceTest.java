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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockServiceTest {

    @Mock private ActivityDemandRepository demandRepository;
    @Mock private VenueRepository venueRepository;
    @Mock private LockRecordRepository lockRecordRepository;
    @Mock private RecommendResultRepository recommendResultRepository;
    @Mock private DepositService depositService;
    @InjectMocks private LockService lockService;

    private ActivityDemand demand;
    private Venue venue;

    @BeforeEach
    void setUp() {
        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setCustomerName("客户A");
        demand.setDemandName("客户A沙龙");
        demand.setExpectedPeople(50);
        demand.setExpectedDate(LocalDateTime.parse("2026-10-01T00:00:00"));
        demand.setBudgetMax(new BigDecimal("5000"));
        demand.setRequiredFacilities("投影仪,WiFi");
        demand.setLocked(0);

        venue = new Venue();
        venue.setId(10L);
        venue.setName("阳光厅");
        venue.setStatus(1);
        venue.setPricePerDay(new BigDecimal("3000"));
        venue.setFacilities("投影仪,WiFi,音响");
    }

    private DepositTransaction freezeTx(long id) {
        DepositTransaction tx = new DepositTransaction();
        tx.setId(id);
        tx.setAmount(new BigDecimal("3000"));
        return tx;
    }

    private DepositTransaction refundTx(String rate, String amount, String refund, String forfeit) {
        DepositTransaction tx = new DepositTransaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setRefundRate(new BigDecimal(rate));
        tx.setRefundAmount(new BigDecimal(refund));
        tx.setForfeitAmount(new BigDecimal(forfeit));
        tx.setReason("结算");
        return tx;
    }

    @Test
    void confirmLock_succeeds_whenVenueInRecommendList() {
        RecommendResult recommend = new RecommendResult();
        recommend.setVenueId(10L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));
        when(recommendResultRepository.findByDemandIdOrderByRecommendOrder(1L)).thenReturn(List.of(recommend));
        when(demandRepository.findByLockedVenueIdAndLocked(10L, 1)).thenReturn(List.of());
        when(depositService.freezeDeposit(any(), any())).thenReturn(freezeTx(99L));

        LockRecord record = lockService.confirmLock(1L, 10L);

        assertEquals("LOCKED", record.getStatus());
        assertEquals(1, demand.getLocked());
        assertEquals(5, demand.getMatchStatus());
        // 押金冻结成功后才占场，冻结金额与流水ID落到需求上
        assertEquals(0, new BigDecimal("3000").compareTo(demand.getDepositAmount()));
        assertEquals(99L, demand.getDepositFreezeId());
    }

    @Test
    void confirmLock_freezeFailure_doesNotOccupyVenueOrEnterTodo() {
        RecommendResult recommend = new RecommendResult();
        recommend.setVenueId(10L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));
        when(recommendResultRepository.findByDemandIdOrderByRecommendOrder(1L)).thenReturn(List.of(recommend));
        when(demandRepository.findByLockedVenueIdAndLocked(10L, 1)).thenReturn(List.of());
        // 押金冻结失败（余额不足）
        when(depositService.freezeDeposit(any(), any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "押金冻结失败：余额不足"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lockService.confirmLock(1L, 10L));
        assertTrue(ex.getReason().contains("押金冻结失败"));

        // 冻结失败：需求不进待办活动、场地未被占用
        assertEquals(0, demand.getLocked());
        assertNotEquals(5, demand.getMatchStatus());
        assertNull(demand.getLockedVenueId());
        assertNull(demand.getDepositAmount());
        verify(lockRecordRepository, never()).save(any());
        verify(demandRepository, never()).save(any());
    }

    @Test
    void confirmLock_rejected_whenExpectedDateMissing() {
        demand.setExpectedDate(null);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lockService.confirmLock(1L, 10L));
        assertTrue(ex.getReason().contains("活动日期"));
        verify(depositService, never()).freezeDeposit(any(), any());
    }

    @Test
    void confirmLock_rejected_whenAlreadyLocked() {
        demand.setLocked(1);
        demand.setLockedVenueName("阳光厅");
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lockService.confirmLock(1L, 10L));
        assertTrue(ex.getReason().contains("已锁定"));
    }

    @Test
    void cancelActivity_settlesTieredRefund_andReleasesVenue() {
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setLockedVenueName("阳光厅");
        demand.setDepositAmount(new BigDecimal("3000"));
        demand.setDepositFreezeId(99L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(depositService.cancelWithTieredRefund(any(), any()))
                .thenReturn(refundTx("50", "3000", "1500", "1500"));
        LockRecord active = new LockRecord();
        active.setStatus("LOCKED");
        when(lockRecordRepository.findByDemandIdOrderByLockedAtDesc(1L)).thenReturn(List.of(active));

        DepositTransaction tx = lockService.cancelActivity(1L);

        assertEquals(0, new BigDecimal("1500").compareTo(tx.getRefundAmount()));
        // 取消后释放场地、需求进入已取消终态
        assertEquals(0, demand.getLocked());
        assertEquals(6, demand.getMatchStatus());
        assertNull(demand.getLockedVenueId());
        assertNull(demand.getDepositAmount());
        assertEquals("CANCELED", active.getStatus());
        verify(recommendResultRepository).deleteByDemandId(1L);
    }

    @Test
    void releaseLock_refundsFully_andVoidsRecommend() {
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setLockedVenueName("阳光厅");
        demand.setDepositAmount(new BigDecimal("3000"));
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(depositService.releaseFullRefund(any(), any()))
                .thenReturn(refundTx("100", "3000", "3000", "0"));
        LockRecord active = new LockRecord();
        active.setStatus("LOCKED");
        when(lockRecordRepository.findByDemandIdOrderByLockedAtDesc(1L)).thenReturn(List.of(active));

        lockService.releaseLock(1L);

        assertEquals(0, demand.getLocked());
        assertEquals(4, demand.getMatchStatus());
        verify(recommendResultRepository).deleteByDemandId(1L);
    }

    @Test
    void priceRaiseOverBudget_breaksLock_andVoidsRecommend() {
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setLockedVenueName("阳光厅");
        demand.setDepositAmount(new BigDecimal("3000"));
        when(demandRepository.findByLockedVenueIdAndLocked(10L, 1)).thenReturn(List.of(demand));
        when(depositService.releaseFullRefund(any(), any()))
                .thenReturn(refundTx("100", "3000", "3000", "0"));

        String summary = lockService.breakLocksForVenueChange(
                venue, 1, new BigDecimal("6000"), "投影仪,WiFi,音响");

        assertNotNull(summary);
        assertTrue(summary.contains("超过需求预算上限"));
        assertEquals(0, demand.getLocked());
        assertEquals(4, demand.getMatchStatus());
        assertNotNull(demand.getLockBreakReason());
        verify(recommendResultRepository).deleteByDemandId(1L);
        // 场地原因破裂，押金全额退回
        verify(depositService).releaseFullRefund(any(), any());
    }

    @Test
    void facilityRemoval_breaksLock() {
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setDepositAmount(new BigDecimal("3000"));
        when(demandRepository.findByLockedVenueIdAndLocked(10L, 1)).thenReturn(List.of(demand));
        when(depositService.releaseFullRefund(any(), any()))
                .thenReturn(refundTx("100", "3000", "3000", "0"));

        String summary = lockService.breakLocksForVenueChange(
                venue, 1, new BigDecimal("3000"), "音响");

        assertNotNull(summary);
        assertTrue(summary.contains("投影仪"));
        assertTrue(summary.contains("WiFi"));
        assertEquals(4, demand.getMatchStatus());
    }

    @Test
    void disableVenue_breaksLock() {
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setDepositAmount(new BigDecimal("3000"));
        when(demandRepository.findByLockedVenueIdAndLocked(10L, 1)).thenReturn(List.of(demand));
        when(depositService.releaseFullRefund(any(), any()))
                .thenReturn(refundTx("100", "3000", "3000", "0"));

        String summary = lockService.breakLocksForVenueChange(
                venue, 0, new BigDecimal("3000"), "投影仪,WiFi,音响");

        assertNotNull(summary);
        assertTrue(summary.contains("停用"));
        assertEquals(4, demand.getMatchStatus());
    }

    @Test
    void harmlessChange_doesNotBreakLock() {
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        when(demandRepository.findByLockedVenueIdAndLocked(10L, 1)).thenReturn(List.of(demand));

        String summary = lockService.breakLocksForVenueChange(
                venue, 1, new BigDecimal("4500"), "投影仪,WiFi,音响,空调");

        assertNull(summary);
        assertEquals(1, demand.getLocked());
        verify(recommendResultRepository, never()).deleteByDemandId(any());
        verify(depositService, never()).releaseFullRefund(any(), any());
    }
}
