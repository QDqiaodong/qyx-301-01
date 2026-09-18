package com.example.salon.controller;

import com.example.salon.dto.InvoiceVoidRequest;
import com.example.salon.entity.Invoice;
import com.example.salon.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 结算发票（财务专用）：
 *   POST /api/finance/invoices/demand/{demandId}/issue  按押金结清状态开票（票面金额由流水核算）
 *   POST /api/finance/invoices/{id}/void               旧票作废成红字（必填原因）
 *   GET  /api/finance/invoices                         发票台账
 *   GET  /api/demand/{id}/invoices                     某条需求的全部发票（含红字作废票）
 * 开票/作废接口校验 X-Operator-Role 请求头：只有 FINANCE（财务）放行，其它角色 403。
 */
@RestController
@RequestMapping("/api/finance/invoices")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InvoiceController {

    public static final String HEADER_ROLE = "X-Operator-Role";
    public static final String HEADER_OPERATOR = "X-Operator-Name";

    private final InvoiceService invoiceService;

    /** 发票台账：有效票与红字作废票全部留痕，财务逐笔可见 */
    @GetMapping
    public ResponseEntity<List<Invoice>> listInvoices() {
        return ResponseEntity.ok(invoiceService.listAllInvoices());
    }

    /** 某条需求的全部发票（有效票 + 红字作废票） */
    @GetMapping("/demand/{demandId}")
    public ResponseEntity<List<Invoice>> listDemandInvoices(@PathVariable Long demandId) {
        return ResponseEntity.ok(invoiceService.listInvoicesByDemand(demandId));
    }

    /**
     * 开具结算发票：票面金额、客户名全部由后端按押金流水/押金账户核算，
     * 不接收任何手填金额与客户名；没结清、已有有效票、非财务角色一律失败。
     */
    @PostMapping("/demand/{demandId}/issue")
    public ResponseEntity<Invoice> issue(@PathVariable Long demandId,
                                         @RequestHeader(value = HEADER_ROLE, required = false) String operatorRole,
                                         @RequestHeader(value = HEADER_OPERATOR, required = false) String headerOperator,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String operatorName = body != null && body.get("operatorName") != null
                ? String.valueOf(body.get("operatorName")) : headerOperator;
        return ResponseEntity.ok(invoiceService.issue(demandId, operatorRole, operatorName));
    }

    /** 旧票作废成红字：只有财务能操作，必须填写作废原因；作废后旧票号不再有效，可重新开新票 */
    @PostMapping("/{id}/void")
    public ResponseEntity<Invoice> voidInvoice(@PathVariable Long id,
                                               @RequestHeader(value = HEADER_ROLE, required = false) String operatorRole,
                                               @RequestHeader(value = HEADER_OPERATOR, required = false) String headerOperator,
                                               @RequestBody(required = false) InvoiceVoidRequest request) {
        InvoiceVoidRequest body = request == null ? new InvoiceVoidRequest() : request;
        String operatorName = body.getOperatorName() != null ? body.getOperatorName() : headerOperator;
        return ResponseEntity.ok(invoiceService.voidInvoice(
                id, body.getVoidReason(), operatorRole, operatorName));
    }
}
