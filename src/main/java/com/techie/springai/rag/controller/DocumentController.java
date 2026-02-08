package com.techie.springai.rag.controller;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.ai.vectorstore.SearchRequest;

import java.util.*;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.ai.document.Document;
import com.techie.springai.rag.service.RAGService;  

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api")
public class DocumentController {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    private final VectorStore vectorStore;
private final JdbcTemplate jdbcTemplate;
private final ChatClient chatClient;
private final RAGService ragService;  // ← 添加这行

// 上传文件存放目录
private static final String UPLOAD_DIR = "uploads/";

public DocumentController(
    VectorStore vectorStore, 
    JdbcTemplate jdbcTemplate,
    ChatClient.Builder chatClientBuilder,
    RAGService ragService  // ← 添加这个参数
) {
    this.vectorStore = vectorStore;
    this.jdbcTemplate = jdbcTemplate;
    this.chatClient = chatClientBuilder.build();
    this.ragService = ragService;  // ← 添加这行
    new File(UPLOAD_DIR).mkdirs();
}

    
    @PostMapping("/upload")
    public ResponseEntity<String> uploadDocument(@RequestParam("file") MultipartFile file) {
        // 1. 校验文件
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }
        
        if (!file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body("只支持 PDF 文件");
        }
        
        try {
            // 2. 保存文件到本地
            String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filepath = Paths.get(UPLOAD_DIR, filename);
            Files.write(filepath, file.getBytes());
            log.info("文件已保存: {}", filepath);
            
            // 3. 解析并导入向量库（复用之前的逻辑）
            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(filepath.toFile()));
            //TextSplitter textSplitter = new TokenTextSplitter(300, 50, 5, 10000, true); // 调小 chunk 避免超长
            TextSplitter textSplitter = new TokenTextSplitter(600, 150, 5, 10000, true);// ↑ chunk 增大到 600，overlap 增大到 150，保留更多上下文


            log.info("开始向量化并导入: {}", filename);
            vectorStore.accept(textSplitter.split(reader.read()));
            log.info("导入完成: {}", filename);
            
            return ResponseEntity.ok("文件上传并导入成功: " + filename);
            
        } catch (IOException e) {
            log.error("文件处理失败", e);
            return ResponseEntity.internalServerError().body("文件处理失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("向量化失败", e);
            return ResponseEntity.internalServerError().body("向量化失败: " + e.getMessage());
        }
    }
    @GetMapping("/documents")
public ResponseEntity<List<Map<String, Object>>> listDocuments() {
    try {
        // SQL：按文档分组，统计每个文档的 chunk 数量
        String sql = "SELECT " +
                     "  metadata->>'source' as source, " +
                     "  COUNT(*) as chunk_count " +
                     "FROM vector_store " +
                     "WHERE metadata->>'source' IS NOT NULL " +
                     "GROUP BY metadata->>'source' " +
                     "ORDER BY COUNT(*) DESC";  // 按 chunk 数量降序
        
        List<Map<String, Object>> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> doc = new HashMap<>();
            
            // 提取文件名（去掉路径前缀 "uploads/" 和时间戳前缀）
            String source = rs.getString("source");
            String filename = source;
            if (source.contains("/")) {
                filename = source.substring(source.lastIndexOf("/") + 1);
            }
            // 去掉时间戳前缀（1770434765237_竞赛星级表.pdf -> 竞赛星级表.pdf）
            if (filename.matches("\\d+_.*")) {
                filename = filename.substring(filename.indexOf("_") + 1);
            }
            
            doc.put("id", rowNum + 1);  // 用行号作为 id
            doc.put("filename", filename);
            doc.put("fullPath", source);
            doc.put("chunkCount", rs.getInt("chunk_count"));
            
            return doc;
        });
        
        log.info("查询到 {} 个文档", documents.size());
        return ResponseEntity.ok(documents);
        
    } catch (Exception e) {
        log.error("查询文档列表失败", e);
        return ResponseEntity.internalServerError().body(null);
    }
}

@PostMapping("/query")
public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> request) {
    try {
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "问题不能为空"));
        }
        
        log.info("收到查询请求: {}", question);
        
        // ===== 使用 RAGService 混合检索 =====
        List<org.springframework.ai.document.Document> similarDocs = ragService.hybridSearch(question);
        
        if (similarDocs.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "answer", "抱歉，未找到相关文档内容，请确认已上传相关文档。",
                "sources", Collections.emptyList(),
                "question", question,
                "retrievedChunks", 0
            ));
        }
        
        // ===== 构建优化的 Prompt =====
        String prompt = ragService.buildPrompt(question, similarDocs);
        
        // ===== 调用 LLM =====
        String answer = chatClient.prompt()
            .user(prompt)
            .call()
            .content();
        
        // ===== 提取来源文件 =====
        Set<String> sourceFiles = new HashSet<>();
        for (var doc : similarDocs) {
            Object sourceObj = doc.getMetadata().get("source");
            if (sourceObj != null) {
                String source = sourceObj.toString();
                if (source.contains("/")) {
                    source = source.substring(source.lastIndexOf("/") + 1);
                }
                if (source.matches("\\d+_.*")) {
                    source = source.substring(source.indexOf("_") + 1);
                }
                sourceFiles.add(source);
            }
        }
        
        // ===== 返回结果 =====
        Map<String, Object> response = new HashMap<>();
        response.put("answer", answer);
        response.put("sources", new ArrayList<>(sourceFiles));
        response.put("question", question);
        response.put("retrievedChunks", similarDocs.size());
        
        log.info("查询完成，检索到 {} 个片段，答案长度: {}", similarDocs.size(), answer.length());
        return ResponseEntity.ok(response);
        
    } catch (Exception e) {
        log.error("查询失败", e);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "查询失败: " + e.getMessage()));
    }
}




}
