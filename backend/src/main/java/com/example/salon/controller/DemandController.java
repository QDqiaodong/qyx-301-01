package com.example.salon.controller;

import com.example.salon.dto.RecommendResponse;
import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.entity.Invoice;
import com.example.salon.entity.LockRecord;
import com.example.salon.entity.RecommendResult;
import com.example.salon.entity.SiteVisit;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.RecommendResultRepository;
import com.example.salon.repository.SiteVisitRepository;
import com.example.salon.service.DepositService;
import com.example.salon.service.InvoiceService;
import com.example.salon.service.LockService;
import com.example.salon.service.RecommendService;
import com.example.salon.service.SiteVisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/demand")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DemandController {

    private final ActivityDemandRepository demandRepository;
    private final RecommendResultRepository recommendResultRepository;
    private final SiteVisitRepository siteVisitRepository;
    private final LockRecordRepository lockRecordRepository;
    private final RecommendService recommendService;
    private final SiteVisitService siteVisitService;
    private final LockService lockService;
    private final DepositService depositService;
    private final InvoiceService invoiceService;

    @GetMapping
    public ResponseEntity<List<ActivityDemand>> getAllDemands() {
        List<ActivityDemand> demands = demandRepository.findAllByOrderByCreatedAtDesc();
        // 需求列表带上当前有效票号/金额：与发票台账、押金流水看到同一张
        invoiceService.attachActiveInvoices(demands);
        return ResponseEntity.ok(demands);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActivityDemand> getDemandById(@PathVariable Long id) {
        return demandRepository.findById(id)
                .map(demand -> {
                    invoiceService.attachActiveInvoice(demand);
                    return ResponseEntity.ok(demand);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ActivityDemand> createDemand(@RequestBody ActivityDemand demand) {
        demand.setId(null);
        demand.setMatchStatus(0);
        demand.setLocked(0);
        demand.setLockBreakReason(null);
        demand.setDepositAmount(null);
        demand.setDepositFreezeId(null);
        demand.setDepositRefundSummary(null);
        return ResponseEntity.ok(demandRepository.save(demand));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ActivityDemand> updateDemand(@PathVariable Long id, @RequestBody ActivityDemand demand) {
        return demandRepository.findById(id)
                .map(existing -> {
                    // 锁定期间期望日期、人数、预算上限、必备设施冻结，不能直接改。
                    // 要改这些数字，须先解除锁定并作废当前推荐，再按新条件重算。
                    if (existing.getLocked() != null && existing.getLocked() == 1) {
                        ensureFrozenFieldsUnchanged(existing, demand);
                    }
                    existing.setCustomerName(demand.getCustomerName());
                    existing.setCustomerPhone(demand.getCustomerPhone());
                    existing.setDemandName(demand.getDemandName());
                    existing.setExpectedDate(demand.getExpectedDate());
                    existing.setExpectedPeople(demand.getExpectedPeople());
                    existing.setActivityCategory(demand.getActivityCategory());
                    existing.setBudgetMin(demand.getBudgetMin());
                    existing.setBudgetMax(demand.getBudgetMax());
                    existing.setRequiredFacilities(demand.getRequiredFacilities());
                    existing.setSpecialRequirements(demand.getSpecialRequirements());
                    if (existing.getLocked() == null || existing.getLocked() != 1) {
                        existing.setMatchStatus(0);
                    }
                    return ResponseEntity.ok(demandRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void ensureFrozenFieldsUnchanged(ActivityDemand existing, ActivityDemand incoming) {
        if (!Objects.equals(existing.getExpectedDate(), incoming.getExpectedDate())
                || !Objects.equals(existing.getExpectedPeople(), incoming.getExpectedPeople())
                || !Objects.equals(existing.getBudgetMax(), incoming.getBudgetMax())
                || !Objects.equals(normalize(existing.getRequiredFacilities()), normalize(incoming.getRequiredFacilities()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求已锁定，期望日期、人数、预算上限、必备设施在锁定期间不能直接修改；"
                            + "请先解除锁定并作废当前推荐，再按新条件重算");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDemand(@PathVariable Long id) {
        return demandRepository.findById(id)
                .map(demand -> {
                    if (demand.getLocked() != null && demand.getLocked() == 1) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "该需求已锁定，请先解除锁定后再删除");
                    }
                    recommendResultRepository.deleteByDemandId(id);
                    siteVisitRepository.findByDemandId(id).ifPresent(siteVisitRepository::delete);
                    lockRecordRepository.findByDemandIdOrderByLockedAtDesc(id)
                            .forEach(lockRecordRepository::delete);
                    demandRepository.delete(demand);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/recommend")
    public ResponseEntity<RecommendResponse> calculateRecommend(@PathVariable Long id) {
        return ResponseEntity.ok(recommendService.calculateRecommend(id));
    }

    @GetMapping("/{id}/recommend")
    public ResponseEntity<List<RecommendResult>> getRecommendResults(@PathVariable Long id) {
        return ResponseEntity.ok(recommendService.getRecommendResults(id));
    }

    // ==================== 踩点试场 ====================

    @PostMapping("/{id}/site-visit")
    public ResponseEntity<SiteVisit> registerSiteVisit(@PathVariable Long id, @RequestBody SiteVisit request) {
        return ResponseEntity.ok(siteVisitService.register(id, request));
    }

    @GetMapping("/{id}/site-visit")
    public ResponseEntity<SiteVisit> getSiteVisit(@PathVariable Long id) {
        SiteVisit visit = siteVisitService.getByDemand(id);
        return visit == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(visit);
    }

    @DeleteMapping("/{id}/site-visit")
    public ResponseEntity<Void> cancelSiteVisit(@PathVariable Long id) {
        siteVisitService.cancel(id);
        return ResponseEntity.ok().build();
    }

    // ==================== 场地锁定 ====================

    @PostMapping("/{id}/lock")
    public ResponseEntity<LockRecord> confirmLock(@PathVariable Long id, @RequestParam Long venueId) {
        return ResponseEntity.ok(lockService.confirmLock(id, venueId));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<LockRecord> releaseLock(@PathVariable Long id) {
        return ResponseEntity.ok(lockService.releaseLock(id));
    }

    @GetMapping("/{id}/locks")
    public ResponseEntity<List<LockRecord>> getLockHistory(@PathVariable Long id) {
        return ResponseEntity.ok(lockService.getLockHistory(id));
    }

    // ==================== 押金冻结 / 分档退押 ====================

    /** 取消活动前的退押预估：档位与金额完全由后端按活动日距离计算，仅用于展示。 */
    @GetMapping("/{id}/deposit/preview")
    public ResponseEntity<DepositService.DepositPreview> previewCancellation(@PathVariable Long id) {
        return ResponseEntity.ok(lockService.previewCancellation(id));
    }

    /**
     * 客户取消活动：按距活动日远近分档退押（≥3天全退、三天内半退、当天不退）。
     * 不接收退回比例参数——比例由后端财务规则固化，销售不能手改。
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<DepositTransaction> cancelActivity(@PathVariable Long id) {
        return ResponseEntity.ok(lockService.cancelActivity(id));
    }

    /** 该需求的押金流水（冻结、退回、没收），财务逐笔可核；关联的当前有效票号一并挂出。 */
    @GetMapping("/{id}/deposit/transactions")
    public ResponseEntity<List<DepositTransaction>> getDepositTransactions(@PathVariable Long id) {
        List<DepositTransaction> txs = depositService.listTransactionsByDemand(id);
        invoiceService.attachActiveInvoicesToTransactions(txs);
        return ResponseEntity.ok(txs);
    }

    /** 该需求的发票记录（有效票 + 红字作废票） */
    @GetMapping("/{id}/invoices")
    public ResponseEntity<List<Invoice>> getDemandInvoices(@PathVariable Long id) {
        return ResponseEntity.ok(invoiceService.listInvoicesByDemand(id));
    }
}
