package com.aiinsight.service;

import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvidenceChunkService {

    private static final int CHUNK_SIZE = 420;
    private static final int CHUNK_OVERLAP = 80;

    public List<EvidenceChunk> chunk(List<EvidenceSource> sources) {
        List<EvidenceChunk> chunks = new ArrayList<>();
        for (EvidenceSource source : sources) {
            chunks.addAll(chunk(source));
        }
        return chunks;
    }

    private List<EvidenceChunk> chunk(EvidenceSource source) {
        List<EvidenceChunk> chunks = new ArrayList<>();
        String text = sourceText(source);
        if (!StringUtils.hasText(text)) {
            return chunks;
        }
        int start = 0;
        int index = 1;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + CHUNK_SIZE);
            String chunkText = text.substring(start, end).trim();
            if (StringUtils.hasText(chunkText)) {
                chunks.add(new EvidenceChunk(
                        source.getCitationKey() + "-C" + index,
                        source.getCitationKey(),
                        index,
                        source.getTitle(),
                        source.getUrl(),
                        chunkText
                ));
                index++;
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(0, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    private String sourceText(EvidenceSource source) {
        String rawText = source.getRawText();
        if (StringUtils.hasText(rawText)) {
            return rawText.replaceAll("\\s+", " ").trim();
        }
        return source.getSnippet() == null ? "" : source.getSnippet().replaceAll("\\s+", " ").trim();
    }
}
