package com.example.ekb.common.enums;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    SUCCESS("0", "success", HttpStatus.OK),
    BAD_REQUEST("400", "Bad request", HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED("400001", "Validation failed", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("401", "Unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("403", "Forbidden", HttpStatus.FORBIDDEN),
    NOT_FOUND("404", "Resource not found", HttpStatus.NOT_FOUND),
    CONFLICT("409", "Conflict", HttpStatus.CONFLICT),
    USERNAME_ALREADY_EXISTS("409001", "Username already exists", HttpStatus.CONFLICT),
    KNOWLEDGE_BASE_NOT_FOUND("404001", "Knowledge base not found", HttpStatus.NOT_FOUND),
    KNOWLEDGE_BASE_NAME_ALREADY_EXISTS("409002", "Knowledge base name already exists", HttpStatus.CONFLICT),
    DOCUMENT_NOT_FOUND("404002", "Document not found", HttpStatus.NOT_FOUND),
    DOCUMENT_TYPE_NOT_SUPPORTED("400002", "Document type is not supported", HttpStatus.BAD_REQUEST),
    DOCUMENT_FILE_TOO_LARGE("400003", "Document file is too large", HttpStatus.BAD_REQUEST),
    DOCUMENT_ALREADY_EXISTS("409003", "Document already exists in this knowledge base", HttpStatus.CONFLICT),
    DOCUMENT_BUSY("409004", "Document is being processed", HttpStatus.CONFLICT),
    INVALID_USERNAME_OR_PASSWORD("401001", "Invalid username or password", HttpStatus.UNAUTHORIZED),
    USER_DISABLED("403001", "User is disabled", HttpStatus.FORBIDDEN),
    TOKEN_INVALID("401002", "Invalid token", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_STORE_UNAVAILABLE("503003", "Auth token store unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVICE_UNAVAILABLE("503001", "AI service unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    STORAGE_OPERATION_FAILED("503002", "Storage operation failed", HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR("500", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
