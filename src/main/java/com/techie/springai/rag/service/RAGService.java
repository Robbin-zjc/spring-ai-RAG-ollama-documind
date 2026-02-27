package com.techie.springai.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RAGService {

    private static final Logger log = LoggerFactory.getLogger(RAGService.class);

    private final VectorStore vectorStore;
    private final QueryExpander queryExpander;
    private final DocumentReranker documentReranker;

    @Value("${rag.search.topk:20}")
    private int defaultTopK;

    @Value("${rag.search.similarity-threshold:0.25}")
    private double defaultThreshold;

    @Value("${rag.search.expand-query:true}")
    private boolean expandQueryEnabled;

    @Value("${rag.search.rerank:true}")
    private boolean rerankEnabled;

    @Value("${rag.search.max-docs:15}")
    private int maxDocuments;

    @Value("${rag.search.max-per-source:4}")
    private int maxPerSource;

    @Value("${rag.search.min-source-coverage:2}")
    private int minSourceCoverage;

    @Value("${rag.hybrid.lexical-weight:0.35}")
    private double lexicalWeight;

    public RAGService(VectorStore vectorStore, QueryExpander queryExpander,
                      DocumentReranker documentReranker) {
        this.vectorStore = vectorStore;
        this.queryExpander = queryExpander;
        this.documentReranker = documentReranker;
    }

    public List<Document> hybridSearch(String question) {
        return hybridSearch(question, RetrievalOptions.defaultOptions());
    }

    /**
     * 进阶混合检索：
     * - 多查询扩展 + RRF 融合
     * - Dense + Lexical late-fusion
     * - Metadata 过滤（文件名/文件类型）
     * - 跨来源覆盖控制
     */
    public List<Document> hybridSearch(String question, RetrievalOptions options) {
        long startTime = System.currentTimeMillis();

        SearchConfig config = determineSearchConfig(question);

        List<String> expandedQueries = new ArrayList<>();
        expandedQueries.add(question);
        if (expandQueryEnabled) {
            List<String> expansions = queryExpander.expandQuery(question);
            expandedQueries.addAll(expansions.stream()
                .filter(q -> !q.equals(question))
                .limit(4)
                .toList());
        }

        Set<String> uniqueIds = new HashSet<>();
        List<Document> allDocs = new ArrayList<>();
        Map<String, List<Document>> queryResults = new HashMap<>();
        Map<String, Double> queryWeights = new HashMap<>();

        for (String query : expandedQueries) {
            List<Document> results = searchWithConfig(query, config);
            queryResults.put(query, results);
            queryWeights.put(query, queryExpander.getQueryImportance(query, question));
            addUniqueDocs(allDocs, uniqueIds, results);
        }

        // 检索后先做元数据过滤（来源文件/类型）
        allDocs = applyMetadataFilter(allDocs, options);
        queryResults.replaceAll((k, v) -> applyMetadataFilter(v, options));

        if (isListingQuestion(question) && allDocs.size() < 10) {
            String broadQuery = question.replaceAll("有哪些|包括|所有|？", "").trim();
            SearchConfig relaxedConfig = new SearchConfig(10, Math.max(0.15, config.threshold - 0.1));
            List<Document> supplementary = applyMetadataFilter(searchWithConfig(broadQuery, relaxedConfig), options);
            addUniqueDocs(allDocs, uniqueIds, supplementary);
        }

        if (allDocs.size() < 5) {
            SearchConfig retryConfig = new SearchConfig(config.topK + 10, 0.15);
            List<Document> retryResults = applyMetadataFilter(searchWithConfig(question, retryConfig), options);
            addUniqueDocs(allDocs, uniqueIds, retryResults);
        }

        List<Document> finalResults = allDocs;
        if (rerankEnabled && !allDocs.isEmpty()) {
            if (queryResults.size() > 1) {
                finalResults = documentReranker.fuseResults(queryResults, queryWeights);
            }
            finalResults = documentReranker.rerank(finalResults, question);
            finalResults = hybridDenseLexicalRescore(finalResults, question);
        }

        finalResults = ensureSourceCoverage(finalResults, maxDocuments, maxPerSource, minSourceCoverage);

        long duration = System.currentTimeMillis() - startTime;
        log.info("检索完成: cost={}ms, docs={}, queries={}", duration, finalResults.size(), expandedQueries.size());
        return finalResults;
    }

    private List<Document> hybridDenseLexicalRescore(List<Document> docs, String question) {
        if (docs.isEmpty()) {
            return docs;
        }
        List<String> terms = extractTerms(question);
        if (terms.isEmpty()) {
            return docs;
        }

        Map<String, Integer> lexicalScores = new HashMap<>();
        int maxLex = 1;
        for (Document doc : docs) {
            int score = lexicalScore(doc.getText(), terms);
            lexicalScores.put(resolveUniqueId(doc), score);
            maxLex = Math.max(maxLex, score);
        }

        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double denseNorm = 1.0 - ((double) i / Math.max(1, docs.size()));
            double lexicalNorm = (double) lexicalScores.getOrDefault(resolveUniqueId(doc), 0) / maxLex;
            double score = denseNorm * (1.0 - lexicalWeight) + lexicalNorm * lexicalWeight;
            scored.add(new ScoredDoc(doc, score));
        }

        return scored.stream()
            .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
            .map(ScoredDoc::doc)
            .collect(Collectors.toList());
    }

    private int lexicalScore(String text, List<String> terms) {
        String content = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (term.length() >= 2 && content.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private List<String> extractTerms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("[，。！？,.?]", " ");
        String[] raw = normalized.split("\\s+");
        Set<String> stop = Set.of("是什么", "有哪些", "包括", "请问", "怎么", "如何", "为什么", "一个", "这个", "那个");
        return Arrays.stream(raw)
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .filter(s -> !stop.contains(s))
            .distinct()
            .toList();
    }

    private List<Document> applyMetadataFilter(List<Document> docs, RetrievalOptions options) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        if (!options.hasAnyFilter()) {
            return docs;
        }

        return docs.stream().filter(doc -> {
            String source = resolveSource(doc).toLowerCase(Locale.ROOT);
            boolean sourceMatch = options.sourceFiles().isEmpty() || options.sourceFiles().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(source::contains);

            boolean typeMatch = options.fileTypes().isEmpty() || options.fileTypes().stream()
                .map(s -> s.toLowerCase(Locale.ROOT).replace(".", ""))
                .anyMatch(ext -> source.endsWith("." + ext));

            return sourceMatch && typeMatch;
        }).collect(Collectors.toList());
    }

    private SearchConfig determineSearchConfig(String question) {
        int topK = defaultTopK;
        double threshold = defaultThreshold;

        if (isListingQuestion(question)) {
            topK = (int) (topK * 1.5);
            threshold -= 0.05;
        }

        if (isPrecisionQuestion(question)) {
            topK = (int) (topK * 0.7);
            threshold += 0.05;
        }

        topK = Math.max(5, Math.min(50, topK));
        threshold = Math.max(0.1, Math.min(0.5, threshold));

        return new SearchConfig(topK, threshold);
    }

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

    private void addUniqueDocs(List<Document> target, Set<String> ids, List<Document> source) {
        for (Document doc : source) {
            String uniqueId = resolveUniqueId(doc);
            if (ids.add(uniqueId)) {
                target.add(doc);
            }
        }
    }

    private List<Document> ensureSourceCoverage(List<Document> rankedDocs, int maxDocs, int maxPerSource, int minSourceCoverage) {
        if (rankedDocs == null || rankedDocs.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> sourceCounter = new HashMap<>();
        List<Document> selected = new ArrayList<>();
        List<Document> overflow = new ArrayList<>();

        for (Document doc : rankedDocs) {
            String source = resolveSource(doc);
            int count = sourceCounter.getOrDefault(source, 0);
            if (count < maxPerSource) {
                selected.add(doc);
                sourceCounter.put(source, count + 1);
                if (selected.size() >= maxDocs) {
                    break;
                }
            } else {
                overflow.add(doc);
            }
        }

        if (selected.size() < maxDocs || sourceCounter.size() < minSourceCoverage) {
            Set<String> coveredSources = new HashSet<>(sourceCounter.keySet());
            for (Document doc : overflow) {
                if (selected.size() >= maxDocs && coveredSources.size() >= minSourceCoverage) {
                    break;
                }
                String source = resolveSource(doc);
                if (!coveredSources.contains(source)) {
                    selected.add(doc);
                    coveredSources.add(source);
                    sourceCounter.put(source, sourceCounter.getOrDefault(source, 0) + 1);
                }
            }
        }

        return selected.stream().limit(maxDocs).collect(Collectors.toList());
    }

    private String resolveSource(Document doc) {
        Object source = doc.getMetadata().get("source");
        return source == null ? "unknown" : source.toString();
    }

    private String resolveUniqueId(Document doc) {
        if (doc.getId() != null && !doc.getId().isBlank()) {
            return doc.getId();
        }
        return resolveSource(doc) + "#" + Integer.toHexString(Objects.hashCode(doc.getText()));
    }

    private boolean isListingQuestion(String question) {
        String[] keywords = {"有哪些", "包括", "所有", "列举", "分为", "几个", "几种", "星级"};
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrecisionQuestion(String question) {
        String[] keywords = {"是什么", "定义", "具体指", "含义"};
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public String buildPrompt(String question, List<Document> docs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个精确的文档问答助手。\\n\\n");
        prompt.append("### 参考文档：\\n");

        for (int i = 0; i < docs.size(); i++) {
            String source = resolveSource(docs.get(i));
            prompt.append(String.format("[文档片段 %d][来源:%s]\\n%s\\n\\n", i + 1, source, docs.get(i).getText()));
        }

        prompt.append("### 用户问题：\\n");
        prompt.append(question).append("\\n\\n");

        prompt.append("### 回答要求：\\n");
        prompt.append("1. 仔细阅读所有文档片段，优先整合不同来源的信息，不要遗漏关键点\\n");
        prompt.append("2. 列举类问题必须完整列出并去重\\n");
        prompt.append("3. 严格基于文档内容回答，不确定时明确说明证据不足\\n");
        prompt.append("4. 每个关键结论后附证据片段编号，如（证据：[文档片段2][文档片段5]）\\n");
        prompt.append("5. 最后补充'未覆盖的信息'\\n\\n");
        prompt.append("### 回答：\\n");

        return prompt.toString();
    }

    public String buildVerificationPrompt(String question, String answer, List<Document> docs) {
        StringBuilder p = new StringBuilder();
        p.append("你是答案质检器。请检查【候选答案】是否被【证据片段】支持。\\n");
        p.append("输出格式：\\n");
        p.append("- verdict: PASS/FAIL\\n");
        p.append("- issues: 列出不被支持或遗漏点\\n");
        p.append("- revised_answer: 若FAIL则给出修正后的最终答案（保留证据编号）\\n\\n");
        p.append("【问题】\\n").append(question).append("\\n\\n");
        p.append("【候选答案】\\n").append(answer).append("\\n\\n");
        p.append("【证据片段】\\n");
        for (int i = 0; i < docs.size(); i++) {
            p.append("[文档片段 ").append(i + 1).append("] ").append(docs.get(i).getText()).append("\\n");
        }
        return p.toString();
    }

    public record RetrievalOptions(Set<String> sourceFiles, Set<String> fileTypes) {
        public static RetrievalOptions defaultOptions() {
            return new RetrievalOptions(Collections.emptySet(), Collections.emptySet());
        }

        public boolean hasAnyFilter() {
            return (sourceFiles != null && !sourceFiles.isEmpty()) || (fileTypes != null && !fileTypes.isEmpty());
        }
    }

    private record SearchConfig(int topK, double threshold) {}

    private record ScoredDoc(Document doc, double score) {}
}
