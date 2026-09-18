package com.example.salon.dto;

import lombok.Data;

/**
 * 发票作废请求：必须填写作废原因（红字票留痕）。
 */
@Data
public class InvoiceVoidRequest {

    /** 红字作废原因（必填） */
    private String voidReason;

    /** 操作人姓名（可选，用于台账留痕；角色仍以后端校验为准） */
    private String operatorName;
}
