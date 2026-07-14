package com.example.ekb.storage;

import java.io.InputStream;

public interface StorageService {

    StoredObject putObject(String objectKey, InputStream inputStream, long size, String contentType);

    InputStream getObject(String objectKey);

    boolean exists(String objectKey);

    void removeObject(String objectKey);

    void ensureBucketExists();

    String bucketName();
}
