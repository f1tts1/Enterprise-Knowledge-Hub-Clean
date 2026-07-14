package com.example.ekb.retrieval.controller;

import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import com.example.ekb.retrieval.service.RetrievalService;
import com.example.ekb.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public ApiResponse<RetrievalSearchResponse> search(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long kbId,
            @Valid @RequestBody RetrievalSearchRequest request
    ) {
        return ApiResponse.success(retrievalService.search(loginUser.id(), kbId, request));
    }
}
