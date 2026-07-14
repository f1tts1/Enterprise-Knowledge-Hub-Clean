package com.example.ekb.knowledge.controller;

import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.common.response.PageResponse;
import com.example.ekb.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.ekb.knowledge.dto.KnowledgeBaseResponse;
import com.example.ekb.knowledge.service.KnowledgeBaseService;
import com.example.ekb.security.LoginUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeBaseService.create(loginUser.id(), request));
    }

    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseResponse>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page must be at least 1") long page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must be at most 100") long size
    ) {
        return ApiResponse.success(knowledgeBaseService.list(loginUser.id(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> getDetail(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id
    ) {
        return ApiResponse.success(knowledgeBaseService.getDetail(loginUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id
    ) {
        knowledgeBaseService.delete(loginUser.id(), id);
        return ApiResponse.success(null);
    }
}
