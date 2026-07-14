package com.example.ekb.rag.dto;

import com.example.ekb.retrieval.dto.RetrievalSearchItem;

public record RagCitation(
        Integer index,
        Long docId,
        String chunkId,
        String fileName,
        Double score,
        Integer pageNo,
        Integer chunkIndex,
        Integer charStart,
        Integer charEnd,
        String text
) {

    public static RagCitation from(int index, RetrievalSearchItem item) {
        return new RagCitation(
                index,
                item.docId(),
                item.chunkId(),
                item.fileName(),
                item.score(),
                item.pageNo(),
                item.chunkIndex(),
                item.charStart(),
                item.charEnd(),
                item.text()
        );
    }
}
