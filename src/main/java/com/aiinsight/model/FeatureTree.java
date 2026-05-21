package com.aiinsight.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// FeatureTree 用树形结构表达功能体系，方便做横向功能对比和缺口分析。
public class FeatureTree {

    private String productName;
    private List<FeatureNode> roots = new ArrayList<>();
}
