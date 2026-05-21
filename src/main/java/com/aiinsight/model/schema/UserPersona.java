package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// UserPersona 描述目标用户画像，让报告不只停留在功能罗列。
public class UserPersona {

    private String name;
    private String segment;
    private String companySize;
    private List<String> jobsToBeDone = new ArrayList<>();
    private List<String> painPoints = new ArrayList<>();
    private List<String> buyingConcerns = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
}
