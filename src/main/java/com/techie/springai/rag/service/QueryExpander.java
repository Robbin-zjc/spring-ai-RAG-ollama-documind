package com.techie.springai.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询扩展器 - 根据原始问题生成多个搜索查询
 * 遵循行业标准的查询扩展策略（Query Expansion）
 */
@Component
public class QueryExpander {
    
    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);
    
    private static final Map<String, String[]> SYNONYMS = Map.ofEntries(
        Map.entry("有哪些", new String[]{"包括", "涵盖", "包含", "由"}),
        Map.entry("是什么", new String[]{"定义", "含义", "解释", "说明"}),
        Map.entry("怎么", new String[]{"如何", "方式", "方法", "步骤"}),
        Map.entry("为什么", new String[]{"原因", "因素", "原由"}),
        Map.entry("特点", new String[]{"特性", "属性", "性质", "特征"}),
        Map.entry("优势", new String[]{"优点", "好处", "利益", "长处"}),
        Map.entry("劣势", new String[]{"缺点", "不足", "问题", "弱点"})
    );
    
    /**
     * 生成扩展查询列表
     */
    public List<String> expandQuery(String originalQuestion) {
        List<String> queries = new ArrayList<>();
        queries.add(originalQuestion); // 保留原始查询
        
        // 1. 同义词替换扩展
        queries.addAll(generateSynonymQueries(originalQuestion));
        
        // 2. 关键词提取扩展
        queries.addAll(generateKeywordFocusedQueries(originalQuestion));
        
        // 3. 问题分解扩展
        queries.addAll(generateDecomposedQueries(originalQuestion));
        
        // 4. 上位下位关系扩展
        queries.addAll(generateHypernymHyponymQueries(originalQuestion));
        
        // 去重和排序
        List<String> uniqueQueries = queries.stream()
            .distinct()
            .filter(q -> !q.isEmpty() && q.length() > 2)
            .limit(10) // 限制扩展查询数量
            .collect(Collectors.toList());
        
        log.info("查询扩展: {} -> {} 个查询", originalQuestion, uniqueQueries.size());
        return uniqueQueries;
    }
    
    /**
     * 同义词替换扩展
     */
    private List<String> generateSynonymQueries(String question) {
        List<String> synonymQueries = new ArrayList<>();
        
        for (Map.Entry<String, String[]> entry : SYNONYMS.entrySet()) {
            if (question.contains(entry.getKey())) {
                for (String synonym : entry.getValue()) {
                    String newQuery = question.replace(entry.getKey(), synonym);
                    synonymQueries.add(newQuery);
                }
            }
        }
        
        return synonymQueries;
    }
    
    /**
     * 关键词聚焦扩展 - 突出不同的关键词
     */
    private List<String> generateKeywordFocusedQueries(String question) {
        List<String> keywordQueries = new ArrayList<>();
        
        // 提取名词短语
        String[] words = question.split("\\s+|(?<=[\\p{P}\\p{Punct}])");
        
        // 生成只包含关键词的查询
        if (words.length > 3) {
            // 移除常见的虚词
            String[] stopWords = {"的", "是", "在", "和", "与", "或", "？", "。", ","};
            Set<String> stopSet = new HashSet<>(Arrays.asList(stopWords));
            
            String keywordOnly = Arrays.stream(words)
                .filter(w -> !stopSet.contains(w) && w.length() > 0)
                .collect(Collectors.joining(" "));
            
            if (!keywordOnly.isEmpty()) {
                keywordQueries.add(keywordOnly);
            }
        }
        
        return keywordQueries;
    }
    
    /**
     * 问题分解扩展 - 将复合问题分解为多个简单问题
     */
    private List<String> generateDecomposedQueries(String question) {
        List<String> decomposedQueries = new ArrayList<>();
        
        // 分解"和"连接的问题
        if (question.contains("和") || question.contains("与") || question.contains("及")) {
            String[] parts = question.split("[和与及]");
            for (String part : parts) {
                String trimmed = part.trim().replaceAll("[？。]", "");
                if (trimmed.length() > 2) {
                    decomposedQueries.add(trimmed);
                }
            }
        }
        
        // 分解"或"连接的问题
        if (question.contains("或")) {
            String[] parts = question.split("或");
            for (String part : parts) {
                String trimmed = part.trim().replaceAll("[？。]", "");
                if (trimmed.length() > 2) {
                    decomposedQueries.add(trimmed);
                }
            }
        }
        
        return decomposedQueries;
    }
    
    /**
     * 上位下位关系扩展 - 自动化的泛化/特化
     */
    private List<String> generateHypernymHyponymQueries(String question) {
        List<String> hypernymQueries = new ArrayList<>();
        
        // 针对列举类问题，生成更通用的查询
        if (question.contains("有哪些") || question.contains("包括")) {
            String generalized = question.replaceAll("有哪些|包括|分别是|都有", "");
            generalized = generalized.replaceAll("[？。]", "").trim();
            
            if (generalized.length() > 2) {
                hypernymQueries.add(generalized);
                hypernymQueries.add(generalized + "的分类");
                hypernymQueries.add(generalized + "的类型");
            }
        }
        
        return hypernymQueries;
    }
    
    /**
     * 获取查询重要性权重 - 用于排序结果
     */
    public double getQueryImportance(String query, String originalQuestion) {
        double importance = 1.0;
        
        // 原始查询权重最高
        if (query.equals(originalQuestion)) {
            importance = 1.5;
        }
        // 关键词查询权重高
        else if (query.split("\\s+").length < originalQuestion.split("\\s+").length * 0.7) {
            importance = 1.2;
        }
        
        return importance;
    }
}
