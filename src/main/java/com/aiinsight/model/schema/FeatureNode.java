package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// FeatureNode 表示功能树中的一个节点，每个节点都可以绑定证据来源。
public class FeatureNode {

    private String name;
    private String description;
    private List<FeatureNode> children = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();

    public FeatureNode(String name, String description, List<String> evidenceIds) {
        this.name = name;
        this.description = description;
        this.evidenceIds = new ArrayList<>(evidenceIds);
    }
}
