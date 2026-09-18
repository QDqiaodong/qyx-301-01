package com.example.salon.service;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.CustomerAccount;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.entity.Venue;
import com.example.salon.repository.CustomerAccountRepository;
import com.example.salon.repository.DepositTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 客户押金：口头看中场地后先冻结一笔押金，冻结成功需求才进入待办活动；
 * 活动取消按距活动日远近分档退押（财务固定比例，销售不能手改）：
 *   距活动日 ≥3 天取消：全额退回；
 *   距活动日 1～2 天（三天内）取消：只退一半；
 *   活动当天（及逾期）取消：不退。
 * 每一笔冻结、退回、没收、充值都落押金流水台账，财务逐笔可见。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepositService {

    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_FREEZE = "FREEZE";
    /** 客户主动取消活动：按分档比例退回 */
    public static final String TYPE_REFUND = "REFUND";
    /** 解除重配、场地原因锁定破裂等非客户违约：全额解冻退回 */
    public static final String TYPE_UNFREEZE = "UNFREEZE";

    private final CustomerAccountRepository accountRepository;
    private final DepositTransactionRepository transactionRepository;

    /** 分档结果：rate 为退回比例（100/50/0） */
    public record RefundTier(BigDecimal rate, long daysToActivity, String ruleText) {
    }

    /**
     * 冻结押金：按场地日租金从客户可用余额冻结。
     * 余额不足直接抛 409——由调用方在同一事务里执行，抛出后整个锁定回滚，
     * 需求不进待办活动、场地当天也不被占。
     */
    @Transactional
    public DepositTransaction freezeDeposit(ActivityDemand demand, Venue venue) {
        BigDecimal amount = venue.getPricePerDay();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场地日租金无效，无法冻结押金");
        }

        CustomerAccount account = getOrCreateAccount(demand.getCustomerName(), demand.getCustomerPhone());
        if (account.getAvailableBalance() == null
                || account.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "押金冻结失败：客户「%s」可用押金余额 ¥%s 不足，冻结该场地需押金 ¥%s。"
                            + "当天不能开场、场地不予锁定占用，请先充值补足押金后再确认。",
                    account.getCustomerName(),
                    nz(account.getAvailableBalance()).stripTrailingZeros().toPlainString(),
                    amount.stripTrailingZeros().toPlainString()));
        }

        account.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        account.setFrozenBalance(nz(account.getFrozenBalance()).add(amount));
        accountRepository.save(account);

        DepositTransaction tx = new DepositTransaction();
        tx.setAccountId(account.getId());
        tx.setCustomerName(account.getCustomerName());
        tx.setCustomerPhone(account.getCustomerPhone());
        tx.setDemandId(demand.getId());
        tx.setDemandName(demand.getDemandName());
        tx.setVenueId(venue.getId());
        tx.setVenueName(venue.getName());
        tx.setType(TYPE_FREEZE);
        tx.setAmount(amount);
        tx.setRefundAmount(BigDecimal.ZERO);
        tx.setForfeitAmount(BigDecimal.ZERO);
        tx.setActivityDate(demand.getExpectedDate());
        tx.setReason(String.format("口头看中场地「%s」，按日租金冻结押金，冻结成功后进入待办活动", venue.getName()));
        DepositTransaction saved = transactionRepository.save(tx);

        log.info("需求ID {} 冻结押金 ¥{}（场地「{}」，账户ID {}）",
                demand.getId(), amount.toPlainString(), venue.getName(), account.getId());
        return saved;
    }

    /**
     * 客户取消活动：按取消日距活动日远近分档结算退押。比例由后端固定计算，
     * 不接收任何外部传入的比例，销售无法手改。
     */
    @Transactional
    public DepositTransaction cancelWithTieredRefund(ActivityDemand demand, LocalDateTime canceledAt) {
        requireFrozen(demand);
        RefundTier tier = calculateTier(demand.getExpectedDate(), canceledAt);
        String reason = String.format("客户取消活动：取消日 %s，活动日 %s，距活动日 %d 天。%s",
                canceledAt.toLocalDate(), demand.getExpectedDate().toLocalDate(),
                tier.daysToActivity(), tier.ruleText());
        return settle(demand, TYPE_REFUND, tier.rate(), reason);
    }

    /**
     * 非客户违约的全额退回：销售解除锁定回待重配、场地停用/涨价/拆设施导致锁定自行破裂。
     * 冻结押金 100% 退回客户可用余额。
     */
    @Transactional
    public DepositTransaction releaseFullRefund(ActivityDemand demand, String reason) {
        requireFrozen(demand);
        return settle(demand, TYPE_UNFREEZE, BigDecimal.valueOf(100), reason);
    }

    /**
     * 取消前的退押预估（只读）：返回按当前日期算出的档位、退回与没收金额。
     * 仅用于向销售/客户展示规则，最终结算仍以后端为准。
     */
    @Transactional(readOnly = true)
    public DepositPreview previewCancellation(ActivityDemand demand) {
        requireFrozen(demand);
        RefundTier tier = calculateTier(demand.getExpectedDate(), LocalDateTime.now());
        BigDecimal frozen = nz(demand.getDepositAmount());
        BigDecimal refund = applyRate(frozen, tier.rate());
        BigDecimal forfeit = frozen.subtract(refund);
        return new DepositPreview(frozen, tier.rate(), refund, forfeit,
                tier.daysToActivity(), tier.ruleText());
    }

    /**
     * 分档规则（财务固定）：
     * 活动当天（含逾期，天数≤0）不退；1～2 天（三天内）退一半；≥3 天全额。
     */
    public RefundTier calculateTier(LocalDateTime activityDate, LocalDateTime now) {
        if (activityDate == null) {
            // 冻结时已要求活动日必填，兜底按最保守的「不退」处理
            return new RefundTier(BigDecimal.ZERO, 0, "活动日期缺失，按当天取消处理，押金不退");
        }
        LocalDate today = now.toLocalDate();
        LocalDate activityDay = activityDate.toLocalDate();
        long days = ChronoUnit.DAYS.between(today, activityDay);

        if (days <= 0) {
            return new RefundTier(BigDecimal.ZERO, days, "活动当天取消，押金不退");
        }
        if (days < 3) {
            return new RefundTier(BigDecimal.valueOf(50), days, "距活动日三天内取消，只退一半押金");
        }
        return new RefundTier(BigDecimal.valueOf(100), days, "距活动日三天（含）以上取消，全额退回押金");
    }

    @Transactional
    public CustomerAccount recharge(Long accountId, BigDecimal amount, String note) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "充值金额必须大于0");
        }
        CustomerAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "押金账户不存在"));
        account.setAvailableBalance(nz(account.getAvailableBalance()).add(amount));
        accountRepository.save(account);

        DepositTransaction tx = new DepositTransaction();
        tx.setAccountId(account.getId());
        tx.setCustomerName(account.getCustomerName());
        tx.setCustomerPhone(account.getCustomerPhone());
        tx.setType(TYPE_RECHARGE);
        tx.setAmount(amount);
        tx.setRefundAmount(amount);
        tx.setForfeitAmount(BigDecimal.ZERO);
        tx.setRefundRate(BigDecimal.valueOf(100));
        tx.setReason(note == null || note.trim().isEmpty() ? "押金充值入账" : note.trim());
        transactionRepository.save(tx);

        log.info("押金账户ID {} 充值 ¥{}", accountId, amount.toPlainString());
        return account;
    }

    @Transactional(readOnly = true)
    public List<CustomerAccount> listAccounts() {
        return accountRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<DepositTransaction> listAllTransactions() {
        return transactionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<DepositTransaction> listTransactionsByDemand(Long demandId) {
        return transactionRepository.findByDemandIdOrderByCreatedAtDesc(demandId);
    }

    @Transactional
    public CustomerAccount getOrCreateAccount(String name, String phone) {
        String normalizedName = name == null ? "" : name.trim();
        String normalizedPhone = normalizePhone(phone);
        if (normalizedName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户姓名缺失，无法建立押金账户");
        }
        return accountRepository.findByCustomerNameAndCustomerPhone(normalizedName, normalizedPhone)
                .orElseGet(() -> {
                    CustomerAccount account = new CustomerAccount();
                    account.setCustomerName(normalizedName);
                    account.setCustomerPhone(normalizedPhone);
                    account.setAvailableBalance(BigDecimal.ZERO);
                    account.setFrozenBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    // ==================== 内部结算 ====================

    private DepositTransaction settle(ActivityDemand demand, String type, BigDecimal rate, String reason) {
        BigDecimal frozen = nz(demand.getDepositAmount());
        Long freezeTxId = demand.getDepositFreezeId();

        CustomerAccount account = getOrCreateAccount(demand.getCustomerName(), demand.getCustomerPhone());
        BigDecimal refund = applyRate(frozen, rate);
        BigDecimal forfeit = frozen.subtract(refund);

        account.setFrozenBalance(nz(account.getFrozenBalance()).subtract(frozen));
        account.setAvailableBalance(nz(account.getAvailableBalance()).add(refund));
        accountRepository.save(account);

        DepositTransaction tx = new DepositTransaction();
        tx.setAccountId(account.getId());
        tx.setCustomerName(account.getCustomerName());
        tx.setCustomerPhone(account.getCustomerPhone());
        tx.setDemandId(demand.getId());
        tx.setDemandName(demand.getDemandName());
        tx.setVenueId(demand.getLockedVenueId());
        tx.setVenueName(demand.getLockedVenueName());
        tx.setType(type);
        tx.setAmount(frozen);
        tx.setRefundAmount(refund);
        tx.setForfeitAmount(forfeit);
        tx.setRefundRate(rate);
        tx.setFreezeTransactionId(freezeTxId);
        tx.setActivityDate(demand.getExpectedDate());
        tx.setReason(reason);
        DepositTransaction saved = transactionRepository.save(tx);

        log.info("需求ID {} 押金结算：冻结 ¥{}，退回 ¥{}（比例 {}%），没收 ¥{}，类型 {}",
                demand.getId(), frozen.toPlainString(), refund.toPlainString(),
                rate.stripTrailingZeros().toPlainString(), forfeit.toPlainString(), type);
        return saved;
    }

    private void requireFrozen(ActivityDemand demand) {
        if (demand.getDepositAmount() == null
                || demand.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该需求当前没有冻结中的押金");
        }
    }

    private BigDecimal applyRate(BigDecimal frozen, BigDecimal rate) {
        // 金额到分：冻结额 × 比例 / 100，四舍五入
        return frozen.multiply(rate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizePhone(String phone) {
        return phone == null ? "" : phone.trim();
    }

    /** 取消活动退押预估 */
    public record DepositPreview(BigDecimal frozenAmount, BigDecimal refundRate,
                                 BigDecimal refundAmount, BigDecimal forfeitAmount,
                                 long daysToActivity, String ruleText) {
    }
}
