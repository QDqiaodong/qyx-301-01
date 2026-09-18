package com.example.salon.controller;

import com.example.salon.entity.Invoice;
import com.example.salon.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 结算发票：财务在「活动已开场」或「押金已全额退完」后为需求开结算发票。
 * 只有财务角色能开票/作废（operatorRole 必须是 FINANCE）；
 * 未结清、已有有效票、票面客户与押金账户不同人，都会被后端拒绝并写明原因。
 * 作废走红字：必须留作废原因，旧票号留痕但不再有效，重开的新票才是当前有效票。
 */
@RestController
@RequestMapping("/api/finance/invoices")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InvoiceController {

    private final InvoiceService invoiceService;

    /** 发票台账：有效票与红字作废票逐笔可见 */
    @GetMapping
    public ResponseEntity<List<Invoice>> listInvoices() {
        return ResponseEntity.ok(invoiceService.listInvoices());
    }

    /** 该需求的当前有效票（红字作废的旧票不会返回） */
    @GetMapping("/demand/{demandId}")
    public ResponseEntity<Invoice> currentOfDemand(@PathVariable Long demandId) {
        Invoice invoice = invoiceService.getCurrentInvoice(demandId);
        return invoice == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(invoice);
    }

    /**
     * 开结算发票。body：demandId 必填；operatorRole 必须是 FINANCE；
     * operatorName 开票人；customerName 可空，填了就必须跟押金账户持有人同一人。
     */
    @PostMapping
    public ResponseEntity<Invoice> issue(@RequestBody Map<String, Object> body) {
        Long demandId = parseLong(body == null ? null : body.get("demandId"));
        String operatorRole = str(body == null ? null : body.get("operatorRole"));
        String operatorName = str(body == null ? null : body.get("operatorName"));
        String customerName = str(body == null ? null : body.get("customerName"));
        return ResponseEntity.ok(invoiceService.issueInvoice(demandId, operatorRole, operatorName, customerName));
    }

    /** 红字作废：body 里 operatorRole 必须是 FINANCE，reason 作废原因必填 */
    @PostMapping("/{id}/void")
    public ResponseEntity<Invoice> voidInvoice(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String operatorRole = str(body == null ? null : body.get("operatorRole"));
        String reason = str(body == null ? null : body.get("reason"));
        return ResponseEntity.ok(invoiceService.voidInvoice(id, operatorRole, reason));
    }

    private String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
