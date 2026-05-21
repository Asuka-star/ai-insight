package com.aiinsight.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// PricingModel 描述竞品定价策略；证据不足时也要显式标记，避免生成臆测结论。
public class PricingModel {

    private boolean hasFreePlan;
    private String strategySummary;
    private List<PricingPlan> plans = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
}
