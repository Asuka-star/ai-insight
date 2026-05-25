package com.aiinsight.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SearchResult {

    private String title;
    private String url;
    private String snippet;
    private String query;
    private int rank;

    public SearchResult(String title, String url, String snippet, String query, int rank) {
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.query = query;
        this.rank = rank;
    }
}
