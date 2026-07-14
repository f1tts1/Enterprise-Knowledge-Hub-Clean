package com.example.ekb.indexing.service;

public interface IndexingService {

    void requestIndexingAfterUpload(Long documentId, Long indexingTaskId);

    void processIndexingTask(Long documentId, Long indexingTaskId);
}
