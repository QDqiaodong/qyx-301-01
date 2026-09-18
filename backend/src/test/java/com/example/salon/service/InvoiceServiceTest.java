package com.example.salon.service;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.CustomerAccount;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.entity.Invoice;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.CustomerAccountRepository;
import com.example.salon.repository.DepositTransactionRepository;
import com.example.salon.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 结算发票财务规矩：
 * - 非财务角色开票/作废一律 403；
 * - 冻结中没开场没退完不能开，错误点明还差哪笔冻结没结；
 * - 已开场按冻结流水冻结额开；全额实退完按实退额开；半退/全没收不能开；
 * - 已有有效票不能重复开，必须先作红字（填原因），旧票作废后新票才有效；
 * - 票面金额只从押金流水取，客户名必须与押金账户户主一致。
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private ActivityDemandRepository demandRepository;
    @Mock private DepositTransactionRepository transactionRepository;
    @Mock private CustomerAccountRepository accountRepository;
    @Mock private InvoiceNumberAllocator numberAllocator;
    @InjectMocks private InvoiceService invoiceService;

    /** 票号流水，每次开票递增，模拟独立事务里的计数器 */
    private long nextInvoiceSerial = 1;

    private ActivityDemand demand;
    private CustomerAccount account;

    @BeforeEach
    void setUp() {
        lenient().when(invoiceRepository.save(any(Invoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(invoiceRepository.saveAndFlush(any(Invoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(numberAllocator.nextValue()).thenAnswer(inv -> nextInvoiceSerial++);

        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setCustomerName("客户A");
        demand.setCustomerPhone("13800000000");
        demand.setDemandName("客户A沙龙");
        demand.setExpectedDate(LocalDateTime.parse("2026-10-10T09:00:00"));
        demand.setLocked(0);
        demand.setOpened(0);

        account = new CustomerAccount();
        account.setId(7L);
        account.setCustomerName("客户A");
        account.setCustomerPhone("13800000000");
        lenient().when(accountRepository.findByCustomerNameAndCustomerPhone("客户A", "13800000000"))
                .thenReturn(Optional.of(account));
        // 默认没有有效票
        lenient().when(invoiceRepository.findByDemandIdAndStatus(1L, Invoice.STATUS_VALID))
                .thenReturn(Optional.empty());
    }

    private DepositTransaction tx(long id, String type, String amount, String refund, String forfeit, String rate) {
        DepositTransaction t = new DepositTransaction();
        t.setId(id);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setRefundAmount(new BigDecimal(refund));
        t.setForfeitAmount(new BigDecimal(forfeit));
        t.setRefundRate(rate == null ? null : new BigDecimal(rate));
        t.setAccountId(7L);
        t.setCustomerName("客户A");
        t.setVenueId(10L);
        t.setVenueName("阳光厅");
        return t;
    }

    private DepositTransaction freeze(long id, String amount) {
        DepositTransaction t = tx(id, DepositService.TYPE_FREEZE, amount, "0", "0", null);
        return t;
    }

    // ==================== 角色 ====================

    @Test
    void issue_fails_for_nonFinanceRole() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "SALES", "销售小张"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("只有财务角色才能"));
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void issue_fails_whenRoleHeaderMissing() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, null, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("只有财务"));
    }

    @Test
    void void_fails_for_nonFinanceRole() {
        // 非财务角色在任何查票之前就被角色校验挡下（403）
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.voidInvoice(100L, "开错了", "SALES", "销售小张"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("只有财务角色才能"));
        verify(invoiceRepository, never()).save(any());
    }

    // ==================== 没结清不能开 ====================

    @Test
    void issue_fails_whenNeverFrozen() {
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("从未冻结过押金"));
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void issue_fails_whenFrozen_notOpened_notRefunded_andNamesMissingPayment() {
        // 锁场当天：冻结中、没开场、没退 → 销售想先开票报销，财务规矩必须挡住
        demand.setLocked(1);
        demand.setDepositAmount(new BigDecimal("3000"));
        demand.setDepositFreezeId(50L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        DepositTransaction freeze = freeze(50L, "3000");
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(freeze));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // 错误必须点明还差哪一笔没结：冻结金额与冻结流水号
        assertTrue(ex.getReason().contains("¥3000"), ex.getReason());
        assertTrue(ex.getReason().contains("#50"), ex.getReason());
        assertTrue(ex.getReason().contains("没有结清") || ex.getReason().contains("尚未开场"), ex.getReason());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void issue_fails_whenHalfRefunded() {
        // 三天内取消：只退一半，没收一半 → 押金没全额结清，不能开
        DepositTransaction freeze = freeze(50L, "3000");
        DepositTransaction refund = tx(51L, DepositService.TYPE_REFUND, "3000", "1500", "1500", "50");
        refund.setFreezeTransactionId(50L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(refund, freeze));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("¥1500"), ex.getReason());
        assertTrue(ex.getReason().contains("没收"), ex.getReason());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void issue_fails_whenFullyForfeited() {
        // 活动当天取消：不退 → 没有实退金额，不能开
        DepositTransaction freeze = freeze(50L, "3000");
        DepositTransaction refund = tx(51L, DepositService.TYPE_REFUND, "3000", "0", "3000", "0");
        refund.setFreezeTransactionId(50L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(refund, freeze));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("全额没收") || ex.getReason().contains("没有实退"), ex.getReason());
        verify(invoiceRepository, never()).save(any());
    }

    // ==================== 结清后可以开 ====================

    @Test
    void issue_succeeds_whenOpened_amountEqualsFrozenFromLedger() {
        // 已开场：票面金额必须等于冻结流水的冻结额
        demand.setLocked(1);
        demand.setOpened(1);
        demand.setOpenedAt(LocalDateTime.parse("2026-10-10T09:30:00"));
        demand.setLockedVenueId(10L);
        demand.setLockedVenueName("阳光厅");
        demand.setDepositAmount(new BigDecimal("9999")); // 故意写错卡片金额，验证以流水为准
        demand.setDepositFreezeId(50L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(freeze(50L, "3000")));

        Invoice result = invoiceService.issue(1L, "FINANCE", "财务小王");

        assertEquals(Invoice.STATUS_VALID, result.getStatus());
        assertEquals(0, new BigDecimal("3000").compareTo(result.getAmount()));
        assertEquals(Invoice.BASIS_FROZEN_OPENED, result.getBasisType());
        assertEquals(50L, result.getFreezeTransactionId());
        assertNull(result.getSettlementTransactionId());
        assertEquals("客户A", result.getCustomerName());
        assertEquals(7L, result.getAccountId());
        assertEquals(1, result.getActiveFlag());
        assertTrue(result.getInvoiceNo().startsWith("FP"));
        verify(invoiceRepository).saveAndFlush(any(Invoice.class));
    }

    @Test
    void issue_succeeds_whenFullyRefunded_amountEqualsActualRefund() {
        // 解除重配/提前取消：100% 实退 → 票面金额等于实退金额
        DepositTransaction freeze = freeze(50L, "3000");
        DepositTransaction refund = tx(52L, DepositService.TYPE_UNFREEZE, "3000", "3000", "0", "100");
        refund.setFreezeTransactionId(50L);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(refund, freeze));

        Invoice result = invoiceService.issue(1L, "FINANCE", "财务小王");

        assertEquals(Invoice.STATUS_VALID, result.getStatus());
        assertEquals(0, new BigDecimal("3000").compareTo(result.getAmount()));
        assertEquals(Invoice.BASIS_FULLY_REFUNDED, result.getBasisType());
        assertEquals(52L, result.getSettlementTransactionId());
        assertEquals(50L, result.getFreezeTransactionId());
    }

    // ==================== 重复开票 / 红字作废 ====================

    @Test
    void issue_fails_whenValidInvoiceAlreadyExists() {
        Invoice existing = validInvoice(100L, "FP20260918000100", "3000");
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(invoiceRepository.findByDemandIdAndStatus(1L, Invoice.STATUS_VALID))
                .thenReturn(Optional.of(existing));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("已有一张有效发票"));
        assertTrue(ex.getReason().contains("FP20260918000100"));
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void void_succeeds_forFinance_withReason_andOldNoNoLongerValid() {
        Invoice invoice = validInvoice(100L, "FP20260918000100", "3000");
        when(invoiceRepository.findById(100L)).thenReturn(Optional.of(invoice));

        Invoice red = invoiceService.voidInvoice(100L, "客户抬头开错，需要重开", "FINANCE", "财务小王");

        assertEquals(Invoice.STATUS_RED_VOID, red.getStatus());
        assertNull(red.getActiveFlag());
        assertEquals("客户抬头开错，需要重开", red.getVoidReason());
        assertEquals("财务小王", red.getVoidedBy());
        assertNotNull(red.getVoidedAt());
        // 票号保留留痕，但状态已不是有效票
        assertEquals("FP20260918000100", red.getInvoiceNo());
    }

    @Test
    void void_fails_withoutReason() {
        Invoice invoice = validInvoice(100L, "FP20260918000100", "3000");
        when(invoiceRepository.findById(100L)).thenReturn(Optional.of(invoice));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.voidInvoice(100L, "  ", "FINANCE", "财务小王"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("作废原因"));
        assertEquals(Invoice.STATUS_VALID, invoice.getStatus());
    }

    @Test
    void void_fails_whenAlreadyVoided() {
        Invoice invoice = validInvoice(100L, "FP20260918000100", "3000");
        invoice.setStatus(Invoice.STATUS_RED_VOID);
        invoice.setActiveFlag(null);
        when(invoiceRepository.findById(100L)).thenReturn(Optional.of(invoice));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.voidInvoice(100L, "再作废一次", "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("红字作废票"));
    }

    @Test
    void newIssue_afterVoid_isCurrentValidInvoiceWithDifferentNo() {
        // 旧票（id=90）已作废后再开：按全额实退开新票（id=100），新票才是当前有效票
        String oldVoidedNo = "FP20260917000090";
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        // 作废后查有效票为空
        when(invoiceRepository.findByDemandIdAndStatus(1L, Invoice.STATUS_VALID))
                .thenReturn(Optional.empty());
        DepositTransaction freeze = freeze(50L, "3000");
        DepositTransaction refund = tx(52L, DepositService.TYPE_UNFREEZE, "3000", "3000", "0", "100");
        refund.setFreezeTransactionId(50L);
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(refund, freeze));

        Invoice newInvoice = invoiceService.issue(1L, "FINANCE", "财务小王");
        assertEquals(Invoice.STATUS_VALID, newInvoice.getStatus());
        assertNotEquals(oldVoidedNo, newInvoice.getInvoiceNo());
        assertEquals(1, newInvoice.getActiveFlag());
        assertEquals(0, new BigDecimal("3000").compareTo(newInvoice.getAmount()));
    }

    // ==================== 客户身份 ====================

    @Test
    void issue_fails_whenCustomerMismatchWithDepositAccount() {
        // 押金账户户主是「客户A」，需求上却挂了另一个名字 → 不能开
        demand.setCustomerName("客户B");
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        when(accountRepository.findByCustomerNameAndCustomerPhone("客户B", "13800000000"))
                .thenReturn(Optional.of(account));
        DepositTransaction freeze = freeze(50L, "3000");
        DepositTransaction refund = tx(52L, DepositService.TYPE_UNFREEZE, "3000", "3000", "0", "100");
        refund.setFreezeTransactionId(50L);
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(refund, freeze));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issue(1L, "FINANCE", "财务小王"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("押金账户户主"));
        assertTrue(ex.getReason().contains("客户B"));
        verify(invoiceRepository, never()).save(any());
    }

    private Invoice validInvoice(long id, String no, String amount) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        invoice.setInvoiceNo(no);
        invoice.setDemandId(1L);
        invoice.setDemandName("客户A沙龙");
        invoice.setAccountId(7L);
        invoice.setCustomerName("客户A");
        invoice.setAmount(new BigDecimal(amount));
        invoice.setStatus(Invoice.STATUS_VALID);
        invoice.setActiveFlag(1);
        invoice.setIssuedAt(LocalDateTime.parse("2026-09-18T10:00:00"));
        invoice.setBasisType(Invoice.BASIS_FROZEN_OPENED);
        return invoice;
    }
}
