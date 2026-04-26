package com.sentinel.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RCAResponse {
    private String issue;
    private String rootCause;
    private String impactedService;
    private String recommendedFix;
}
