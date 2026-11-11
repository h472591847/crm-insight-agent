package com.github.yingli.controller;


import com.github.yingli.entity.ChatEntity;
import com.github.yingli.service.ChatService;
import com.github.yingli.service.DocumentService;
import com.github.yingli.utils.LeeResult;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @Author oliver
 */
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.feature.rag-enabled", havingValue = "true")
public class RagController {

    private final DocumentService documentService;

    private final ChatService chatService;

    @PostMapping("/uploadRagDoc")
    public LeeResult uploadRagDoc(@RequestParam("file") MultipartFile file) {
        List<Document> documentList = documentService.loadText(file.getResource(), file.getOriginalFilename());

        return LeeResult.ok(documentList);
    }

    @GetMapping("/doSearch")
    public LeeResult doSearch(@RequestParam("question") String question) {
        return LeeResult.ok(documentService.doSearch(question));
    }

    /**
     * 从知识库中搜索内容, 由大模型输出
     *
     * @param chatEntity
     * @param response
     */
    @PostMapping("/search")
    public void search(@RequestBody ChatEntity chatEntity, HttpServletResponse response) {
        List<Document> documentList = documentService.doSearch(chatEntity.getMessage());
        response.setCharacterEncoding("UTF-8");
        chatService.doChatRagSearch(chatEntity, documentList);
    }

}
