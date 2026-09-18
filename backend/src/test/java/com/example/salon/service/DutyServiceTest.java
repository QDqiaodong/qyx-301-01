package com.example.salon.service;

import com.example.salon.dto.DayDutyView;
import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.DutySignin;
import com.example.salon.entity.LockRecord;
import com.example.salon.entity.OpeningRecord;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.DutySigninRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.OpeningRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
class DutyServiceTest {

    @Mock private ActivityDemandRepository demandRepository;
    @Mock private LockRecordRepository lockRecordRepository;
    @Mock private DutySigninRepository dutySigninRepository;
    @Mock private OpeningRecordRepository openingRecordRepository;
    @InjectMocks private DutyService dutyService;

    private ActivityDemand demand;
    private LockRecord lockRecord;

    @BeforeEach
    void setUp() {
        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setCustomerName("客户A");
        demand.setDemandName("客户A沙龙");
        demand.setExpectedDate(LocalDateTime.parse("2026-10-10T09:00:00"));
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setLockedVenueName("阳光厅");
        demand.setMatchStatus(5);
        demand.setOpened(0);
        // 冻结中的押金：撤岗、开场都不许动它
        demand.setDepositAmount(new BigDecimal("3000"));
        demand.setDepositFreezeId(99L);

        lockRecord = new LockRecord();
        lockRecord.setId(55L);
        lockRecord.setDemandId(1L);
        lockRecord.setVenueId(10L);
        lockRecord.setVenueName("阳光厅");
        lockRecord.setStatus("LOCKED");
    }

    private void mockLockedDemand() {
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(lockRecordRepository.findByDemandIdOrderByLockedAtDesc(1L)).thenReturn(List.of(lockRecord));
        when(lockRecordRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(lockRecord));
    }

    private DutySignin signedPost(String post, String staffName) {
        DutySignin signin = new DutySignin();
        signin.setId(post.equals(DutySignin.POST_PRIMARY) ? 11L : 12L);
        signin.setDemandId(1L);
        signin.setLockRecordId(55L);
        signin.setVenueId(10L);
        signin.setVenueName("阳光厅");
        signin.setActivityDate(LocalDate.parse("2026-10-10"));
        signin.setPost(post);
        signin.setStaffName(staffName);
        signin.setStatus(DutySignin.STATUS_SIGNED);
        signin.setSignedAt(LocalDateTime.now());
        return signin;
    }

    // ==================== 签到 ====================

    @Test
    void signIn_primaryAndFlex_succeed() {
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_PRIMARY))
                .thenReturn(Optional.empty());
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_FLEX))
                .thenReturn(Optional.empty());
        when(dutySigninRepository.save(any(DutySignin.class))).thenAnswer(inv -> inv.getArgument(0));

        DutySignin primary = dutyService.signIn(1L, "PRIMARY", "张三");
        DutySignin flex = dutyService.signIn(1L, "FLEX", "李四");

        assertEquals(DutySignin.POST_PRIMARY, primary.getPost());
        assertEquals(DutySignin.POST_FLEX, flex.getPost());
        assertEquals(DutySignin.STATUS_SIGNED, primary.getStatus());
        assertEquals(DutySignin.STATUS_SIGNED, flex.getStatus());
        assertEquals(55L, primary.getLockRecordId());
        assertEquals(LocalDate.parse("2026-10-10"), primary.getActivityDate());
    }

    @Test
    void signIn_rejected_whenDemandNotLocked() {
        demand.setLocked(0);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.signIn(1L, "PRIMARY", "张三"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(dutySigninRepository, never()).save(any());
    }

    @Test
    void signIn_conflict_whenPostAlreadyOccupied() {
        // 机动岗已被两个人前后脚去签：先签的占岗，后签的看到该岗已被占用
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_FLEX))
                .thenReturn(Optional.of(signedPost(DutySignin.POST_FLEX, "李四")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.signIn(1L, "FLEX", "王五"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("已被"));
        assertTrue(ex.getReason().contains("占用"));
        assertTrue(ex.getReason().contains("李四"));
        verify(dutySigninRepository, never()).save(any());
    }

    @Test
    void signIn_conflict_whenConcurrentInsertHitsUniqueConstraint() {
        // 两人同时签同一空岗：数据库唯一约束只放行一个，失败方看到该岗已被占用
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_FLEX))
                .thenReturn(Optional.empty());
        when(dutySigninRepository.save(any(DutySignin.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for uk_duty_lock_post"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.signIn(1L, "FLEX", "王五"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("已被占用"));
    }

    @Test
    void signIn_allowedAgain_afterWithdraw() {
        mockLockedDemand();
        DutySignin withdrawn = signedPost(DutySignin.POST_FLEX, "李四");
        withdrawn.setStatus(DutySignin.STATUS_WITHDRAWN);
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_FLEX))
                .thenReturn(Optional.of(withdrawn));
        when(dutySigninRepository.save(any(DutySignin.class))).thenAnswer(inv -> inv.getArgument(0));

        DutySignin resigned = dutyService.signIn(1L, "FLEX", "王五");

        assertEquals(DutySignin.STATUS_SIGNED, resigned.getStatus());
        assertEquals("王五", resigned.getStaffName());
        assertNull(resigned.getWithdrawnAt());
    }

    // ==================== 开场 ====================

    @Test
    void openVenue_rejected_whenOnlyPrimarySigned() {
        // 只签主值守、机动空着：场地必须停在已锁定，不能开场
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordId(55L))
                .thenReturn(List.of(signedPost(DutySignin.POST_PRIMARY, "张三")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.openVenue(1L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("机动"));
        assertTrue(ex.getReason().contains("不能开场"));

        assertEquals(0, demand.getOpened());
        verify(openingRecordRepository, never()).save(any());
        verify(demandRepository, never()).save(any());
    }

    @Test
    void openVenue_rejected_whenNeitherSigned() {
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordId(55L)).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.openVenue(1L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(0, demand.getOpened());
        verify(openingRecordRepository, never()).save(any());
    }

    @Test
    void openVenue_succeeds_whenBothPostsSigned() {
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordId(55L)).thenReturn(List.of(
                signedPost(DutySignin.POST_PRIMARY, "张三"),
                signedPost(DutySignin.POST_FLEX, "李四")));
        when(openingRecordRepository.save(any(OpeningRecord.class))).thenAnswer(inv -> {
            OpeningRecord record = inv.getArgument(0);
            record.setId(77L);
            return record;
        });

        OpeningRecord opening = dutyService.openVenue(1L);

        assertEquals(OpeningRecord.STATUS_OPEN, opening.getStatus());
        assertEquals(1, opening.getActiveFlag());
        assertEquals(LocalDate.parse("2026-10-10"), opening.getActivityDate());
        // 场地从已锁定变成已开场
        assertEquals(1, demand.getOpened());
        assertNotNull(demand.getOpenedAt());
        // 开场不动押金冻结
        assertEquals(0, new BigDecimal("3000").compareTo(demand.getDepositAmount()));
        assertEquals(99L, demand.getDepositFreezeId());
    }

    @Test
    void openVenue_rejected_whenAlreadyOpened() {
        demand.setOpened(1);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.openVenue(1L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(openingRecordRepository, never()).save(any());
    }

    // ==================== 撤岗 ====================

    @Test
    void withdraw_primary_revertsOpenedToLocked_andKeepsDepositFrozen() {
        // 主值守中途撤岗：已开场的退回已锁定、当天开场条作废，但押金冻结不能冲掉
        demand.setOpened(1);
        demand.setOpenedAt(LocalDateTime.parse("2026-10-10T10:00:00"));
        mockLockedDemand();
        DutySignin primary = signedPost(DutySignin.POST_PRIMARY, "张三");
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_PRIMARY))
                .thenReturn(Optional.of(primary));
        when(dutySigninRepository.save(any(DutySignin.class))).thenAnswer(inv -> inv.getArgument(0));
        OpeningRecord opening = new OpeningRecord();
        opening.setId(77L);
        opening.setLockRecordId(55L);
        opening.setStatus(OpeningRecord.STATUS_OPEN);
        opening.setActiveFlag(1);
        when(openingRecordRepository.findByLockRecordIdAndStatus(55L, OpeningRecord.STATUS_OPEN))
                .thenReturn(Optional.of(opening));

        dutyService.withdraw(1L, "PRIMARY");

        // 已开场退回已锁定
        assertEquals(0, demand.getOpened());
        assertNull(demand.getOpenedAt());
        // 当天开场条作废
        assertEquals(OpeningRecord.STATUS_VOID, opening.getStatus());
        assertNull(opening.getActiveFlag());
        assertNotNull(opening.getVoidedAt());
        assertTrue(opening.getVoidReason().contains("撤岗"));
        // 主值守岗空出
        assertEquals(DutySignin.STATUS_WITHDRAWN, primary.getStatus());
        // 押金冻结不随撤岗冲掉：冻结金额、冻结流水号都不动
        assertEquals(0, new BigDecimal("3000").compareTo(demand.getDepositAmount()));
        assertEquals(99L, demand.getDepositFreezeId());
    }

    @Test
    void withdraw_flex_revertsOpenedToLocked() {
        // 机动岗空着同样不能维持开场：撤岗后两岗不齐，已开场退回已锁定
        demand.setOpened(1);
        mockLockedDemand();
        DutySignin flex = signedPost(DutySignin.POST_FLEX, "李四");
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_FLEX))
                .thenReturn(Optional.of(flex));
        when(dutySigninRepository.save(any(DutySignin.class))).thenAnswer(inv -> inv.getArgument(0));
        OpeningRecord opening = new OpeningRecord();
        opening.setStatus(OpeningRecord.STATUS_OPEN);
        opening.setActiveFlag(1);
        when(openingRecordRepository.findByLockRecordIdAndStatus(55L, OpeningRecord.STATUS_OPEN))
                .thenReturn(Optional.of(opening));

        dutyService.withdraw(1L, "FLEX");

        assertEquals(0, demand.getOpened());
        assertEquals(OpeningRecord.STATUS_VOID, opening.getStatus());
        assertEquals(0, new BigDecimal("3000").compareTo(demand.getDepositAmount()));
    }

    @Test
    void withdraw_whenNotOpened_onlyFreesPost() {
        mockLockedDemand();
        DutySignin primary = signedPost(DutySignin.POST_PRIMARY, "张三");
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_PRIMARY))
                .thenReturn(Optional.of(primary));
        when(dutySigninRepository.save(any(DutySignin.class))).thenAnswer(inv -> inv.getArgument(0));

        dutyService.withdraw(1L, "PRIMARY");

        assertEquals(DutySignin.STATUS_WITHDRAWN, primary.getStatus());
        assertEquals(0, demand.getOpened());
        verify(openingRecordRepository, never()).save(any());
        verify(demandRepository, never()).save(any());
    }

    @Test
    void withdraw_rejected_whenPostEmpty() {
        mockLockedDemand();
        when(dutySigninRepository.findByLockRecordIdAndPost(55L, DutySignin.POST_PRIMARY))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> dutyService.withdraw(1L, "PRIMARY"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ==================== 当天页 ====================

    @Test
    void dayView_reflectsPersistedPostsAndOpenState() {
        // 再进当天页：两岗和能不能开场必须还是同一份持久化数据
        demand.setOpened(1);
        demand.setOpenedAt(LocalDateTime.parse("2026-10-10T10:00:00"));
        when(demandRepository.findByLocked(1)).thenReturn(List.of(demand));
        when(lockRecordRepository.findByDemandIdOrderByLockedAtDesc(1L)).thenReturn(List.of(lockRecord));
        when(dutySigninRepository.findByLockRecordId(55L)).thenReturn(List.of(
                signedPost(DutySignin.POST_PRIMARY, "张三"),
                signedPost(DutySignin.POST_FLEX, "李四")));
        OpeningRecord opening = new OpeningRecord();
        opening.setId(77L);
        when(openingRecordRepository.findByLockRecordIdAndStatus(55L, OpeningRecord.STATUS_OPEN))
                .thenReturn(Optional.of(opening));

        List<DayDutyView> views = dutyService.getDayView(LocalDate.parse("2026-10-10"));

        assertEquals(1, views.size());
        DayDutyView view = views.get(0);
        assertEquals("阳光厅", view.venueName());
        assertEquals("张三", view.primaryPost().staffName());
        assertEquals("李四", view.flexPost().staffName());
        assertTrue(view.bothSigned());
        assertTrue(view.opened());
        assertFalse(view.canOpen());
        assertEquals(77L, view.openingId());
        // 其他日期看不到这场
        assertTrue(dutyService.getDayView(LocalDate.parse("2026-10-11")).isEmpty());
    }

    @Test
    void dayView_canOpenOnlyWhenBothSignedAndNotOpened() {
        when(demandRepository.findByLocked(1)).thenReturn(List.of(demand));
        when(lockRecordRepository.findByDemandIdOrderByLockedAtDesc(1L)).thenReturn(List.of(lockRecord));
        // 只签了主值守：不可开场
        when(dutySigninRepository.findByLockRecordId(55L))
                .thenReturn(List.of(signedPost(DutySignin.POST_PRIMARY, "张三")));

        DayDutyView view = dutyService.getDayView(LocalDate.parse("2026-10-10")).get(0);

        assertNotNull(view.primaryPost());
        assertNull(view.flexPost());
        assertFalse(view.bothSigned());
        assertFalse(view.canOpen());
        assertFalse(view.opened());
    }
}
