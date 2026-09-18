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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结算发票：活动已开场、或押金已全额退完之后，财务给这条需求开一张结算发票。
 * 财务规矩：
 *   1) 只有财务角色能开票、能作废；销售锁场当天想先开票走报销，一律拒绝。
 *   2) 还在冻结中、没开场也没退完的需求不能开票，报错写明还差哪一笔没结。
 *   3) 票面金额 = 该需求已结清的冻结金额（开场路径）或实退金额（全额退押路径），
 *      客户名必须跟押金账户持有人同一人。
 *   4) 一条需求同时只有一张有效票；重开前必须先把旧票红字作废并留下作废原因，
 *      作废后旧票号不能再当有效票去报，新开的票才是当前有效票。
 * 发票台账、押金流水、需求详情三处看到的是同一张有效票号和同一金额。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    /** 能开票/作废的角色：财务 */
    public static final String ROLE_FINANCE = "FINANCE";

    private final InvoiceRepository invoiceRepository;
    private final ActivityDemandRepository demandRepository;
    private final DepositTransactionRepository transactionRepository;
    private final CustomerAccountRepository accountRepository;

    // ==================== 开票 ====================

    /**
     * 开结算发票。只有结清（已开场 / 押金全额退完）的需求能开；
     * 未结清、已有有效票、非财务角色、票面客户与押金账户不是同一人，都当场失败。
     */
    @Transactional
    public Invoice issueInvoice(Long demandId, String operatorRole, String operatorName, String customerNameInput) {
        requireFinance(operatorRole, "开结算发票");
        if (demandId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少需求ID，无法开票");
        }
        // 锁住需求行：两个财务前后脚给同一需求开票，只放行一个
        ActivityDemand demand = demandRepository.findByIdForUpdate(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));

        invoiceRepository.findByDemandIdAndStatus(demandId, Invoice.STATUS_VALID)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                            "该需求已开过有效发票「%s」（¥%s），不能重复开票；"
                                    + "如需重开，请先把旧票红字作废并留下作废原因，再开新票",
                            existing.getInvoiceNo(),
                            existing.getAmount().stripTrailingZeros().toPlainString()));
                });

        List<DepositTransaction> txs = transactionRepository.findByDemandIdOrderByCreatedAtDesc(demandId);
        DepositTransaction freezeTx = txs.stream()
                .filter(t -> DepositService.TYPE_FREEZE.equals(t.getType()))
                .findFirst().orElse(null);
        DepositTransaction settleTx = txs.stream()
                .filter(t -> DepositService.TYPE_REFUND.equals(t.getType())
                        || DepositService.TYPE_UNFREEZE.equals(t.getType()))
                .findFirst().orElse(null);

        boolean stillFrozen = demand.getDepositAmount() != null
                && demand.getDepositAmount().compareTo(BigDecimal.ZERO) > 0;
        boolean opened = demand.getOpened() != null && demand.getOpened() == 1;

        String basis;
        BigDecimal amount;
        Long settleTxId = null;
        if (opened && stillFrozen) {
            // 活动已开场：冻结押金转为已结清，票面金额 = 冻结金额
            basis = Invoice.BASIS_OPENED;
            amount = demand.getDepositAmount();
        } else if (stillFrozen) {
            // 还在冻结中、没开场也没退完：写明还差哪一笔没结
            Long freezeId = demand.getDepositFreezeId() != null
                    ? demand.getDepositFreezeId()
                    : (freezeTx == null ? null : freezeTx.getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "押金未结清，不能开票：冻结流水#%s（¥%s，场地「%s」）还在冻结中——"
                            + "活动未开场、押金未退完。等活动开场或押金全额退完后再开。",
                    freezeId == null ? "?" : freezeId,
                    demand.getDepositAmount().stripTrailingZeros().toPlainString(),
                    demand.getLockedVenueName() == null ? "-" : demand.getLockedVenueName()));
        } else if (settleTx == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求没有已结清的押金（未冻结过或没有结算流水），不能开票");
        } else if (settleTx.getRefundAmount().compareTo(settleTx.getAmount()) != 0) {
            // 退了一半或没退：不算全额退完，写明哪一笔没结
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "押金未全额退完，不能开票：结算流水#%d 冻结 ¥%s 只退回 ¥%s、没收 ¥%s，"
                            + "活动也未开场，不满足开票条件。",
                    settleTx.getId(),
                    settleTx.getAmount().stripTrailingZeros().toPlainString(),
                    settleTx.getRefundAmount().stripTrailingZeros().toPlainString(),
                    settleTx.getForfeitAmount().stripTrailingZeros().toPlainString()));
        } else {
            // 押金已全额退完：票面金额 = 实退金额
            basis = Invoice.BASIS_REFUNDED;
            amount = settleTx.getRefundAmount();
            settleTxId = settleTx.getId();
        }

        if (freezeTx == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "找不到该需求的押金冻结流水，票面金额对不上流水，不能开票");
        }

        // 票面对应的冻结流水：退押路径精确取结算流水当时结清的那笔冻结
        Long linkedFreezeTxId = settleTxId != null && settleTx.getFreezeTransactionId() != null
                ? settleTx.getFreezeTransactionId()
                : freezeTx.getId();

        // 票面客户必须跟押金账户持有人同一人：客户名从押金账户带出，不允许填别人
        CustomerAccount account = accountRepository.findById(freezeTx.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "押金账户不存在，无法核对票面客户，不能开票"));
        String inputName = customerNameInput == null ? "" : customerNameInput.trim();
        if (!inputName.isEmpty() && !inputName.equals(account.getCustomerName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                    "票面客户必须与押金账户持有人是同一人：该需求的押金账户持有人是「%s」，不能开给「%s」",
                    account.getCustomerName(), inputName));
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceNo(nextInvoiceNo());
        invoice.setDemandId(demand.getId());
        invoice.setDemandName(demand.getDemandName());
        invoice.setAccountId(account.getId());
        invoice.setCustomerName(account.getCustomerName());
        invoice.setCustomerPhone(account.getCustomerPhone());
        invoice.setAmount(amount);
        invoice.setSettleBasis(basis);
        invoice.setFreezeTransactionId(linkedFreezeTxId);
        invoice.setSettleTransactionId(settleTxId);
        invoice.setStatus(Invoice.STATUS_VALID);
        invoice.setIssuedBy(operatorName == null || operatorName.trim().isEmpty()
                ? "财务" : operatorName.trim());
        Invoice saved;
        try {
            saved = invoiceRepository.save(invoice);
        } catch (DataIntegrityViolationException e) {
            // 票号撞号（并发各开各的需求时同序号）：唯一约束兜底，提示重试
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发票号冲突，请重新提交开票");
        }

        // 需求详情同步挂上当前有效票：三处看到同一张票号、同一金额
        demand.setInvoiceNo(saved.getInvoiceNo());
        demand.setInvoiceAmount(saved.getAmount());
        demand.setInvoiceIssuedAt(saved.getCreatedAt() == null ? LocalDateTime.now() : saved.getCreatedAt());
        demandRepository.save(demand);

        log.info("需求ID {} 开出结算发票「{}」¥{}（结清依据：{}，开票人：{}）",
                demandId, saved.getInvoiceNo(), amount.stripTrailingZeros().toPlainString(),
                basis, saved.getIssuedBy());
        return saved;
    }

    // ==================== 红字作废 ====================

    /**
     * 红字作废：必须留作废原因。作废后旧票号留痕但不再是有效票，
     * 需求详情与押金流水上的有效票号同步撤下，之后可重开新票。
     */
    @Transactional
    public Invoice voidInvoice(Long invoiceId, String operatorRole, String reason) {
        requireFinance(operatorRole, "作废发票");
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在"));
        if (Invoice.STATUS_VOID.equals(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    String.format("发票「%s」已是红字作废状态，不能重复作废", invoice.getInvoiceNo()));
        }
        String voidReason = reason == null ? "" : reason.trim();
        if (voidReason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "作废发票必须留下作废原因（红字冲销依据），不能为空");
        }

        invoice.setStatus(Invoice.STATUS_VOID);
        invoice.setVoidReason(voidReason);
        invoice.setVoidedAt(LocalDateTime.now());
        Invoice saved = invoiceRepository.save(invoice);

        // 需求详情上的当前有效票同步撤下：旧票号不能再当有效票去报
        demandRepository.findById(invoice.getDemandId()).ifPresent(demand -> {
            if (Objects.equals(demand.getInvoiceNo(), invoice.getInvoiceNo())) {
                demand.setInvoiceNo(null);
                demand.setInvoiceAmount(null);
                demand.setInvoiceIssuedAt(null);
                demandRepository.save(demand);
            }
        });

        log.info("发票「{}」（需求ID {}）已红字作废，原因：{}", invoice.getInvoiceNo(), invoice.getDemandId(), voidReason);
        return saved;
    }

    // ==================== 台账 / 流水对票 ====================

    /** 发票台账：有效票与红字作废票逐笔可见 */
    @Transactional(readOnly = true)
    public List<Invoice> listInvoices() {
        return invoiceRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 该需求的当前有效票（无有效票返回 null） */
    @Transactional(readOnly = true)
    public Invoice getCurrentInvoice(Long demandId) {
        return invoiceRepository.findByDemandIdAndStatus(demandId, Invoice.STATUS_VALID).orElse(null);
    }

    /**
     * 给押金流水带上当前有效票号：只有发票台账里的有效票、且流水正是该票
     * 对应的冻结/结算流水时才带出来。红字作废后旧票号自动消失，
     * 重开后流水页自动换成新票号——流水页、发票台账、需求详情始终是同一张有效票。
     */
    @Transactional(readOnly = true)
    public void fillCurrentInvoice(List<DepositTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return;
        }
        Set<Long> demandIds = txs.stream()
                .map(DepositTransaction::getDemandId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (demandIds.isEmpty()) {
            return;
        }
        List<Invoice> validInvoices = invoiceRepository.findByDemandIdInAndStatus(demandIds, Invoice.STATUS_VALID);
        Map<Long, Invoice> byFreezeTxId = new HashMap<>();
        Map<Long, Invoice> bySettleTxId = new HashMap<>();
        for (Invoice inv : validInvoices) {
            if (inv.getFreezeTransactionId() != null) {
                byFreezeTxId.put(inv.getFreezeTransactionId(), inv);
            }
            if (inv.getSettleTransactionId() != null) {
                bySettleTxId.put(inv.getSettleTransactionId(), inv);
            }
        }
        for (DepositTransaction tx : txs) {
            Invoice inv = byFreezeTxId.get(tx.getId());
            if (inv == null) {
                inv = bySettleTxId.get(tx.getId());
            }
            if (inv != null) {
                tx.setInvoiceNo(inv.getInvoiceNo());
                tx.setInvoiceAmount(inv.getAmount());
            }
        }
    }

    // ==================== 内部 ====================

    /** 只有财务角色能开票/作废；销售或其他人操作一律 403 */
    private void requireFinance(String operatorRole, String action) {
        String role = operatorRole == null ? "" : operatorRole.trim().toUpperCase();
        if (!ROLE_FINANCE.equals(role)) {
            String who = role.isEmpty() ? "未表明身份" :
                    ("SALES".equals(role) ? "销售" : role);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, String.format(
                    "只有财务角色能%s（当前角色：%s）。销售或其他人%s一律不准，请转财务处理。",
                    action, who, action));
        }
    }

    /** 当天票号：INVyyyyMMdd-####，全库唯一、作废不复用 */
    private String nextInvoiceNo() {
        String prefix = "INV" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        int next = invoiceRepository.findTopByInvoiceNoStartingWithOrderByInvoiceNoDesc(prefix)
                .map(inv -> {
                    try {
                        return Integer.parseInt(inv.getInvoiceNo().substring(prefix.length())) + 1;
                    } catch (NumberFormatException e) {
                        return 1;
                    }
                })
                .orElse(1);
        return prefix + String.format("%04d", next);
    }
}
