package com.stockmanager.assistant;

import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 复用 JWT，用户身份只取自服务器认证主体。 */
@RestController
@RequestMapping("/api/v1/assistant/conversations")
public class AssistantConversationController {
    private final AssistantConversationService service;
    public AssistantConversationController(AssistantConversationService service) { this.service = service; }
    public record RenameRequest(String title) {}
    @GetMapping
    public ApiResponse<List<AssistantConversationService.Conversation>> list(Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(service.list(user(auth)), trace(request));
    }
    @PostMapping
    public ApiResponse<AssistantConversationService.Conversation> create(Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(service.create(user(auth)), trace(request));
    }
    @GetMapping("/{id}/messages")
    public ApiResponse<List<AssistantConversationService.Message>> messages(Authentication auth, @PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.messages(user(auth), id), trace(request));
    }
    @PatchMapping("/{id}")
    public ApiResponse<Void> rename(Authentication auth, @PathVariable String id, @RequestBody RenameRequest body, HttpServletRequest request) {
        service.rename(user(auth), id, body.title());
        return ApiResponse.success(null, trace(request));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id, HttpServletRequest request) {
        service.delete(user(auth), id);
        return ApiResponse.success(null, trace(request));
    }
    private long user(Authentication auth) { return Long.parseLong(auth.getName()); }
    private String trace(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
