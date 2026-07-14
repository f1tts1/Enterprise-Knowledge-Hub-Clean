package com.example.ekb.rag.controller;

import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.rag.dto.RagAskRequest;
import com.example.ekb.rag.dto.RagAskResponse;
import com.example.ekb.rag.service.RagService;
import com.example.ekb.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public ApiResponse<RagAskResponse> ask(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long kbId,
            @Valid @RequestBody RagAskRequest request
    ) {
        return ApiResponse.success(ragService.ask(loginUser.id(), kbId, request));
    }
}
