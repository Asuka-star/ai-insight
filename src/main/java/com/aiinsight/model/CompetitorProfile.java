package com.aiinsight.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// CompetitorProfile 是单个竞品的统一画像，Extractor Agent 会把原始资料抽取成这个结构。
public class CompetitorProfile {

    private String productName;
    private String companyName;
    private String positioning;
    private List<String> targetUsers = new ArrayList<>();
    private FeatureTree featureTree = new FeatureTree();
    private PricingModel pricingModel = new PricingModel();
    private List<UserPersona> personas = new ArrayList<>();
    private List<String> strengths = new ArrayList<>();
    private List<String> weaknesses = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
}
