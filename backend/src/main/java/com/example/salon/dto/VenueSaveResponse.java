package com.example.salon.dto;

import com.example.salon.entity.Venue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场地保存结果。若本次停用/涨价/拆设施导致已有锁定自行破裂，
 * breakMessage 会带上破裂原因汇总。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VenueSaveResponse {

    private Venue venue;
    private String breakMessage;
}
