package com.example.salon.service;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.CustomerAccount;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.entity.Venue;
import com.example.salon.repository.CustomerAccountRepository;
import com.example.salon.repository.DepositTransactionRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock private CustomerAccountRepository accountRepository;
    @Mock private DepositTransactionRepository transactionRepository;
    @InjectMocks private DepositService depositService;

    private ActivityDemand demand;
    private Venue venue;
    private CustomerAccount account;

    @BeforeEach
    void setUp() {
        // 保存流水时返回入参（Mockito 默认返回 null，服务方法会把保存结果直接返回给调用方）
        lenient().when(transactionRepository.save(any(DepositTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        demand = new ActivityDemand();
        demand.setId(1L);
        demand.setCustomerName("客户A");
        demand.setCustomerPhone("13800000000");
        demand.setDemandName("客户A沙龙");
        demand.setExpectedDate(LocalDateTime.parse("2026-10-10T09:00:00"));
        demand.setDepositAmount(new BigDecimal("3000"));

        venue = new Venue();
        venue.setId(10L);
        venue.setName("阳光厅");
        venue.setPricePerDay(new BigDecimal("3000"));

        account = new CustomerAccount();
        account.setId(7L);
        account.setCustomerName("客户A");
        account.setCustomerPhone("13800000000");
    }

    private void mockAccount(BigDecimal available, BigDecimal frozen) {
        account.setAvailableBalance(available);
        account.setFrozenBalance(frozen);
        when(accountRepository.findByCustomerNameAndCustomerPhone("客户A", "13800000000"))
                .thenReturn(Optional.of(account));
    }

    // ==================== 冻结 ====================

    @Test
    void freezeDeposit_succeeds_whenBalanceEnough() {
        mockAccount(new BigDecimal("5000"), BigDecimal.ZERO);

        DepositTransaction tx = depositService.freezeDeposit(demand, venue);

        assertEquals(DepositService.TYPE_FREEZE, tx.getType());
        assertEquals(0, new BigDecimal("3000").compareTo(tx.getAmount()));
        // 可用余额扣减、冻结余额增加
        assertEquals(0, new BigDecimal("2000").compareTo(account.getAvailableBalance()));
        assertEquals(0, new BigDecimal("3000").compareTo(account.getFrozenBalance()));
        verify(transactionRepository).save(any(DepositTransaction.class));
    }

    @Test
    void freezeDeposit_fails_whenBalanceInsufficient() {
        mockAccount(new BigDecimal("1000"), BigDecimal.ZERO);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> depositService.freezeDeposit(demand, venue));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("押金冻结失败"));
        // 冻结失败：余额不动、不产生流水（由外层事务整体回滚，场地不被占用）
        assertEquals(0, new BigDecimal("1000").compareTo(account.getAvailableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFrozenBalance()));
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    // ==================== 分档退押 ====================

    @Test
    void tier_moreThanThreeDays_fullRefund() {
        // 活动 10-10，取消 10-01 → 9 天，全额
        DepositService.RefundTier tier = depositService.calculateTier(
                demand.getExpectedDate(), LocalDateTime.parse("2026-10-01T10:00:00"));
        assertEquals(0, new BigDecimal("100").compareTo(tier.rate()));
        assertEquals(9, tier.daysToActivity());
    }

    @Test
    void tier_withinThreeDays_halfRefund() {
        DepositService.RefundTier twoDays = depositService.calculateTier(
                demand.getExpectedDate(), LocalDateTime.parse("2026-10-08T10:00:00"));
        assertEquals(0, new BigDecimal("50").compareTo(twoDays.rate()));
        assertEquals(2, twoDays.daysToActivity());

        DepositService.RefundTier oneDay = depositService.calculateTier(
                demand.getExpectedDate(), LocalDateTime.parse("2026-10-09T20:00:00"));
        assertEquals(0, new BigDecimal("50").compareTo(oneDay.rate()));
    }

    @Test
    void tier_sameDay_noRefund() {
        DepositService.RefundTier tier = depositService.calculateTier(
                demand.getExpectedDate(), LocalDateTime.parse("2026-10-10T08:00:00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(tier.rate()));
        assertTrue(tier.ruleText().contains("当天取消"));
    }

    @Test
    void tier_afterActivityDay_noRefund() {
        DepositService.RefundTier tier = depositService.calculateTier(
                demand.getExpectedDate(), LocalDateTime.parse("2026-10-11T08:00:00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(tier.rate()));
    }

    @Test
    void cancel_withinThreeDays_refundsHalf_andForfeitsHalf() {
        // 账户：冻结 3000、可用 0；活动前一天取消 → 退 1500、没收 1500
        mockAccount(BigDecimal.ZERO, new BigDecimal("3000"));

        DepositTransaction tx = depositService.cancelWithTieredRefund(
                demand, LocalDateTime.parse("2026-10-09T10:00:00"));

        assertEquals(DepositService.TYPE_REFUND, tx.getType());
        assertEquals(0, new BigDecimal("50").compareTo(tx.getRefundRate()));
        assertEquals(0, new BigDecimal("1500").compareTo(tx.getRefundAmount()));
        assertEquals(0, new BigDecimal("1500").compareTo(tx.getForfeitAmount()));
        assertEquals(0, new BigDecimal("1500").compareTo(account.getAvailableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFrozenBalance()));
    }

    @Test
    void cancel_sameDay_refundsNothing() {
        mockAccount(BigDecimal.ZERO, new BigDecimal("3000"));

        DepositTransaction tx = depositService.cancelWithTieredRefund(
                demand, LocalDateTime.parse("2026-10-10T08:00:00"));

        assertEquals(0, BigDecimal.ZERO.compareTo(tx.getRefundAmount()));
        assertEquals(0, new BigDecimal("3000").compareTo(tx.getForfeitAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getAvailableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFrozenBalance()));
    }

    @Test
    void cancel_early_refundsFully() {
        mockAccount(BigDecimal.ZERO, new BigDecimal("3000"));

        DepositTransaction tx = depositService.cancelWithTieredRefund(
                demand, LocalDateTime.parse("2026-10-01T10:00:00"));

        assertEquals(0, new BigDecimal("100").compareTo(tx.getRefundRate()));
        assertEquals(0, new BigDecimal("3000").compareTo(tx.getRefundAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(tx.getForfeitAmount()));
        assertEquals(0, new BigDecimal("3000").compareTo(account.getAvailableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFrozenBalance()));
    }

    @Test
    void releaseFullRefund_returnsEverything() {
        mockAccount(BigDecimal.ZERO, new BigDecimal("3000"));

        DepositTransaction tx = depositService.releaseFullRefund(demand, "解除重配，全额退回");

        assertEquals(DepositService.TYPE_UNFREEZE, tx.getType());
        assertEquals(0, new BigDecimal("3000").compareTo(tx.getRefundAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(tx.getForfeitAmount()));
        assertEquals(0, new BigDecimal("3000").compareTo(account.getAvailableBalance()));
    }

    @Test
    void settle_withoutFrozenDeposit_rejected() {
        demand.setDepositAmount(null);
        assertThrows(ResponseStatusException.class,
                () -> depositService.cancelWithTieredRefund(demand, LocalDateTime.now()));
    }
}
