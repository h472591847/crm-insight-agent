package com.github.yingli.service;


import com.github.yingli.entity.ChatEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @Author oliver
 */
public interface ChatService {
    /**
     * 测试大模型交互聊天（同步）
     * <p>
     * Note:
     * 系统提示词3大类型
     * 1.system
     * 2.user
     * 3.assistant
     *
     * @param prompt
     * @return
     */
    String chatTest(String prompt);

    /**
     * 测试大模型交互聊天（流式响应JSON）
     *
     * @param prompt
     * @return
     */
    Flux<ChatResponse> streamResponse(String prompt);

    /**
     * 测试大模型交互聊天（流式响应String）
     *
     * @param prompt
     * @return
     */
    Flux<String> streamStr(String prompt);

    /**
     * 和大模型交互
     *
     * @param chatEntity
     */
    void doChat(ChatEntity chatEntity);

    /**
     * Rag知识库检索汇总给大模型输出
     *
     * @param chatEntity
     * @param ragContext
     */
    void doChatRagSearch(ChatEntity chatEntity, List<Document> ragContext);

    /**
     * 基于searXng的实时联网搜索
     *
     * @param chatEntity
     */
    void doInternetSearch(ChatEntity chatEntity);

}
