package com.techie.springai.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档重排序器 - 使用多维度重排序优化检索结果
 * 遵循行业标准的Learning-to-Rank (LTR) 思想
 */
@Component
public class DocumentReranker {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentReranker.class);
    
    @Value("${rag.rerank.enabled:true}")
    private boolean enabled;
    
    @Value("${rag.rerank.position-weight:0.2}")
    private double positionWeight; // 位置权重
    
    @Value("${rag.rerank.diversity-weight:0.15}")
    private double diversityWeight; // 多样性权重
    
    @Value("${rag.rerank.coverage-weight:0.15}")
    private double coverageWeight; // 覆盖度权重
    
    /**
     * 重排序文档列表
     */
    public List<Document> rerank(List<Document> documents, String query) {
        if (!enabled || documents.isEmpty()) {
            return documents;
        }
        
        // 1. 计算每个文档的综合分数
        List<DocumentScore> scores = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            scores.add(calculateScore(documents.get(i), query, i, documents.size()));
        }
        
        // 2. 按综合分数排序
        List<Document> reranked = scores.stream()
            .sorted(Comparator.comparingDouble(DocumentScore::getTotalScore).reversed())
            .map(DocumentScore::getDocument)
            .collect(Collectors.toList());
        
        log.debug("文档重排序完成: {} 个文档", reranked.size());
        return reranked;
    }
    
    /**
     * 计算单个文档的综合分数
     */
    private DocumentScore calculateScore(Document doc, String query, int originalIndex, int totalDocs) {
        double relevanceScore = calculateRelevanceScore(doc, query);
        double positionScore = calculatePositionScore(originalIndex, totalDocs);
        double diversityScore = calculateDiversityScore(doc);
        double coverageScore = calculateCoverageScore(doc);
        
        double totalScore = 
            relevanceScore * 0.5 +           // 相关性最重要
            positionScore * positionWeight +
            diversityScore * diversityWeight +
            coverageScore * coverageWeight;
        
        return new DocumentScore(doc, relevanceScore, positionScore, diversityScore, 
            coverageScore, totalScore, originalIndex);
    }
    
    /**
     * 计算相关性分数
     */
    private double calculateRelevanceScore(Document doc, String query) {
        String content = doc.getText().toLowerCase();
        String queryLower = query.toLowerCase();
        
        double score = 0.0;
        
        // 1. 查询词完全匹配
        if (content.contains(queryLower)) {
            score += 0.5;
        }
        
        // 2. 关键词匹配率
        String[] queryWords = queryLower.split("\\s+");
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && content.contains(word)) {
                matchedWords++;
            }
        }
        score += (double) matchedWords / queryWords.length * 0.3;
        
        // 3. 文档长度合理性（太长或太短都不好）
        int contentLength = content.length();
        if (contentLength > 100 && contentLength < 2000) {
            score += 0.2;
        }
        
        return Math.min(score, 1.0);
    }
    
    /**
     * 位置偏差分数 - 原始排名靠前的文档得分更高
     */
    private double calculatePositionScore(int originalIndex, int totalDocs) {
        // 使用对数衰减函数
        return 1.0 / (1.0 + Math.log(originalIndex + 1));
    }
    
    /**
     * 多样性分数 - 鼓励返回内容多样化的文档
     */
    private double calculateDiversityScore(Document doc) {
        String content = doc.getText();
        
        // 1. 检查文档长度多样性
        if (content.length() < 200) {
            return 0.3; // 太短
        } else if (content.length() > 1500) {
            return 0.8; // 较长，通常更全面
        } else {
            return 0.6; // 中等长度
        }
    }
    
    /**
     * 信息覆盖度分数 - 基于信息丰富度
     */
    private double calculateCoverageScore(Document doc) {
        String content = doc.getText();
        
        // 计算内容的多样性指标
        double score = 0.0;
        
        // 1. 句子数量
        int sentences = content.split("[。！？]").length;
        if (sentences > 5) {
            score += 0.3;
        }
        
        // 2. 数字和具体数据的存在
        if (content.matches(".*\\d+.*")) {
            score += 0.2;
        }
        
        // 3. 列表项（使用常见符号）
        if (content.matches(".*[1-9]\\.|•|·|×|√|☆|★.*")) {
            score += 0.2;
        }
        
        // 4. 结构化信息（使用冒号、箭头等）
        if (content.matches(".*[:：→→-].*")) {
            score += 0.2;
        }
        
        return Math.min(score, 1.0);
    }
    
    /**
     * 多查询结果融合 - 合并来自不同查询的结果
     */
    public List<Document> fuseResults(Map<String, List<Document>> queryResults, Map<String, Double> queryWeights) {
        Map<String, FusedDocumentScore> fusedScores = new HashMap<>();

        for (Map.Entry<String, List<Document>> entry : queryResults.entrySet()) {
            String query = entry.getKey();
            List<Document> docs = entry.getValue();
            double queryWeight = queryWeights.getOrDefault(query, 1.0);

            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                String docId = (doc.getId() != null && !doc.getId().isBlank())
                    ? doc.getId()
                    : Integer.toHexString(Objects.hashCode(doc.getText()));

                // RRF 思想 + 查询权重，兼顾原始查询精度与扩展查询召回
                double rankScore = 1.0 / (60 + i + 1);
                double totalContribution = rankScore * queryWeight;

                fusedScores.computeIfAbsent(docId, k -> new FusedDocumentScore(doc))
                    .addScore(totalContribution);
            }
        }
        
        // 按融合分数排序
        return fusedScores.values().stream()
            .sorted(Comparator.comparingDouble(FusedDocumentScore::getTotalScore).reversed())
            .map(FusedDocumentScore::getDocument)
            .collect(Collectors.toList());
    }
    
    /**
     * 文档分数内部类
     */
    private static class DocumentScore {
        private final Document document;
        private final double relevanceScore;
        private final double positionScore;
        private final double diversityScore;
        private final double coverageScore;
        private final double totalScore;
        private final int originalIndex;
        
        public DocumentScore(Document document, double relevanceScore, double positionScore,
                           double diversityScore, double coverageScore, double totalScore,
                           int originalIndex) {
            this.document = document;
            this.relevanceScore = relevanceScore;
            this.positionScore = positionScore;
            this.diversityScore = diversityScore;
            this.coverageScore = coverageScore;
            this.totalScore = totalScore;
            this.originalIndex = originalIndex;
        }
        
        public Document getDocument() { return document; }
        public double getTotalScore() { return totalScore; }
    }
    
    /**
     * 融合文档分数内部类
     */
    private static class FusedDocumentScore {
        private final Document document;
        private double totalScore;
        
        public FusedDocumentScore(Document document) {
            this.document = document;
            this.totalScore = 0.0;
        }
        
        public void addScore(double score) {
            this.totalScore += score;
        }
        
        public Document getDocument() { return document; }
        public double getTotalScore() { return totalScore; }
    }
}
