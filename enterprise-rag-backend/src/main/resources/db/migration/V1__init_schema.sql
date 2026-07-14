-- Enterprise Knowledge Hub V1 schema.
-- Target: MySQL 8.0+ / 9.x.
-- Scope: V1 core business tables only. RBAC and tenant tables are intentionally postponed.
Drop database enterprise_knowledge_hub;

CREATE DATABASE IF NOT EXISTS enterprise_knowledge_hub
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE enterprise_knowledge_hub;

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(64) NULL,
  email VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  KEY idx_user_email (email),
  KEY idx_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Application user';

CREATE TABLE IF NOT EXISTS knowledge_base (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE; future: TEAM, PUBLIC',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, ARCHIVED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kb_owner_name_deleted (owner_user_id, name, is_deleted),
  KEY idx_kb_owner_user_id (owner_user_id),
  KEY idx_kb_status (status),
  CONSTRAINT fk_kb_owner_user FOREIGN KEY (owner_user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Knowledge base';

CREATE TABLE IF NOT EXISTS document (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  kb_id BIGINT UNSIGNED NOT NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NULL,
  file_size BIGINT UNSIGNED NOT NULL DEFAULT 0,
  bucket VARCHAR(128) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  checksum_sha256 CHAR(64) NULL,
  index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_INDEX' COMMENT 'PENDING_INDEX, INDEXING, INDEXED, INDEX_FAILED, DELETING, DELETED, DELETE_FAILED',
  chunk_count INT UNSIGNED NOT NULL DEFAULT 0,
  error_message TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  active_checksum_sha256 CHAR(64)
    GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN checksum_sha256 ELSE NULL END) STORED
    COMMENT 'Only active documents participate in checksum deduplication',
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_kb_active_checksum (kb_id, active_checksum_sha256),
  KEY idx_doc_kb_id (kb_id),
  KEY idx_doc_owner_user_id (owner_user_id),
  KEY idx_doc_index_status (index_status),
  KEY idx_doc_object_key (object_key),
  KEY idx_doc_checksum_sha256 (checksum_sha256),
  CONSTRAINT fk_doc_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
  CONSTRAINT fk_doc_owner_user FOREIGN KEY (owner_user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Uploaded document metadata';

CREATE TABLE IF NOT EXISTS indexing_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  document_id BIGINT UNSIGNED NOT NULL,
  kb_id BIGINT UNSIGNED NOT NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, RUNNING, SUCCESS, FAILED',
  retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  max_retry INT UNSIGNED NOT NULL DEFAULT 3,
  error_message TEXT NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_task_document_id (document_id),
  KEY idx_task_kb_id (kb_id),
  KEY idx_task_owner_user_id (owner_user_id),
  KEY idx_task_status (status),
  KEY idx_task_created_at (created_at),
  CONSTRAINT fk_task_document FOREIGN KEY (document_id) REFERENCES document (id),
  CONSTRAINT fk_task_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
  CONSTRAINT fk_task_owner_user FOREIGN KEY (owner_user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Document indexing task';

CREATE TABLE IF NOT EXISTS conversation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  kb_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(200) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_conversation_user_created (user_id, created_at),
  KEY idx_conversation_kb_id (kb_id),
  CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT fk_conversation_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Chat conversation';

CREATE TABLE IF NOT EXISTS chat_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role VARCHAR(32) NOT NULL COMMENT 'USER, ASSISTANT, SYSTEM',
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS, FAILED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_message_conversation_created (conversation_id, created_at),
  KEY idx_message_user_id (user_id),
  KEY idx_message_role (role),
  CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id),
  CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Chat message';

CREATE TABLE IF NOT EXISTS answer_citation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  assistant_message_id BIGINT UNSIGNED NOT NULL,
  doc_id BIGINT UNSIGNED NOT NULL,
  chunk_id VARCHAR(128) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  page_no INT UNSIGNED NULL,
  chunk_index INT UNSIGNED NOT NULL,
  quote_text TEXT NOT NULL,
  score DOUBLE NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_citation_assistant_message_id (assistant_message_id),
  KEY idx_citation_doc_id (doc_id),
  KEY idx_citation_chunk_id (chunk_id),
  CONSTRAINT fk_citation_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id),
  CONSTRAINT fk_citation_document FOREIGN KEY (doc_id) REFERENCES document (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Answer citation source';

CREATE TABLE IF NOT EXISTS feedback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  assistant_message_id BIGINT UNSIGNED NOT NULL,
  rating VARCHAR(32) NOT NULL COMMENT 'USEFUL, NOT_USEFUL',
  comment VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_feedback_user_message (user_id, assistant_message_id),
  KEY idx_feedback_assistant_message_id (assistant_message_id),
  KEY idx_feedback_user_id (user_id),
  CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT fk_feedback_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Answer feedback';

CREATE TABLE IF NOT EXISTS model_call_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NULL,
  provider VARCHAR(64) NOT NULL,
  model VARCHAR(128) NOT NULL,
  call_type VARCHAR(32) NOT NULL COMMENT 'CHAT, EMBEDDING, RERANK',
  prompt_tokens INT UNSIGNED NOT NULL DEFAULT 0,
  completion_tokens INT UNSIGNED NOT NULL DEFAULT 0,
  latency_ms BIGINT UNSIGNED NULL,
  success TINYINT(1) NOT NULL DEFAULT 1,
  error_message TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_model_call_request_id (request_id),
  KEY idx_model_call_user_id (user_id),
  KEY idx_model_call_created_at (created_at),
  KEY idx_model_call_provider_model_type (provider, model, call_type),
  CONSTRAINT fk_model_call_user FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='LLM and embedding call log';
