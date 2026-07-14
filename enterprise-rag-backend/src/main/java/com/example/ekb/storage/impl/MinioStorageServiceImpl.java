package com.example.ekb.storage.impl;

import java.io.InputStream;

import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.storage.MinioProperties;
import com.example.ekb.storage.StorageService;
import com.example.ekb.storage.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MinioStorageServiceImpl implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioStorageServiceImpl(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public StoredObject putObject(String objectKey, InputStream inputStream, long size, String contentType) {
        // 延迟创建 bucket，降低本地开发门槛：只要启动 MinIO，
        // 不需要手动提前建 bucket。
        ensureBucketExists();
        String normalizedObjectKey = normalizeObjectKey(objectKey);
        String resolvedContentType = StringUtils.hasText(contentType) ? contentType : DEFAULT_CONTENT_TYPE;

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName())
                    .object(normalizedObjectKey)
                    .stream(inputStream, size, -1)
                    .contentType(resolvedContentType)
                    .build());

            return new StoredObject(bucketName(), normalizedObjectKey, size, resolvedContentType);
        } catch (Exception ex) {
            throw storageException("Failed to put object: " + normalizedObjectKey, ex);
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        String normalizedObjectKey = normalizeObjectKey(objectKey);
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName())
                    .object(normalizedObjectKey)
                    .build());
        } catch (Exception ex) {
            throw storageException("Failed to get object: " + normalizedObjectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        String normalizedObjectKey = normalizeObjectKey(objectKey);
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName())
                    .object(normalizedObjectKey)
                    .build());
            return true;
        } catch (ErrorResponseException ex) {
            // 只有对象不存在才返回 false。权限、网络、签名错误都应该直接失败，
            // 因为它们通常是环境配置问题。
            if ("NoSuchKey".equals(ex.errorResponse().code())) {
                return false;
            }
            throw storageException("Failed to stat object: " + normalizedObjectKey, ex);
        } catch (Exception ex) {
            throw storageException("Failed to stat object: " + normalizedObjectKey, ex);
        }
    }

    @Override
    public void removeObject(String objectKey) {
        String normalizedObjectKey = normalizeObjectKey(objectKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName())
                    .object(normalizedObjectKey)
                    .build());
        } catch (ErrorResponseException ex) {
            // S3/MinIO 的删除应按幂等语义处理：目标对象已经不存在，
            // 说明“删除后的最终状态”已经达成，不需要让业务删除任务失败。
            if ("NoSuchKey".equals(ex.errorResponse().code())) {
                return;
            }
            throw storageException("Failed to remove object: " + normalizedObjectKey, ex);
        } catch (Exception ex) {
            throw storageException("Failed to remove object: " + normalizedObjectKey, ex);
        }
    }

    @Override
    public void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucketName())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucketName())
                        .build());
            }
        } catch (Exception ex) {
            throw storageException("Failed to ensure bucket exists: " + bucketName(), ex);
        }
    }

    @Override
    public String bucketName() {
        return minioProperties.getBucket();
    }

    private String normalizeObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey is required");
        }
        // MinIO objectKey 统一使用斜杠，即使调用方传入 Windows 风格路径。
        return objectKey.trim().replace('\\', '/');
    }

    private BusinessException storageException(String message, Exception ex) {
        return new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, message + ": " + ex.getMessage());
    }
}
