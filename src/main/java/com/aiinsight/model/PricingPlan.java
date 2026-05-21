package com.aiinsight.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// PricingPlan 表示一个具体价格档位，用于从价格页抽取结构化套餐信息。
public class PricingPlan {

    private String name;
    private String priceText;
    private String billingCycle;
    private String targetSegment;
    private List<String> includedFeatures = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();

    public PricingPlan(String name,
                       String priceText,
                       String billingCycle,
                       String targetSegment,
                       List<String> includedFeatures,
                       List<String> evidenceIds) {
        this.name = name;
        this.priceText = priceText;
        this.billingCycle = billingCycle;
        this.targetSegment = targetSegment;
        this.includedFeatures = new ArrayList<>(includedFeatures);
        this.evidenceIds = new ArrayList<>(evidenceIds);
    }
}
