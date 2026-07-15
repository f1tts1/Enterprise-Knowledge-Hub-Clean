-- Add immutable indexing attempts and deletion fencing.
--
-- The V1/V2 application creates exactly one indexing_task row per document and
-- reuses it for retries. This migration therefore recovers the current attempt
-- number from retry_count, then switches future retries to append-only task rows.

USE enterprise_knowledge_hub;

-- The pre-V3 application creates exactly one task row per document. Stop before
-- any DDL if the database violates that invariant, because guessing which row is
-- current would make the pointer backfill and attempt history non-deterministic.
CREATE TEMPORARY TABLE v3_indexing_task_preflight (
  document_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (document_id)
);

-- A duplicate document_id raises a duplicate-key error in this session-local
-- table and stops the mysql client before any persistent ALTER TABLE runs.
INSERT INTO v3_indexing_task_preflight (document_id)
SELECT document_id
FROM indexing_task;

DROP TEMPORARY TABLE v3_indexing_task_preflight;

ALTER TABLE indexing_task
  ADD COLUMN attempt_no INT UNSIGNED NOT NULL DEFAULT 0
    COMMENT '0-based immutable indexing attempt number' AFTER status,
  ADD COLUMN trigger_type VARCHAR(32) NOT NULL DEFAULT 'UPLOAD'
    COMMENT 'UPLOAD, MANUAL_RETRY' AFTER attempt_no,
  ADD COLUMN failure_stage VARCHAR(32) NULL
    COMMENT 'PRECONDITION, AI_PIPELINE, TIMEOUT' AFTER max_retry,
  ADD COLUMN retryable TINYINT(1) NULL
    COMMENT 'Whether the failure is known to be retryable; NULL means unknown' AFTER failure_stage,
  ADD COLUMN last_publish_attempt_at DATETIME(3) NULL
    COMMENT 'Last attempt to publish this pending task to RabbitMQ' AFTER retryable,
  ADD UNIQUE KEY uk_task_document_attempt (document_id, attempt_no),
  ADD KEY idx_task_pending_publish (status, last_publish_attempt_at);

ALTER TABLE document
  ADD COLUMN current_indexing_task_id BIGINT UNSIGNED NULL
    COMMENT 'Current indexing attempt; used as a fencing token for late workers' AFTER index_status,
  ADD COLUMN delete_generation INT UNSIGNED NOT NULL DEFAULT 0
    COMMENT 'Monotonic fencing token for document deletion attempts' AFTER current_indexing_task_id,
  ADD KEY idx_doc_current_indexing_task_id (current_indexing_task_id);

-- Older builds reused one row for every manual retry, so retry_count is the only
-- recoverable execution number for existing data.
UPDATE indexing_task
SET attempt_no = retry_count,
    trigger_type = CASE WHEN retry_count = 0 THEN 'UPLOAD' ELSE 'MANUAL_RETRY' END,
    updated_at = updated_at;

UPDATE document d
JOIN indexing_task t ON t.document_id = d.id
SET d.current_indexing_task_id = t.id,
    d.updated_at = d.updated_at
WHERE d.current_indexing_task_id IS NULL;

-- The old timeout scheduler updated document and task in two independent
-- statements. Reconcile the two partial states so the new current-attempt
-- scheduler does not scan the same legacy RUNNING row forever.
UPDATE indexing_task t
JOIN document d ON d.current_indexing_task_id = t.id
SET t.status = 'FAILED',
    t.failure_stage = 'TIMEOUT',
    t.retryable = 1,
    t.error_message = COALESCE(t.error_message, d.error_message, 'Legacy indexing timeout reconciliation'),
    t.finished_at = COALESCE(t.finished_at, d.updated_at),
    t.updated_at = t.updated_at
WHERE t.status = 'RUNNING'
  AND d.index_status = 'INDEX_FAILED';

UPDATE document d
JOIN indexing_task t ON d.current_indexing_task_id = t.id
SET d.index_status = 'INDEX_FAILED',
    d.error_message = COALESCE(d.error_message, t.error_message, 'Legacy indexing failure reconciliation'),
    d.updated_at = d.updated_at
WHERE d.index_status = 'INDEXING'
  AND t.status = 'FAILED';
