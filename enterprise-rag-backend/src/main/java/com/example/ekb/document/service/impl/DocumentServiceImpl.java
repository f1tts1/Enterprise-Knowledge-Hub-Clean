package com.example.ekb.document.service.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.ekb.ai.client.AiDocumentVectorDeleteClient;
import com.example.ekb.ai.dto.AiDocumentVectorDeleteRequest;
import com.example.ekb.ai.dto.AiDocumentVectorDeleteResponse;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.common.constants.IndexingTaskTriggerType;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.common.response.PageResponse;
import com.example.ekb.document.dto.DocumentIndexStatusResponse;
import com.example.ekb.document.dto.DocumentResponse;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.document.service.DocumentService;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import com.example.ekb.indexing.service.IndexingService;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.storage.StorageService;
import com.example.ekb.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    // V1 文件校验刻意保持小范围：只允许 Python AI 服务已经能解析的文档格式。
    // 后续新增格式时，必须先在 AI 服务侧实现对应 loader。
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md", "markdown");
    private static final String DOCUMENT_DELETE_BUSY_MESSAGE = "Document indexing or deletion is already in progress";
    private static final int DEFAULT_CHUNK_COUNT = 0;
    private static final int DEFAULT_ATTEMPT_NO = 0;
    private static final int DEFAULT_RETRY_COUNT = 0;
    private static final int DEFAULT_MAX_RETRY = 3;
    private static final int NOT_DELETED = 0;
    private static final int DELETED = 1;

    private final DocumentMapper documentMapper;
    private final IndexingTaskMapper indexingTaskMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final StorageService storageService;
    private final IndexingService indexingService;
    private final AiDocumentVectorDeleteClient aiDocumentVectorDeleteClient;

    public DocumentServiceImpl(
            DocumentMapper documentMapper,
            IndexingTaskMapper indexingTaskMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            StorageService storageService,
            IndexingService indexingService,
            AiDocumentVectorDeleteClient aiDocumentVectorDeleteClient
    ) {
        this.documentMapper = documentMapper;
        this.indexingTaskMapper = indexingTaskMapper;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.storageService = storageService;
        this.indexingService = indexingService;
        this.aiDocumentVectorDeleteClient = aiDocumentVectorDeleteClient;
    }

    @Override
    @Transactional
    public DocumentResponse upload(Long currentUserId, Long knowledgeBaseId, MultipartFile file) {
        // 先校验知识库归属，再写对象存储，避免攻击者借用不属于自己的知识库
        // 向 MinIO 写入文件。
        // 上传和知识库删除必须锁定同一条 knowledge_base 记录，避免“删除检查完无文档”
        // 与“上传即将插入文档”交错执行，留下挂在已删除知识库下的活动文档。
        knowledgeBaseAccessService.requireOwnedForWrite(currentUserId, knowledgeBaseId);
        validateFile(file);

        String fileName = cleanFileName(file.getOriginalFilename());
        // 这里一次性读取文件，因为既要计算 checksum，又要构造 MinIO InputStream。
        // V1 限制 20MB，所以这种做法可接受。
        byte[] fileBytes = readFileBytes(file);
        String checksum = sha256(fileBytes);

        // 同一知识库内按文件内容去重，而不是按文件名去重。
        // 用户可能用不同文件名上传同一份内容，checksum 更可靠。
        if (existsActiveDocumentByChecksum(knowledgeBaseId, checksum)) {
            throw new BusinessException(ErrorCode.DOCUMENT_ALREADY_EXISTS);
        }

        String contentType = resolveContentType(file, fileName);
        String objectKey = buildObjectKey(currentUserId, knowledgeBaseId, fileName);
        StoredObject storedObject = null;

        try {
            // 先让 MySQL 唯一索引抢占 active checksum，再写 MinIO。
            // 这样同一知识库内并发上传同内容文件时，失败请求会在写对象前被拦住，
            // 不会因为两个 UUID objectKey 都先落到 MinIO 而留下重复对象。
            Document document = buildDocument(
                    currentUserId,
                    knowledgeBaseId,
                    fileName,
                    storageService.bucketName(),
                    objectKey,
                    fileBytes.length,
                    contentType,
                    checksum
            );
            documentMapper.insert(document);

            // document 行仍处于当前事务内，RabbitMQ 消费者只能在事务提交后的消息里看到它。
            // 如果 MinIO 写入失败，事务会回滚，业务表不会留下指向缺失 object 的记录。
            storedObject = storageService.putObject(
                    objectKey,
                    new ByteArrayInputStream(fileBytes),
                    fileBytes.length,
                    contentType
            );
            registerRollbackObjectCleanup(storedObject);

            IndexingTask indexingTask = buildIndexingTask(document);
            indexingTaskMapper.insert(indexingTask);
            linkCurrentIndexingTask(document, indexingTask);
            // 上传接口只保证文件和任务已经落到 MySQL/MinIO，不同步等待索引。
            // IndexingService 会等当前 MySQL 事务提交后再投递 RabbitMQ；
            // 如果 RabbitMQ 暂时不可用，任务仍保持 PENDING，后续通过简单重试再投递。
            indexingService.requestIndexingAfterUpload(document.getId(), indexingTask.getId());

            return DocumentResponse.from(document, indexingTask.getId());
        } catch (DuplicateKeyException ex) {
            // 即使前面已经检查重复，唯一索引仍然是并发场景下的最终防线。
            // 当前对象写入发生在 document insert 之后；同内容并发冲突通常不会写出 MinIO object。
            // 如果异常发生在对象已写入之后，仍然尽力清理。
            cleanupUploadedObject(storedObject);
            throw new BusinessException(ErrorCode.DOCUMENT_ALREADY_EXISTS);
        } catch (RuntimeException ex) {
            // 如果数据库写入等上传主流程失败，尽力删除刚上传的 object，
            // 避免 MinIO 积累孤儿文件。
            cleanupUploadedObject(storedObject);
            throw ex;
        }
    }

    @Override
    public PageResponse<DocumentResponse> list(Long currentUserId, Long knowledgeBaseId, long page, long size) {
        // 文档列表也必须受知识库归属约束，避免用户探测其他账号的文档数量。
        ensureOwnedKnowledgeBase(currentUserId, knowledgeBaseId);

        LambdaQueryWrapper<Document> query = baseDocumentQuery(currentUserId)
                .eq(Document::getKbId, knowledgeBaseId);
        long total = documentMapper.selectCount(query);
        long offset = (page - 1) * size;

        List<Document> documents = documentMapper.selectList(baseDocumentQuery(currentUserId)
                .eq(Document::getKbId, knowledgeBaseId)
                .orderByDesc(Document::getCreatedAt)
                .orderByDesc(Document::getId)
                .last("LIMIT " + size + " OFFSET " + offset));

        return PageResponse.of(
                documents.stream().map(DocumentResponse::from).toList(),
                page,
                size,
                total
        );
    }

    @Override
    public DocumentResponse getDetail(Long currentUserId, Long documentId) {
        return DocumentResponse.from(getOwnedDocument(currentUserId, documentId));
    }

    @Override
    public DocumentIndexStatusResponse getIndexStatus(Long currentUserId, Long documentId) {
        Document document = getOwnedDocument(currentUserId, documentId);
        return DocumentIndexStatusResponse.from(document, getCurrentIndexingTask(document));
    }

    @Override
    @Transactional
    public DocumentIndexStatusResponse retryIndex(Long currentUserId, Long documentId) {
        Document document = getOwnedDocument(currentUserId, documentId);
        IndexingTask indexingTask = getCurrentIndexingTask(document);
        if (indexingTask == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Indexing task is missing");
        }

        if (DocumentIndexStatus.PENDING_INDEX.equals(document.getIndexStatus())) {
            if (!IndexingTaskStatus.PENDING.equals(indexingTask.getStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Pending document does not have a pending indexing task");
            }

            // RabbitMQ 发布失败或应用在事务提交后崩溃时，MySQL 会留下
            // document=PENDING_INDEX、task=PENDING。这个状态还没有真正执行索引，
            // 因此这里不增加 retry_count，只做一次显式重投，方便人工恢复。
            indexingService.requestIndexingAfterUpload(document.getId(), indexingTask.getId());
            return DocumentIndexStatusResponse.from(document, indexingTask);
        }

        if (DocumentIndexStatus.INDEXING.equals(document.getIndexStatus())) {
            throw new BusinessException(ErrorCode.DOCUMENT_BUSY, DOCUMENT_DELETE_BUSY_MESSAGE);
        }

        if (!DocumentIndexStatus.INDEX_FAILED.equals(document.getIndexStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only INDEX_FAILED or PENDING_INDEX documents can retry indexing");
        }

        if (!IndexingTaskStatus.FAILED.equals(indexingTask.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only FAILED indexing tasks can be retried");
        }

        int nextRetryCount = safeRetryCount(indexingTask) + 1;
        if (nextRetryCount > safeMaxRetry(indexingTask)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Maximum indexing retries reached");
        }

        // 每次真正失败后的重试都创建不可变 attempt。旧 worker 即使迟到，也只能更新旧 task；
        // document.current_indexing_task_id 是跨线程状态写入的 fencing token。
        IndexingTask retryTask = buildRetryIndexingTask(document, indexingTask, nextRetryCount);
        try {
            indexingTaskMapper.insert(retryTask);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "A newer indexing attempt already exists");
        }

        int documentUpdated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getOwnerUserId, currentUserId)
                .eq(Document::getIsDeleted, NOT_DELETED)
                .eq(Document::getIndexStatus, DocumentIndexStatus.INDEX_FAILED)
                .eq(Document::getCurrentIndexingTaskId, indexingTask.getId())
                .set(Document::getIndexStatus, DocumentIndexStatus.PENDING_INDEX)
                .set(Document::getCurrentIndexingTaskId, retryTask.getId())
                .set(Document::getChunkCount, DEFAULT_CHUNK_COUNT)
                .set(Document::getErrorMessage, null));
        if (documentUpdated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Document status changed before retrying index");
        }

        indexingService.requestIndexingAfterUpload(document.getId(), retryTask.getId());

        document.setIndexStatus(DocumentIndexStatus.PENDING_INDEX);
        document.setCurrentIndexingTaskId(retryTask.getId());
        document.setChunkCount(DEFAULT_CHUNK_COUNT);
        document.setErrorMessage(null);
        return DocumentIndexStatusResponse.from(document, retryTask);
    }

    @Override
    public void delete(Long currentUserId, Long documentId) {
        Document document = getOwnedDocumentForDelete(currentUserId, documentId);
        if (DocumentIndexStatus.DELETED.equals(document.getIndexStatus())) {
            // 删除接口按幂等语义处理：业务记录已经处于 DELETED 时再次删除直接成功。
            // 外部 MinIO/Qdrant 即使已经不存在，也不需要再把这次请求变成失败。
            return;
        }
        if (isDeleteBusy(document.getIndexStatus())) {
            // V1 明确不支持“同一文档一边索引一边删除”。这里直接 409，
            // 让业务状态保持简单，也避免索引线程和删除线程互相覆盖 MySQL 状态。
            throw new BusinessException(ErrorCode.DOCUMENT_BUSY, DOCUMENT_DELETE_BUSY_MESSAGE);
        }
        if (!isDeleteStartable(document.getIndexStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Document is not in a deletable state");
        }

        Integer deleteGeneration = markDeleting(document);
        if (deleteGeneration == null) {
            Document latest = getOwnedDocumentForDelete(currentUserId, documentId);
            if (DocumentIndexStatus.DELETED.equals(latest.getIndexStatus())) {
                return;
            }
            if (isDeleteBusy(latest.getIndexStatus())) {
                throw new BusinessException(ErrorCode.DOCUMENT_BUSY, DOCUMENT_DELETE_BUSY_MESSAGE);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "Document status changed during delete");
        }

        try {
            deleteDocumentVectors(document);
            deleteDocumentObject(document);
            markDeleted(document, deleteGeneration);
        } catch (BusinessException ex) {
            markDeleteFailed(document, deleteGeneration, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            String errorMessage = "Failed to delete document: " + readableMessage(ex);
            markDeleteFailed(document, deleteGeneration, errorMessage);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage);
        }
    }

    private Integer markDeleting(Document document) {
        // MySQL 是唯一业务事实来源。删除开始时先把 document 改成 DELETING，
        // 并立刻设置 is_deleted=1：列表、详情、检索二次过滤都会把它视为不可见。
        // 这里不再更新 indexing_task；V1 通过 409 直接禁止索引中删除。
        int currentGeneration = safeDeleteGeneration(document);
        int nextGeneration = currentGeneration + 1;
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getOwnerUserId, document.getOwnerUserId())
                .eq(Document::getDeleteGeneration, currentGeneration)
                .eq(Document::getIndexStatus, document.getIndexStatus())
                .in(Document::getIndexStatus, deleteStartStatuses())
                .set(Document::getIsDeleted, DELETED)
                .set(Document::getIndexStatus, DocumentIndexStatus.DELETING)
                .set(Document::getDeleteGeneration, nextGeneration)
                .set(Document::getErrorMessage, null));
        return updated > 0 ? nextGeneration : null;
    }

    private void deleteDocumentVectors(Document document) {
        try {
            AiDocumentVectorDeleteResponse response = aiDocumentVectorDeleteClient.deleteDocumentVectors(
                    new AiDocumentVectorDeleteRequest(
                            document.getId(),
                            document.getKbId(),
                            document.getOwnerUserId()
                    )
            );
            if (response == null
                    || !Objects.equals(document.getId(), response.documentId())
                    || !"DELETED".equals(response.status())) {
                throw new IllegalStateException("AI service returned an invalid document deletion result");
            }
            log.info("Deleted Qdrant vectors for document delete, documentId={}, kbId={}, ownerUserId={}, status={}, collection={}, collectionExisted={}",
                    document.getId(),
                    document.getKbId(),
                    document.getOwnerUserId(),
                    response == null ? null : response.status(),
                    response == null ? null : response.vectorCollection(),
                    response == null ? null : response.collectionExisted());
        } catch (RuntimeException ex) {
            // Qdrant 不参与 MySQL 事务。失败时只把业务状态推进到 DELETE_FAILED，
            // 用户或后续人工重试再次调用删除即可；point id/filter 都是幂等的。
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "Failed to delete Qdrant vectors: " + readableMessage(ex)
            );
        }
    }

    private void deleteDocumentObject(Document document) {
        try {
            storageService.removeObject(document.getObjectKey());
        } catch (RuntimeException ex) {
            // MinIO 删除同样按最终一致性处理。对象不存在由 StorageService 视为成功；
            // 这里捕获的是网络、认证、服务不可用等真正需要重试的失败。
            throw new BusinessException(
                    ErrorCode.STORAGE_OPERATION_FAILED,
                    "Failed to delete MinIO object: " + readableMessage(ex)
            );
        }
    }

    private void markDeleted(Document document, int deleteGeneration) {
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getOwnerUserId, document.getOwnerUserId())
                .eq(Document::getIndexStatus, DocumentIndexStatus.DELETING)
                .eq(Document::getDeleteGeneration, deleteGeneration)
                .set(Document::getIsDeleted, DELETED)
                .set(Document::getIndexStatus, DocumentIndexStatus.DELETED)
                .set(Document::getErrorMessage, null));
        if (updated == 0) {
            log.info("Ignored stale document deletion success, documentId={}, deleteGeneration={}",
                    document.getId(), deleteGeneration);
            throw new BusinessException(ErrorCode.CONFLICT, "Document deletion attempt is no longer current");
        }
    }

    private void markDeleteFailed(Document document, int deleteGeneration, String errorMessage) {
        // 删除失败仍保持 is_deleted=1，保证文档不会重新出现在列表或检索结果里。
        // error_message 记录最后一次失败原因，后续再次 DELETE 同一个 documentId 即可重试。
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getOwnerUserId, document.getOwnerUserId())
                .eq(Document::getIndexStatus, DocumentIndexStatus.DELETING)
                .eq(Document::getDeleteGeneration, deleteGeneration)
                .set(Document::getIsDeleted, DELETED)
                .set(Document::getIndexStatus, DocumentIndexStatus.DELETE_FAILED)
                .set(Document::getErrorMessage, truncateErrorMessage(errorMessage)));
        if (updated == 0) {
            log.info("Ignored stale document deletion failure, documentId={}, deleteGeneration={}",
                    document.getId(), deleteGeneration);
        }
    }

    private boolean isDeleteBusy(String indexStatus) {
        return deleteBusyStatuses().contains(indexStatus);
    }

    private boolean isDeleteStartable(String indexStatus) {
        return deleteStartStatuses().contains(indexStatus);
    }

    private Set<String> deleteBusyStatuses() {
        // V1 不支持同一文档同时执行索引和删除。
        // 只要文档还在等待索引或正在索引，就直接让 DELETE 返回 409，
        // 避免删除线程与索引线程同时改写 document/indexing_task 状态。
        return Set.of(
                DocumentIndexStatus.PENDING_INDEX,
                DocumentIndexStatus.INDEXING,
                DocumentIndexStatus.DELETING
        );
    }

    private Set<String> deleteStartStatuses() {
        return Set.of(
                DocumentIndexStatus.INDEXED,
                DocumentIndexStatus.INDEX_FAILED,
                DocumentIndexStatus.DELETE_FAILED
        );
    }

    private Document buildDocument(
            Long currentUserId,
            Long knowledgeBaseId,
            String fileName,
            String bucket,
            String objectKey,
            long fileSize,
            String contentType,
            String checksum
    ) {
        Document document = new Document();
        document.setKbId(knowledgeBaseId);
        document.setOwnerUserId(currentUserId);
        document.setFileName(fileName);
        document.setContentType(contentType);
        document.setFileSize(fileSize);
        document.setBucket(bucket);
        document.setObjectKey(objectKey);
        document.setChecksumSha256(checksum);
        // 文档已保存，等待异步索引任务消费。这里不表示索引已经开始，
        // 删除接口会把 PENDING_INDEX 视为忙碌并返回 409，避免 V1 出现边索引边删除。
        document.setIndexStatus(DocumentIndexStatus.PENDING_INDEX);
        document.setChunkCount(DEFAULT_CHUNK_COUNT);
        document.setIsDeleted(NOT_DELETED);
        return document;
    }

    private IndexingTask buildIndexingTask(Document document) {
        IndexingTask indexingTask = new IndexingTask();
        indexingTask.setDocumentId(document.getId());
        indexingTask.setKbId(document.getKbId());
        indexingTask.setOwnerUserId(document.getOwnerUserId());
        indexingTask.setStatus(IndexingTaskStatus.PENDING);
        indexingTask.setAttemptNo(DEFAULT_ATTEMPT_NO);
        indexingTask.setTriggerType(IndexingTaskTriggerType.UPLOAD);
        indexingTask.setRetryCount(DEFAULT_RETRY_COUNT);
        indexingTask.setMaxRetry(DEFAULT_MAX_RETRY);
        return indexingTask;
    }

    private IndexingTask buildRetryIndexingTask(
            Document document,
            IndexingTask previousTask,
            int nextRetryCount
    ) {
        IndexingTask indexingTask = new IndexingTask();
        indexingTask.setDocumentId(document.getId());
        indexingTask.setKbId(document.getKbId());
        indexingTask.setOwnerUserId(document.getOwnerUserId());
        indexingTask.setStatus(IndexingTaskStatus.PENDING);
        indexingTask.setAttemptNo(safeAttemptNo(previousTask) + 1);
        indexingTask.setTriggerType(IndexingTaskTriggerType.MANUAL_RETRY);
        indexingTask.setRetryCount(nextRetryCount);
        indexingTask.setMaxRetry(safeMaxRetry(previousTask));
        return indexingTask;
    }

    private void linkCurrentIndexingTask(Document document, IndexingTask indexingTask) {
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getOwnerUserId, document.getOwnerUserId())
                .eq(Document::getIndexStatus, DocumentIndexStatus.PENDING_INDEX)
                .isNull(Document::getCurrentIndexingTaskId)
                .set(Document::getCurrentIndexingTaskId, indexingTask.getId()));
        if (updated == 0) {
            throw new IllegalStateException("Failed to link the initial indexing attempt");
        }
        document.setCurrentIndexingTaskId(indexingTask.getId());
    }

    private void ensureOwnedKnowledgeBase(Long currentUserId, Long knowledgeBaseId) {
        // V1 使用简单的 owner 权限模型。这里返回 NOT_FOUND 而不是 FORBIDDEN，
        // 避免泄露其他用户的知识库是否存在。
        knowledgeBaseAccessService.requireOwned(currentUserId, knowledgeBaseId);
    }

    private Document getOwnedDocument(Long currentUserId, Long documentId) {
        Document document = documentMapper.selectOne(baseDocumentQuery(currentUserId)
                .eq(Document::getId, documentId)
                .last("LIMIT 1"));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    private Document getOwnedDocumentForDelete(Long currentUserId, Long documentId) {
        // 删除重试不能复用 baseDocumentQuery：DELETING/DELETE_FAILED 已经设置
        // is_deleted=1，对普通业务读取不可见，但同一个 owner 仍应能再次调用 DELETE 完成清理。
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getOwnerUserId, currentUserId)
                .eq(Document::getId, documentId)
                .last("LIMIT 1"));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    private LambdaQueryWrapper<Document> baseDocumentQuery(Long currentUserId) {
        // 所有文档查询都从 owner + 未删除过滤开始。
        // 这是后续扩展团队/RBAC 前的第一层真实权限边界。
        return new LambdaQueryWrapper<Document>()
                .eq(Document::getOwnerUserId, currentUserId)
                .eq(Document::getIsDeleted, NOT_DELETED);
    }

    private boolean existsActiveDocumentByChecksum(Long knowledgeBaseId, String checksum) {
        Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, knowledgeBaseId)
                .eq(Document::getChecksumSha256, checksum)
                .eq(Document::getIsDeleted, NOT_DELETED));
        return count != null && count > 0;
    }

    private IndexingTask getCurrentIndexingTask(Document document) {
        if (document.getCurrentIndexingTaskId() == null) {
            return null;
        }
        IndexingTask task = indexingTaskMapper.selectById(document.getCurrentIndexingTaskId());
        if (task == null || !Objects.equals(document.getId(), task.getDocumentId())) {
            return null;
        }
        return task;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.DOCUMENT_FILE_TOO_LARGE);
        }

        String extension = fileExtension(cleanFileName(file.getOriginalFilename()));
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(
                    ErrorCode.DOCUMENT_TYPE_NOT_SUPPORTED,
                    "Supported document types: pdf, docx, txt, md, markdown"
            );
        }
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Failed to read uploaded file");
        }
    }

    private String cleanFileName(String originalFileName) {
        String fileName = StringUtils.hasText(originalFileName)
                ? StringUtils.cleanPath(originalFileName)
                : "document";
        if (fileName.contains("..")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid file name");
        }

        fileName = fileName.replace('\\', '_').replace('/', '_').trim();
        if (!StringUtils.hasText(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid file name");
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            fileName = fileName.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return fileName;
    }

    private String fileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    private String resolveContentType(MultipartFile file, String fileName) {
        if (StringUtils.hasText(file.getContentType())) {
            return file.getContentType();
        }

        return switch (fileExtension(fileName)) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }

    private String buildObjectKey(Long currentUserId, Long knowledgeBaseId, String fileName) {
        String extension = fileExtension(fileName);
        String storageFileName = StringUtils.hasText(extension)
                ? UUID.randomUUID() + "." + extension
                : UUID.randomUUID().toString();
        // objectKey 包含 owner 和 kb id，方便在 MinIO 控制台人工排查。
        // 真正的权限仍由 MySQL 里的 owner 校验保证，不能只依赖路径。
        return "users/%d/knowledge-bases/%d/documents/%s".formatted(
                currentUserId,
                knowledgeBaseId,
                storageFileName
        );
    }

    private String sha256(byte[] fileBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(fileBytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 algorithm is unavailable");
        }
    }

    private String readableMessage(Throwable ex) {
        if (StringUtils.hasText(ex.getMessage())) {
            return truncateErrorMessage(ex.getMessage());
        }
        return ex.getClass().getSimpleName();
    }

    private int safeRetryCount(IndexingTask indexingTask) {
        return indexingTask.getRetryCount() == null ? 0 : indexingTask.getRetryCount();
    }

    private int safeAttemptNo(IndexingTask indexingTask) {
        return indexingTask.getAttemptNo() == null ? 0 : indexingTask.getAttemptNo();
    }

    private int safeMaxRetry(IndexingTask indexingTask) {
        return indexingTask.getMaxRetry() == null ? DEFAULT_MAX_RETRY : indexingTask.getMaxRetry();
    }

    private int safeDeleteGeneration(Document document) {
        return document.getDeleteGeneration() == null ? 0 : document.getDeleteGeneration();
    }

    private String truncateErrorMessage(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return null;
        }
        String compactMessage = errorMessage.trim();
        if (compactMessage.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return compactMessage;
        }
        return compactMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private void cleanupUploadedObject(StoredObject storedObject) {
        if (storedObject == null) {
            return;
        }
        try {
            storageService.removeObject(storedObject.objectKey());
        } catch (RuntimeException cleanupEx) {
            log.warn("Failed to clean up uploaded object after document upload failure, objectKey={}",
                    storedObject.objectKey(), cleanupEx);
        }
    }

    private void registerRollbackObjectCleanup(StoredObject storedObject) {
        if (storedObject == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 只在数据库明确回滚时清理。STATUS_UNKNOWN 可能表示 commit 已在服务端成功、
                // 但客户端没收到结果；此时删除 object 反而会制造已提交行指向缺失文件。
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    cleanupUploadedObject(storedObject);
                } else if (status == TransactionSynchronization.STATUS_UNKNOWN) {
                    log.warn("Document upload transaction outcome is unknown; keep object for reconciliation, objectKey={}",
                            storedObject.objectKey());
                }
            }
        });
    }
}
