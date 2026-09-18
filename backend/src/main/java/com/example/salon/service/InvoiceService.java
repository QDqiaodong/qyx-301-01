package com.example.salon.service;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.CustomerAccount;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.entity.Invoice;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.CustomerAccountRepository;
import com.example.salon.repository.DepositTransactionRepository;
import com.example.salon.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算发票（财务专用）。
 * 开票条件按财务规矩与押金冻结/结清状态硬挂钩：
 *   1. 活动已经开场——冻结中的押金随开场确认结清，票面金额取冻结流水的冻结额；
 *   2. 押金已经全额实退回客户账户（解除/破裂/提前取消），票面金额取结算流水的实退额。
 * 还在冻结中、没开场也没退完的需求一律不能开票，错误里点明还差哪一笔没结。
 * 票面金额只从押金流水核算（不接收任何手填金额），客户名取押金账户户主。
 * 一条需求同一时刻最多一张有效票；重开必须先把旧票作废成红字并留下作废原因，
 * 作废后旧票号不再是有效票，新票才是当前有效票。开票/作废只有财务角色能操作。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    /** 财务角色：只有该角色能开票/作废；销售等其它角色一律 403 */
    public static final String ROLE_FINANCE = "FINANCE";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvoiceRepository invoiceRepository;
    private final ActivityDemandRepository demandRepository;
    private final DepositTransactionRepository transactionRepository;
    private final CustomerAccountRepository accountRepository;
    private final InvoiceNumberAllocator numberAllocator;

    /** 开票依据解析结果：金额、依据类型、冻结/结算流水、说明 */
    private record SettlementBasis(BigDecimal amount, String basisType,
                                   DepositTransaction freezeTx, DepositTransaction settlementTx,
                                   String reason) {
    }

    /**
     * 开具结算发票。
     *
     * @param operatorRole 操作角色（HTTP 头 X-Operator-Role）：非财务直接 403
     */
    @Transactional
    public Invoice issue(Long demandId, String operatorRole, String operatorName) {
        requireFinance(operatorRole, "开具结算发票");

        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在，无法开票"));

        invoiceRepository.findByDemandIdAndStatus(demandId, Invoice.STATUS_VALID)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                            "该需求已有一张有效发票（发票号 %s，票面金额 ¥%s），不能重复开票；"
                                    + "如需重开，请先把旧票作废成红字并填写作废原因。",
                            existing.getInvoiceNo(), existing.getAmount().stripTrailingZeros().toPlainString()));
                });

        SettlementBasis basis = resolveSettlementBasis(demand);

        // 客户名必须与押金账户户主同一人：以押金账户为准，不信用传入的名字
        CustomerAccount account = accountRepository
                .findByCustomerNameAndCustomerPhone(
                        nz(demand.getCustomerName()).trim(),
                        demand.getCustomerPhone() == null ? "" : demand.getCustomerPhone().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "开票失败：找不到该需求客户的押金账户，无法核对户主身份，不能开票"));
        if (!account.getCustomerName().trim().equals(nz(demand.getCustomerName()).trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "开票失败：需求客户「%s」与押金账户户主「%s」不是同一人，发票客户名必须与押金账户一致。",
                    demand.getCustomerName(), account.getCustomerName()));
        }

        Invoice invoice = new Invoice();
        invoice.setDemandId(demand.getId());
        invoice.setDemandName(demand.getDemandName());
        invoice.setAccountId(account.getId());
        invoice.setCustomerName(account.getCustomerName());
        invoice.setCustomerPhone(account.getCustomerPhone());
        invoice.setVenueId(demand.getLockedVenueId() != null
                ? demand.getLockedVenueId() : (basis.freezeTx() != null ? basis.freezeTx().getVenueId() : null));
        invoice.setVenueName(demand.getLockedVenueName() != null
                ? demand.getLockedVenueName() : (basis.freezeTx() != null ? basis.freezeTx().getVenueName() : null));
        invoice.setActivityDate(demand.getExpectedDate());
        invoice.setAmount(basis.amount());
        invoice.setBasisType(basis.basisType());
        invoice.setFreezeTransactionId(basis.freezeTx() == null ? null : basis.freezeTx().getId());
        invoice.setSettlementTransactionId(basis.settlementTx() == null ? null : basis.settlementTx().getId());
        invoice.setBasisReason(basis.reason());
        LocalDateTime issuedAt = LocalDateTime.now();
        // 票号在独立事务里按全局计数器分配（FP+开票日+6位流水），两人前后脚开也不重号
        long serial = numberAllocator.nextValue();
        String invoiceNo = "FP" + issuedAt.format(DAY_FMT) + String.format("%06d", serial);

        invoice.setStatus(Invoice.STATUS_VALID);
        invoice.setActiveFlag(1);
        invoice.setInvoiceNo(invoiceNo);
        invoice.setIssuedAt(issuedAt);
        invoice.setIssuedBy(normalizeOperator(operatorName));

        Invoice saved;
        try {
            saved = invoiceRepository.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException e) {
            // 两人前后脚开票：唯一索引 (demand_id, active_flag) 只放行一张
            log.warn("开票落库触发唯一约束冲突，demandId={}：{}", demandId, e.getMostSpecificCause().getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求已有一张有效发票，不能重复开票；请先把旧票作废成红字后再开新票");
        }

        log.info("需求ID {} 开具结算发票：票号 {}，票面金额 ¥{}，依据 {}（财务操作人：{}）",
                demandId, saved.getInvoiceNo(), saved.getAmount().toPlainString(),
                basis.basisType(), saved.getIssuedBy());
        return saved;
    }

    /**
     * 把有效发票作废成红字票。只有财务能操作；作废必须填写原因；
     * 已作废的不能重复作废。作废后该票号不再是有效票，需求才可重新开票。
     */
    @Transactional
    public Invoice voidInvoice(Long invoiceId, String reason, String operatorRole, String operatorName) {
        requireFinance(operatorRole, "作废发票");

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在，无法作废"));
        if (!Invoice.STATUS_VALID.equals(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "发票号 " + invoice.getInvoiceNo() + " 已是红字作废票，不能重复作废");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "作废发票必须填写作废原因（红字票需留痕可查）");
        }

        invoice.setStatus(Invoice.STATUS_RED_VOID);
        invoice.setActiveFlag(null);
        invoice.setVoidedAt(LocalDateTime.now());
        invoice.setVoidReason(normalizedReason);
        invoice.setVoidedBy(normalizeOperator(operatorName));
        Invoice saved = invoiceRepository.save(invoice);

        log.info("发票号 {} 作废成红字票，作废原因：{}（财务操作人：{}），需求ID {} 现在可重新开票",
                invoice.getInvoiceNo(), normalizedReason, saved.getVoidedBy(), invoice.getDemandId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Invoice> listAllInvoices() {
        return invoiceRepository.findAllByOrderByIssuedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Invoice> listInvoicesByDemand(Long demandId) {
        return invoiceRepository.findByDemandIdOrderByIssuedAtDesc(demandId);
    }

    // ==================== 视图挂载：台账/流水/需求详情看到同一张有效票 ====================

    /** 把当前有效票号与金额挂到需求详情（无有效票不挂） */
    @Transactional(readOnly = true)
    public void attachActiveInvoice(ActivityDemand demand) {
        if (demand == null || demand.getId() == null) {
            return;
        }
        invoiceRepository.findByDemandIdAndStatus(demand.getId(), Invoice.STATUS_VALID)
                .ifPresent(invoice -> applyToDemand(demand, invoice));
    }

    /** 批量挂载，避免列表逐条查询 */
    @Transactional(readOnly = true)
    public void attachActiveInvoices(List<ActivityDemand> demands) {
        if (demands == null || demands.isEmpty()) {
            return;
        }
        List<Long> demandIds = demands.stream().map(ActivityDemand::getId).toList();
        Map<Long, Invoice> activeByDemand = new HashMap<>();
        invoiceRepository.findByDemandIdInAndStatus(demandIds, Invoice.STATUS_VALID)
                .forEach(inv -> activeByDemand.put(inv.getDemandId(), inv));
        demands.forEach(d -> {
            Invoice inv = activeByDemand.get(d.getId());
            if (inv != null) {
                applyToDemand(d, inv);
            }
        });
    }

    /**
     * 把每张有效票挂到它对应的冻结流水与结算流水上：
     * 财务在押金流水里看到的票号、金额必须与发票台账/需求详情是同一张。
     */
    @Transactional(readOnly = true)
    public void attachActiveInvoicesToTransactions(List<DepositTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return;
        }
        List<Long> txIds = txs.stream().map(DepositTransaction::getId).filter(java.util.Objects::nonNull).toList();
        if (txIds.isEmpty()) {
            return;
        }
        Map<Long, Invoice> invoiceByTxId = new HashMap<>();
        invoiceRepository.findValidLinkedToTransactions(txIds, Invoice.STATUS_VALID)
                .forEach(inv -> {
                    if (inv.getFreezeTransactionId() != null) {
                        invoiceByTxId.put(inv.getFreezeTransactionId(), inv);
                    }
                    if (inv.getSettlementTransactionId() != null) {
                        invoiceByTxId.put(inv.getSettlementTransactionId(), inv);
                    }
                });
        txs.forEach(tx -> {
            Invoice inv = invoiceByTxId.get(tx.getId());
            if (inv != null) {
                applyToTransaction(tx, inv);
            }
        });
    }

    private void applyToDemand(ActivityDemand demand, Invoice inv) {
        demand.setCurrentInvoiceId(inv.getId());
        demand.setCurrentInvoiceNo(inv.getInvoiceNo());
        demand.setCurrentInvoiceAmount(inv.getAmount());
        demand.setCurrentInvoiceStatus(inv.getStatus());
    }

    private void applyToTransaction(DepositTransaction tx, Invoice inv) {
        tx.setCurrentInvoiceId(inv.getId());
        tx.setCurrentInvoiceNo(inv.getInvoiceNo());
        tx.setCurrentInvoiceAmount(inv.getAmount());
        tx.setCurrentInvoiceStatus(inv.getStatus());
    }

    // ==================== 开票依据：只跟押金冻结结清状态挂钩，金额只从流水取 ====================

    private SettlementBasis resolveSettlementBasis(ActivityDemand demand) {
        List<DepositTransaction> txs = transactionRepository.findByDemandIdOrderByCreatedAtDesc(demand.getId());
        List<DepositTransaction> freezes = txs.stream()
                .filter(t -> DepositService.TYPE_FREEZE.equals(t.getType())).toList();
        if (freezes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "开票失败：该需求从未冻结过押金，没有任何已结清的冻结或实退金额，不能开具结算发票。");
        }

        boolean freezeOutstanding = demand.getLocked() != null && demand.getLocked() == 1
                && demand.getDepositAmount() != null && demand.getDepositAmount().compareTo(BigDecimal.ZERO) > 0;

        if (freezeOutstanding) {
            // 冻结还在：只有活动已经开场才算结清；没开场不能开（销售锁场当天报销诉求被财务规矩挡下）
            boolean opened = demand.getOpened() != null && demand.getOpened() == 1;
            if (!opened) {
                DepositTransaction currentFreeze = freezes.get(0);
                for (DepositTransaction f : freezes) {
                    if (java.util.Objects.equals(f.getId(), demand.getDepositFreezeId())) {
                        currentFreeze = f;
                        break;
                    }
                }
                final DepositTransaction outstandingFreeze = currentFreeze;
                BigDecimal frozen = nz(outstandingFreeze.getAmount());
                boolean settledAfter = txs.stream().anyMatch(t ->
                        (DepositService.TYPE_REFUND.equals(t.getType())
                                || DepositService.TYPE_UNFREEZE.equals(t.getType()))
                                && java.util.Objects.equals(t.getFreezeTransactionId(), outstandingFreeze.getId()));
                String tail = settledAfter
                        ? "该笔冻结已有退回流水，与冻结状态不一致，请先核对押金台账后再开票。"
                        : "活动尚未开场，该笔冻结押金既未随开场确认结清、也未实退回客户账户，"
                                + "请先完成开场（两岗签到齐全后开场）或先全额退回押金，再开具结算发票。";
                throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                        "开票失败：还差冻结中的押金 ¥%s（冻结流水 #%d，场地「%s」）没有结清。%s",
                        frozen.stripTrailingZeros().toPlainString(),
                        outstandingFreeze.getId(), outstandingFreeze.getVenueName(), tail));
            }

            // 已开场：以当前冻结流水为结清依据，票面金额取冻结额（以台账流水为准，不取需求卡片上的手填值）
            DepositTransaction currentFreeze = freezes.get(0);
            for (DepositTransaction f : freezes) {
                if (java.util.Objects.equals(f.getId(), demand.getDepositFreezeId())) {
                    currentFreeze = f;
                    break;
                }
            }
            DepositTransaction freeze = currentFreeze;
            boolean settledAfter = txs.stream().anyMatch(t ->
                    (DepositService.TYPE_REFUND.equals(t.getType())
                            || DepositService.TYPE_UNFREEZE.equals(t.getType()))
                            && java.util.Objects.equals(t.getFreezeTransactionId(), freeze.getId()));
            if (settledAfter) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "开票失败：当前冻结押金已存在退回结算流水但需求仍显示冻结中，台账状态不一致，请先核对押金流水后再开票。");
            }
            BigDecimal frozenAmount = nz(freeze.getAmount());
            if (frozenAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "开票失败：冻结流水 #" + freeze.getId() + " 金额无效，无法按冻结额开票，请先核对押金台账。");
            }
            String reason = String.format(
                    "活动已于 %s 开场，冻结押金随开场确认结清；按冻结流水 #%d 的冻结金额开票。",
                    demand.getOpenedAt() == null ? "开场当天" : demand.getOpenedAt().toLocalDate(),
                    freeze.getId());
            return new SettlementBasis(frozenAmount, Invoice.BASIS_FROZEN_OPENED, freeze, null, reason);
        }

        // 冻结已不在（取消/解除/破裂均已结算）：必须有一笔把冻结全额结清的实退流水
        List<DepositTransaction> settlements = txs.stream()
                .filter(t -> DepositService.TYPE_REFUND.equals(t.getType())
                        || DepositService.TYPE_UNFREEZE.equals(t.getType()))
                .toList();
        if (settlements.isEmpty()) {
            DepositTransaction lastFreeze = freezes.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "开票失败：还差冻结押金 ¥%s（冻结流水 #%d）没有结清——该笔冻结没有任何退回结算流水，"
                            + "请先完成全额退押后再开票。",
                    nz(lastFreeze.getAmount()).stripTrailingZeros().toPlainString(), lastFreeze.getId()));
        }

        DepositTransaction settlement = settlements.get(0); // 最近一笔结算
        BigDecimal refund = nz(settlement.getRefundAmount());
        BigDecimal frozen = nz(settlement.getAmount());
        BigDecimal forfeit = nz(settlement.getForfeitAmount());
        boolean fullyRefunded = nz(settlement.getRefundRate()).compareTo(BigDecimal.valueOf(100)) == 0
                && forfeit.compareTo(BigDecimal.ZERO) == 0
                && refund.compareTo(frozen) == 0
                && refund.compareTo(BigDecimal.ZERO) > 0;
        if (!fullyRefunded) {
            String what;
            if (refund.compareTo(BigDecimal.ZERO) <= 0) {
                what = String.format(
                        "冻结押金 ¥%s 已被全额没收（结算流水 #%d），没有实退回客户账户的金额可作为票面金额，不能开票。",
                        frozen.stripTrailingZeros().toPlainString(), settlement.getId());
            } else {
                what = String.format(
                        "押金只实退了 ¥%s，还有 ¥%s 被没收未结清（结算流水 #%d，退回比例 %s%%），"
                                + "票面金额无法与已结清金额对齐，不能开票；只有押金全额实退完之后才能开。",
                        refund.stripTrailingZeros().toPlainString(),
                        forfeit.stripTrailingZeros().toPlainString(),
                        settlement.getId(),
                        settlement.getRefundRate() == null ? "-"
                                : settlement.getRefundRate().stripTrailingZeros().toPlainString());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "开票失败：" + what);
        }

        DepositTransaction linkedFreeze = settlement.getFreezeTransactionId() == null ? freezes.get(0)
                : freezes.stream()
                        .filter(f -> java.util.Objects.equals(f.getId(), settlement.getFreezeTransactionId()))
                        .findFirst().orElse(freezes.get(0));
        String reason = String.format(
                "冻结押金已 100%% 实退回客户账户：冻结流水 #%d 冻结 ¥%s，结算流水 #%d 实退 ¥%s、无没收，按实退金额开票。",
                linkedFreeze.getId(), frozen.stripTrailingZeros().toPlainString(),
                settlement.getId(), refund.stripTrailingZeros().toPlainString());
        return new SettlementBasis(refund, Invoice.BASIS_FULLY_REFUNDED, linkedFreeze, settlement, reason);
    }

    // ==================== 角色与工具 ====================

    private void requireFinance(String operatorRole, String action) {
        String role = operatorRole == null ? "" : operatorRole.trim().toUpperCase();
        if (!ROLE_FINANCE.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, String.format(
                    "%s失败：只有财务角色才能%s，当前操作角色不是财务（销售等其它角色无权开具结算发票）。",
                    action, action));
        }
    }

    private String normalizeOperator(String operatorName) {
        String name = operatorName == null ? "" : operatorName.trim();
        return name.isEmpty() ? "财务" : name;
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
