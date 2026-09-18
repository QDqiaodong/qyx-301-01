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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private ActivityDemandRepository demandRepository;
    @Mock private DepositTransactionRepository transactionRepository;
    @Mock private CustomerAccountRepository accountRepository;
    @InjectMocks private InvoiceService invoiceService;

    private ActivityDemand demand;
    private CustomerAccount account;
    private DepositTransaction freezeTx;

    @BeforeEach
    void setUp() {
        lenient().when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(demandRepository.save(any(ActivityDemand.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(invoiceRepository.findByDemandIdAndStatus(1L, Invoice.STATUS_VALID))
                .thenReturn(Optional.empty());
        lenient().when(invoiceRepository.findTopByInvoiceNoStartingWithOrderByInvoiceNoDesc(anyString()))
                .thenReturn(Optional.empty());

        // 已锁定、已冻结 ¥3000、未开场的需求
        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setCustomerName("客户A");
        demand.setCustomerPhone("13800000000");
        demand.setDemandName("客户A沙龙");
        demand.setLocked(1);
        demand.setLockedVenueId(10L);
        demand.setLockedVenueName("阳光厅");
        demand.setOpened(0);
        demand.setDepositAmount(new BigDecimal("3000"));
        demand.setDepositFreezeId(99L);

        account = new CustomerAccount();
        account.setId(7L);
        account.setCustomerName("客户A");
        account.setCustomerPhone("13800000000");

        freezeTx = new DepositTransaction();
        freezeTx.setId(99L);
        freezeTx.setAccountId(7L);
        freezeTx.setCustomerName("客户A");
        freezeTx.setDemandId(1L);
        freezeTx.setType(DepositService.TYPE_FREEZE);
        freezeTx.setAmount(new BigDecimal("3000"));
        freezeTx.setRefundAmount(BigDecimal.ZERO);
        freezeTx.setForfeitAmount(BigDecimal.ZERO);
    }

    private void mockDemandAndAccount() {
        // 宽松打桩：未结清/已有有效票等失败路径在到账前就已抛出，不会触达全部桩
        lenient().when(demandRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(demand));
        lenient().when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
    }

    private DepositTransaction settleTx(String type, String amount, String refund) {
        DepositTransaction tx = new DepositTransaction();
        tx.setId(100L);
        tx.setAccountId(7L);
        tx.setCustomerName("客户A");
        tx.setDemandId(1L);
        tx.setType(type);
        tx.setAmount(new BigDecimal(amount));
        tx.setRefundAmount(new BigDecimal(refund));
        tx.setForfeitAmount(new BigDecimal(amount).subtract(new BigDecimal(refund)));
        tx.setFreezeTransactionId(99L);
        return tx;
    }

    // ==================== 角色：只有财务能开 ====================

    @Test
    void issue_salesRole_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issueInvoice(1L, "SALES", "销售小王", null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("只有财务角色能开"));
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void issue_noRole_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issueInvoice(1L, null, null, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("只有财务角色能开"));
    }

    @Test
    void void_salesRole_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.voidInvoice(50L, "SALES", "开错了"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("只有财务角色能作废"));
    }

    // ==================== 未结清不能开 ====================

    @Test
    void issue_frozenNotOpenedNotRefunded_fails_andNamesUnsettledFreeze() {
        mockDemandAndAccount();
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(freezeTx));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issueInvoice(1L, "FINANCE", "财务小李", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // 写明还差哪一笔没结：冻结流水#99，未开场也未退完
        assertTrue(ex.getReason().contains("#99"));
        assertTrue(ex.getReason().contains("未开场"));
        assertTrue(ex.getReason().contains("未退完"));
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void issue_partialRefund_fails_notFullyRefunded() {
        mockDemandAndAccount();
        // 已取消但只退了一半：冻结结清但没收 ¥1500，不算全额退完
        demand.setLocked(0);
        demand.setDepositAmount(null);
        demand.setDepositFreezeId(null);
        DepositTransaction half = settleTx(DepositService.TYPE_REFUND, "3000", "1500");
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(half, freezeTx));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issueInvoice(1L, "FINANCE", "财务小李", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("未全额退完"));
        assertTrue(ex.getReason().contains("1500"));
        verify(invoiceRepository, never()).save(any());
    }

    // ==================== 结清后能开：票面金额 = 已结清金额 ====================

    @Test
    void issue_opened_succeeds_amountEqualsFrozen() {
        mockDemandAndAccount();
        demand.setOpened(1);
        demand.setOpenedAt(LocalDateTime.now());
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(freezeTx));

        Invoice invoice = invoiceService.issueInvoice(1L, "FINANCE", "财务小李", null);

        assertEquals(Invoice.STATUS_VALID, invoice.getStatus());
        assertEquals(Invoice.BASIS_OPENED, invoice.getSettleBasis());
        // 票面金额 = 已结清的冻结金额
        assertEquals(0, new BigDecimal("3000").compareTo(invoice.getAmount()));
        // 票面客户 = 押金账户持有人
        assertEquals("客户A", invoice.getCustomerName());
        assertEquals(7L, invoice.getAccountId());
        assertEquals(99L, invoice.getFreezeTransactionId());
        assertNotNull(invoice.getInvoiceNo());
        // 需求详情同步挂上同一张有效票
        assertEquals(invoice.getInvoiceNo(), demand.getInvoiceNo());
        assertEquals(0, invoice.getAmount().compareTo(demand.getInvoiceAmount()));
    }

    @Test
    void issue_fullyRefunded_succeeds_amountEqualsRefund() {
        mockDemandAndAccount();
        // 解除锁定全额退押：需求已不在冻结中
        demand.setLocked(0);
        demand.setDepositAmount(null);
        demand.setDepositFreezeId(null);
        DepositTransaction full = settleTx(DepositService.TYPE_UNFREEZE, "3000", "3000");
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(full, freezeTx));

        Invoice invoice = invoiceService.issueInvoice(1L, "FINANCE", "财务小李", null);

        assertEquals(Invoice.BASIS_REFUNDED, invoice.getSettleBasis());
        // 票面金额 = 实退金额
        assertEquals(0, new BigDecimal("3000").compareTo(invoice.getAmount()));
        assertEquals(100L, invoice.getSettleTransactionId());
        assertEquals(invoice.getInvoiceNo(), demand.getInvoiceNo());
    }

    @Test
    void issue_customerNameMismatch_fails() {
        mockDemandAndAccount();
        demand.setOpened(1);
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(freezeTx));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issueInvoice(1L, "FINANCE", "财务小李", "别人公司"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("同一人"));
        assertTrue(ex.getReason().contains("客户A"));
        verify(invoiceRepository, never()).save(any());
    }

    // ==================== 一需求一有效票；红字作废后才能重开 ====================

    @Test
    void issue_existingValidInvoice_fails() {
        mockDemandAndAccount();
        demand.setOpened(1);
        Invoice existing = new Invoice();
        existing.setId(50L);
        existing.setInvoiceNo("INV20260918-0001");
        existing.setDemandId(1L);
        existing.setAmount(new BigDecimal("3000"));
        existing.setStatus(Invoice.STATUS_VALID);
        when(invoiceRepository.findByDemandIdAndStatus(1L, Invoice.STATUS_VALID))
                .thenReturn(Optional.of(existing));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.issueInvoice(1L, "FINANCE", "财务小李", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("INV20260918-0001"));
        assertTrue(ex.getReason().contains("作废"));
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void void_validInvoice_marksRedLetter_andClearsDemand() {
        Invoice invoice = new Invoice();
        invoice.setId(50L);
        invoice.setInvoiceNo("INV20260918-0001");
        invoice.setDemandId(1L);
        invoice.setAmount(new BigDecimal("3000"));
        invoice.setStatus(Invoice.STATUS_VALID);
        when(invoiceRepository.findById(50L)).thenReturn(Optional.of(invoice));
        demand.setOpened(1);
        demand.setInvoiceNo("INV20260918-0001");
        demand.setInvoiceAmount(new BigDecimal("3000"));
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));

        Invoice voided = invoiceService.voidInvoice(50L, "FINANCE", "票面信息开错，红字冲销重开");

        assertEquals(Invoice.STATUS_VOID, voided.getStatus());
        assertEquals("票面信息开错，红字冲销重开", voided.getVoidReason());
        assertNotNull(voided.getVoidedAt());
        // 需求详情上的有效票同步撤下：旧票号不能再当有效票
        assertNull(demand.getInvoiceNo());
        assertNull(demand.getInvoiceAmount());
    }

    @Test
    void void_withoutReason_fails() {
        Invoice invoice = new Invoice();
        invoice.setId(50L);
        invoice.setInvoiceNo("INV20260918-0001");
        invoice.setStatus(Invoice.STATUS_VALID);
        when(invoiceRepository.findById(50L)).thenReturn(Optional.of(invoice));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.voidInvoice(50L, "FINANCE", "  "));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("作废原因"));
        assertEquals(Invoice.STATUS_VALID, invoice.getStatus());
    }

    @Test
    void void_alreadyVoided_fails() {
        Invoice invoice = new Invoice();
        invoice.setId(50L);
        invoice.setInvoiceNo("INV20260918-0001");
        invoice.setStatus(Invoice.STATUS_VOID);
        when(invoiceRepository.findById(50L)).thenReturn(Optional.of(invoice));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> invoiceService.voidInvoice(50L, "FINANCE", "重复作废"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void reissueAfterVoid_newInvoiceIsCurrent_oldStaysVoid() {
        // 旧票已红字作废
        Invoice old = new Invoice();
        old.setId(50L);
        old.setInvoiceNo("INV" + java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-0001");
        old.setDemandId(1L);
        old.setAmount(new BigDecimal("3000"));
        old.setStatus(Invoice.STATUS_VOID);

        mockDemandAndAccount();
        demand.setOpened(1);
        when(transactionRepository.findByDemandIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(freezeTx));
        // 有效票查询为空（旧票已作废）；票号生成接在旧票号之后
        when(invoiceRepository.findTopByInvoiceNoStartingWithOrderByInvoiceNoDesc(anyString()))
                .thenReturn(Optional.of(old));

        Invoice reissued = invoiceService.issueInvoice(1L, "FINANCE", "财务小李", null);

        assertEquals(Invoice.STATUS_VALID, reissued.getStatus());
        assertNotEquals(old.getInvoiceNo(), reissued.getInvoiceNo());
        // 新票才是当前有效票：需求详情挂新票号
        assertEquals(reissued.getInvoiceNo(), demand.getInvoiceNo());
        assertEquals(Invoice.STATUS_VOID, old.getStatus());
    }

    // ==================== 流水对票：只认当前有效票 ====================

    @Test
    void fillCurrentInvoice_stampsOnlyValidInvoiceLinkedFlows() {
        Invoice valid = new Invoice();
        valid.setInvoiceNo("INV20260918-0002");
        valid.setDemandId(1L);
        valid.setAmount(new BigDecimal("3000"));
        valid.setStatus(Invoice.STATUS_VALID);
        valid.setFreezeTransactionId(99L);
        when(invoiceRepository.findByDemandIdInAndStatus(any(), eq(Invoice.STATUS_VALID)))
                .thenReturn(List.of(valid));

        DepositTransaction other = new DepositTransaction();
        other.setId(100L);
        other.setDemandId(1L);
        other.setType(DepositService.TYPE_REFUND);
        List<DepositTransaction> txs = List.of(freezeTx, other);

        invoiceService.fillCurrentInvoice(txs);

        // 冻结流水对到有效票；没关联的流水不带票号
        assertEquals("INV20260918-0002", freezeTx.getInvoiceNo());
        assertEquals(0, new BigDecimal("3000").compareTo(freezeTx.getInvoiceAmount()));
        assertNull(other.getInvoiceNo());
    }

    @Test
    void fillCurrentInvoice_voidedInvoiceNotStamped() {
        // 台账里只剩红字作废票：流水上不能再出现旧票号
        when(invoiceRepository.findByDemandIdInAndStatus(any(), eq(Invoice.STATUS_VALID)))
                .thenReturn(List.of());

        invoiceService.fillCurrentInvoice(List.of(freezeTx));

        assertNull(freezeTx.getInvoiceNo());
        assertNull(freezeTx.getInvoiceAmount());
    }
}
