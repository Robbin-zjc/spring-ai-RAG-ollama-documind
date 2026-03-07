package com.techie.springai.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 简化版问答接口（Demo 入口）。
 *
 * <p>说明：
 * <ul>
 *   <li>该接口直接使用 Spring AI 的 QuestionAnswerAdvisor，自动从向量库检索上下文后回答。</li>
 *   <li>适合做最小可用演示（MVP）；复杂逻辑（重排、过滤、引用、会话）在 DocumentController + RAGService 中实现。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** Ollama 对话模型客户端（负责文本生成）。 */
    private final OllamaChatModel ollamaChatModel;

    /** 向量存储（负责语义检索证据片段）。 */
    private final VectorStore vectorStore;

    public ChatController(OllamaChatModel ollamaChatModel, VectorStore vectorStore) {
        this.ollamaChatModel = ollamaChatModel;
        this.vectorStore = vectorStore;
    }

    /**
     * 单轮问答接口。
     *
     * @param message 用户问题
     * @return 模型生成答案（已结合向量检索结果）
     */
    @PostMapping
    public String chat(@RequestBody String message) {
        return ChatClient.builder(ollamaChatModel)
                .build().prompt()
                .advisors(new QuestionAnswerAdvisor(vectorStore))
                .user(message)
                .call()
                .content();
    }
}
