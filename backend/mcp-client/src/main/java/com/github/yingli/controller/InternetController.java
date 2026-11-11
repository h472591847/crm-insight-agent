package com.github.yingli.controller;


import com.github.yingli.entity.ChatEntity;
import com.github.yingli.service.ChatService;
import com.github.yingli.service.SearXngService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * @Author oliver
 */
@RestController
@RequestMapping("/internet")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.feature.websearch-enabled", havingValue = "true")
public class InternetController {

    private final SearXngService searXngService;

    private final ChatService chatService;

    @GetMapping("/test")
    public Object test(@RequestParam("query") String query) {
        return searXngService.search(query);
    }

    /**
     * 基于searXng的实时联网搜索, 由大模型输出
     *
     * @param chatEntity
     * @param response
     */
    @PostMapping("/search")
    public void search(@RequestBody ChatEntity chatEntity, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        chatService.doInternetSearch(chatEntity);
    }

}
