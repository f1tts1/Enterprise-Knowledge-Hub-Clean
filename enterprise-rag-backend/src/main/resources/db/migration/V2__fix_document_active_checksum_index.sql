-- Fix document checksum deduplication.
--
-- Old index uk_doc_kb_checksum_deleted(kb_id, checksum_sha256, is_deleted)
-- allowed only one deleted row for the same kb/checksum. That made this
-- sequence fail:
--   1. upload file A
--   2. delete file A
--   3. upload the same file A again
--   4. delete the second copy
--
-- The generated column below keeps deduplication only for active documents:
-- active rows expose checksum_sha256 to the unique index, deleted rows expose
-- NULL. MySQL unique indexes allow multiple NULL values, so multiple deleted
-- history rows can keep their original checksum.

USE enterprise_knowledge_hub;

SET @active_checksum_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'document'
    AND column_name = 'active_checksum_sha256'
);

SET @sql = IF(
  @active_checksum_column_exists = 0,
  'ALTER TABLE document ADD COLUMN active_checksum_sha256 CHAR(64) GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN checksum_sha256 ELSE NULL END) STORED COMMENT ''Only active documents participate in checksum deduplication'' AFTER is_deleted',
  'SELECT ''active_checksum_sha256 already exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @active_checksum_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'document'
    AND index_name = 'uk_doc_kb_active_checksum'
);

SET @sql = IF(
  @active_checksum_index_exists = 0,
  'CREATE UNIQUE INDEX uk_doc_kb_active_checksum ON document (kb_id, active_checksum_sha256)',
  'SELECT ''uk_doc_kb_active_checksum already exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @old_checksum_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'document'
    AND index_name = 'uk_doc_kb_checksum_deleted'
);

SET @sql = IF(
  @old_checksum_index_exists > 0,
  'ALTER TABLE document DROP INDEX uk_doc_kb_checksum_deleted',
  'SELECT ''uk_doc_kb_checksum_deleted already removed'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
