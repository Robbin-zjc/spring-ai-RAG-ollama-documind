package com.techie.springai.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RAGService {
    
    private static final Logger log = LoggerFactory.getLogger(RAGService.class);
    
    private final VectorStore vectorStore;
    
    @Value("${rag.search.topk:20}")
    private int defaultTopK;
    
    @Value("${rag.search.similarity-threshold:0.25}")
    private double defaultThreshold;
    
    public RAGService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }
    
    /**
     * 混合检索：根据问题类型动态调整策略
     */
    public List<Document> hybridSearch(String question) {
        long startTime = System.currentTimeMillis();
        
        // 1. 判断问题类型，动态调整参数
        SearchConfig config = determineSearchConfig(question);
        
        log.info("检索配置 - topK: {}, threshold: {}", config.topK, config.threshold);
        
        // 2. 主检索
        Set<String> uniqueIds = new HashSet<>();
        List<Document> allDocs = new ArrayList<>();
        
        addUniqueDocs(allDocs, uniqueIds, searchWithConfig(question, config));
        log.info("主检索获得 {} 个文档", allDocs.size());
        
        // 3. 补充检索（针对列举类问题）
        if (isListingQuestion(question)) {
            String broadQuery = question.replaceAll("有哪些|包括|所有|？", "").trim();
            SearchConfig relaxedConfig = new SearchConfig(
                10, 
                Math.max(0.15, config.threshold - 0.1)
            );
            
            addUniqueDocs(allDocs, uniqueIds, searchWithConfig(broadQuery, relaxedConfig));
            log.info("补充检索后共 {} 个文档", allDocs.size());
        }
        
        // 4. 如果检索结果太少，降低阈值重试
        if (allDocs.size() < 5) {
            log.warn("检索结果不足，降低阈值重试...");
            SearchConfig retryConfig = new SearchConfig(
                config.topK + 10, 
                0.15
            );
            addUniqueDocs(allDocs, uniqueIds, searchWithConfig(question, retryConfig));
            log.info("重试后共 {} 个文档", allDocs.size());
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("检索完成，耗时: {}ms, 最终文档数: {}", duration, allDocs.size());
        
        return allDocs;
    }
    
    /**
     * 根据问题类型确定检索配置
     */
    private SearchConfig determineSearchConfig(String question) {
        int topK = defaultTopK;
        double threshold = defaultThreshold;
        
        // 列举类问题：增加检索量，降低阈值
        if (isListingQuestion(question)) {
            topK = (int) (topK * 1.5);
            threshold -= 0.05;
        }
        
        // 精确查找问题：减少检索量，提高阈值
        if (isPrecisionQuestion(question)) {
            topK = (int) (topK * 0.7);
            threshold += 0.05;
        }
        
        // 边界保护
        topK = Math.max(5, Math.min(50, topK));
        threshold = Math.max(0.1, Math.min(0.5, threshold));
        
        return new SearchConfig(topK, threshold);
    }
    
    /**
     * 执行检索
     */
    private List<Document> searchWithConfig(String query, SearchConfig config) {
        try {
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(config.topK)
                .similarityThreshold(config.threshold)
                .build();
            
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 添加去重文档
     */
    private void addUniqueDocs(List<Document> target, Set<String> ids, List<Document> source) {
        for (Document doc : source) {
            if (ids.add(doc.getId())) {
                target.add(doc);
            }
        }
    }
    
    /**
     * 判断是否为列举类问题
     */
    private boolean isListingQuestion(String question) {
        String[] keywords = {"有哪些", "包括", "所有", "列举", "分为", "几个", "几种", "星级"};
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 判断是否为精确查找问题
     */
    private boolean isPrecisionQuestion(String question) {
        String[] keywords = {"是什么", "定义", "具体指", "含义"};
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 构建优化的 Prompt
     */
    public String buildPrompt(String question, List<Document> docs) {
    StringBuilder prompt = new StringBuilder();
    
    prompt.append("你是一个精确的文档问答助手。\n\n");
    prompt.append("### 参考文档：\n");
    
    for (int i = 0; i < docs.size(); i++) {
        prompt.append(String.format("[文档片段 %d]\n%s\n\n", i + 1, docs.get(i).getText()));
    }
    
    prompt.append("### 用户问题：\n");
    prompt.append(question).append("\n\n");
    
    prompt.append("### 回答要求：\n");
    prompt.append("1. 【重要】仔细阅读所有文档片段，不要遗漏任何信息\n");
    prompt.append("2. 【重要】如果问题是'有哪些''分为几个'等列举类问题，必须完整列出文档中提到的每一个类别，即使某些描述很简短\n");
    prompt.append("3. 严格基于上述文档内容回答，不得添加文档外的信息\n");
    prompt.append("4. 如果某个类别在文档中只有简单描述，也必须在答案中体现\n");
    prompt.append("5. 答案格式：先总结有几个类别，再逐一说明每个类别的特点\n");
    prompt.append("6. 如果文档中确实没有相关信息，才说明'文档中未提及'\n\n");
    
    prompt.append("### 回答：\n");
    
    return prompt.toString();
}

    
    /**
     * 内部配置类
     */
    private static class SearchConfig {
        int topK;
        double threshold;
        
        SearchConfig(int topK, double threshold) {
            this.topK = topK;
            this.threshold = threshold;
        }
    }
}
