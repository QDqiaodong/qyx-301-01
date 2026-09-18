package com.example.salon.controller;

import com.example.salon.entity.CustomerAccount;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.service.DepositService;
import com.example.salon.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 押金财务：财务查看每个客户的押金账户余额，以及每一笔冻结、退回、没收、充值流水。
 * 退回比例在后端按财务规则分档固化，本控制器不提供任何修改比例的入口。
 */
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DepositController {

    private final DepositService depositService;
    private final InvoiceService invoiceService;

    /** 全部客户押金账户（可用余额 / 已冻结余额） */
    @GetMapping("/accounts")
    public ResponseEntity<List<CustomerAccount>> listAccounts() {
        return ResponseEntity.ok(depositService.listAccounts());
    }

    /** 押金流水台账：每一笔冻结、退回、没收、充值，财务逐笔可见；关联的当前有效发票票号/金额一并挂出 */
    @GetMapping("/deposit-transactions")
    public ResponseEntity<List<DepositTransaction>> listTransactions() {
        List<DepositTransaction> txs = depositService.listAllTransactions();
        invoiceService.attachActiveInvoicesToTransactions(txs);
        return ResponseEntity.ok(txs);
    }

    /** 押金充值入账（财务操作） */
    @PostMapping("/accounts/{id}/recharge")
    public ResponseEntity<CustomerAccount> recharge(@PathVariable Long id,
                                                    @RequestBody Map<String, Object> body) {
        BigDecimal amount = parseAmount(body == null ? null : body.get("amount"));
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        return ResponseEntity.ok(depositService.recharge(id, amount, note));
    }

    private BigDecimal parseAmount(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
