package com.example.ekb.document.controller;

import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.common.response.PageResponse;
import com.example.ekb.document.dto.DocumentIndexStatusResponse;
import com.example.ekb.document.dto.DocumentResponse;
import com.example.ekb.document.service.DocumentService;
import com.example.ekb.security.LoginUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/knowledge-bases/{kbId}/documents")
    public ApiResponse<DocumentResponse> upload(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long kbId,
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(documentService.upload(loginUser.id(), kbId, file));
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ApiResponse<PageResponse<DocumentResponse>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page must be at least 1") long page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must be at most 100") long size
    ) {
        return ApiResponse.success(documentService.list(loginUser.id(), kbId, page, size));
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<DocumentResponse> getDetail(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(documentService.getDetail(loginUser.id(), documentId));
    }

    @GetMapping("/documents/{documentId}/index-status")
    public ApiResponse<DocumentIndexStatusResponse> getIndexStatus(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(documentService.getIndexStatus(loginUser.id(), documentId));
    }

    @PostMapping("/documents/{documentId}/index-retry")
    public ApiResponse<DocumentIndexStatusResponse> retryIndex(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(documentService.retryIndex(loginUser.id(), documentId));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long documentId
    ) {
        documentService.delete(loginUser.id(), documentId);
        return ApiResponse.success(null);
    }
}
