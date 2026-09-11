package com.stockmanager.assistant;

import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 当前登录用户的私有知识库文档管理接口。 */
@RestController
@RequestMapping("/api/v1/assistant/knowledge/documents")
public class AssistantKnowledgeController {
    private final AssistantKnowledgeService service;
    public AssistantKnowledgeController(AssistantKnowledgeService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<AssistantKnowledgeService.DocumentView>> list(Authentication auth,
                                                                          HttpServletRequest request) {
        return ApiResponse.success(service.list(user(auth)), trace(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AssistantKnowledgeService.DocumentView> upload(Authentication auth,
                                                                      @RequestPart("file") MultipartFile file,
                                                                      HttpServletRequest request) {
        return ApiResponse.success(service.upload(user(auth), file, trace(request)), trace(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id, HttpServletRequest request) {
        service.delete(user(auth), id);
        return ApiResponse.success(null, trace(request));
    }

    private long user(Authentication auth) { return Long.parseLong(auth.getName()); }
    private String trace(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
