package com.example.salon.dto;

import com.example.salon.entity.RecommendResult;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendResponse {

    private List<RecommendResult> results;
    private Integer matchStatus;
    private String warningMessage;
    private List<String> missingResources;
}
