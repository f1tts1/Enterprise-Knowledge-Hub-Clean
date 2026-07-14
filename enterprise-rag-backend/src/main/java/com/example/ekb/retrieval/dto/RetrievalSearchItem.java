package com.example.ekb.retrieval.dto;

import com.example.ekb.ai.dto.AiRetrievalSearchItem;

public record RetrievalSearchItem(
        String pointId,
        Double score,
        Long docId,
        String chunkId,
        String fileName,
        Integer pageNo,
        Integer chunkIndex,
        Integer charStart,
        Integer charEnd,
        String text
) {

    public static RetrievalSearchItem from(AiRetrievalSearchItem item) {
        return new RetrievalSearchItem(
                item.pointId(),
                item.score(),
                item.docId(),
                item.chunkId(),
                item.fileName(),
                item.pageNo(),
                item.chunkIndex(),
                item.charStart(),
                item.charEnd(),
                item.text()
        );
    }
}
