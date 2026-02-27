package com.techie.springai.rag.controller;

import com.techie.springai.rag.service.RAGService;
import com.techie.springai.rag.service.SessionStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    private static final String UPLOAD_DIR = "uploads/";

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
        "txt", "md", "csv", "html", "xml", "json",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff"
    );

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ChatClient chatClient;
    private final RAGService ragService;
    private final SessionStoreService sessionStoreService;

    public DocumentController(
        VectorStore vectorStore,
        JdbcTemplate jdbcTemplate,
        ChatClient.Builder chatClientBuilder,
        RAGService ragService,
        SessionStoreService sessionStoreService
    ) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.chatClient = chatClientBuilder.build();
        this.ragService = ragService;
        this.sessionStoreService = sessionStoreService;
        new File(UPLOAD_DIR).mkdirs();
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
        @RequestParam(value = "file", required = false) MultipartFile file,
        @RequestParam(value = "files", required = false) MultipartFile[] files
    ) {
        try {
            List<MultipartFile> inputs = new ArrayList<>();
            if (file != null) {
                inputs.add(file);
            }
            if (files != null && files.length > 0) {
                inputs.addAll(Arrays.asList(files));
            }

            if (inputs.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "至少上传一个文件"));
            }

            if (inputs.size() == 1) {
                String imported = ingestSingleFile(inputs.get(0));
                return ResponseEntity.ok(Map.of(
                    "mode", "single",
                    "message", "文件上传并导入成功",
                    "imported", imported,
                    "successCount", 1,
                    "failedCount", 0,
                    "success", List.of(imported),
                    "failed", Collections.emptyList()
                ));
            }

            return uploadDocuments(inputs.toArray(new MultipartFile[0]));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("文件处理失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "文件处理失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("向量化失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "向量化失败: " + e.getMessage()));
        }
    }

    @PostMapping("/upload/batch")
    public ResponseEntity<Map<String, Object>> uploadDocuments(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "至少上传一个文件"));
        }

        List<String> success = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                String imported = ingestSingleFile(file);
                success.add(imported);
            } catch (Exception e) {
                String filename = file != null ? Objects.toString(file.getOriginalFilename(), "unknown") : "unknown";
                failed.add(Map.of("file", filename, "error", Objects.toString(e.getMessage(), "unknown")));
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("mode", "batch");
        resp.put("requested", files.length);
        resp.put("successCount", success.size());
        resp.put("failedCount", failed.size());
        resp.put("success", success);
        resp.put("failed", failed);
        resp.put("message", failed.isEmpty() ? "批量上传全部成功" : "批量上传部分失败，请查看 failed 明细");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/documents")
    public ResponseEntity<List<Map<String, Object>>> listDocuments() {
        try {
            String sql = "SELECT metadata->>'source' as source, COUNT(*) as chunk_count " +
                "FROM vector_store WHERE metadata->>'source' IS NOT NULL " +
                "GROUP BY metadata->>'source' ORDER BY COUNT(*) DESC";

            List<Map<String, Object>> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> doc = new HashMap<>();
                String source = rs.getString("source");
                String filename = extractReadableFileName(source);
                doc.put("id", rowNum + 1);
                doc.put("filename", filename);
                doc.put("fullPath", source);
                doc.put("chunkCount", rs.getInt("chunk_count"));
                return doc;
            });
            return ResponseEntity.ok(documents);
        } catch (Exception e) {
            log.error("查询文档列表失败", e);
            return ResponseEntity.internalServerError().body(null);
        }
    }

    @GetMapping("/filters/options")
    public ResponseEntity<Map<String, Object>> filterOptions() {
        try {
            List<Map<String, Object>> docs = listDocuments().getBody();
            if (docs == null) {
                docs = Collections.emptyList();
            }
            List<String> sourceFiles = docs.stream()
                .map(d -> Objects.toString(d.get("filename"), ""))
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();

            List<String> fileTypes = docs.stream()
                .map(d -> Objects.toString(d.get("filename"), ""))
                .map(this::getExtension)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();

            return ResponseEntity.ok(Map.of(
                "sourceFiles", sourceFiles,
                "fileTypes", fileTypes
            ));
        } catch (Exception e) {
            log.error("获取过滤器选项失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, Object>> deleteDocument(@RequestParam("filename") String filename) {
        try {
            if (filename == null || filename.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "filename 不能为空"));
            }

            String normalized = Paths.get(filename).getFileName().toString();

            String deleteSql = "DELETE FROM vector_store WHERE metadata->>'source' LIKE ?";
            int deletedRows = jdbcTemplate.update(deleteSql, "%" + normalized);

            int deletedFiles = 0;
            File uploadDir = new File(UPLOAD_DIR);
            File[] files = uploadDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith("_" + normalized) || f.getName().equals(normalized)) {
                        if (f.delete()) {
                            deletedFiles++;
                        }
                    }
                }
            }

            return ResponseEntity.ok(Map.of(
                "filename", normalized,
                "deletedVectors", deletedRows,
                "deletedFiles", deletedFiles
            ));
        } catch (Exception e) {
            log.error("删除文档失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "删除失败: " + e.getMessage()));
        }
    }

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, Object> req) {
        String name = req == null ? "" : Objects.toString(req.get("name"), "");
        String sessionId = sessionStoreService.createSession(name);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        return ResponseEntity.ok(sessionStoreService.listSessions());
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        Map<String, Object> session = sessionStoreService.getSession(sessionId);
        if (session.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        boolean removed = sessionStoreService.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "cleared", removed));
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, Object> request) {
        try {
            String question = Objects.toString(request.get("question"), "").trim();
            if (question.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "问题不能为空"));
            }

            String sessionId = Objects.toString(request.get("sessionId"), "default");
            Set<String> sourceFiles = toStringSet(request.get("sourceFiles"));
            Set<String> fileTypes = toStringSet(request.get("fileTypes"));

            RAGService.RetrievalOptions options = new RAGService.RetrievalOptions(sourceFiles, fileTypes);
            List<Document> similarDocs = ragService.hybridSearch(question, options);
            if (similarDocs.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "answer", "抱歉，未找到相关文档内容，请确认已上传相关文档。",
                    "sources", Collections.emptyList(),
                    "question", question,
                    "sessionId", sessionId,
                    "retrievedChunks", 0,
                    "citations", Collections.emptyList()
                ));
            }

            String historyContext = buildHistoryContext(sessionId);
            String prompt = ragService.buildPrompt(question, similarDocs) + "\n\n### 对话历史:\n" + historyContext;
            String draftAnswer = chatClient.prompt().user(prompt).call().content();

            String verifyPrompt = ragService.buildVerificationPrompt(question, draftAnswer, similarDocs);
            String verifyResult = chatClient.prompt().user(verifyPrompt).call().content();
            String finalAnswer = mergeVerification(draftAnswer, verifyResult);

            sessionStoreService.appendTurn(sessionId, "user", question);
            sessionStoreService.appendTurn(sessionId, "assistant", finalAnswer);

            List<Map<String, Object>> citations = buildCitations(similarDocs);
            Set<String> sourceFilesOut = new LinkedHashSet<>();
            citations.forEach(c -> sourceFilesOut.add(Objects.toString(c.get("source"), "unknown")));

            Map<String, Object> response = new HashMap<>();
            response.put("answer", finalAnswer);
            response.put("draftAnswer", draftAnswer);
            response.put("verification", verifyResult);
            response.put("sources", new ArrayList<>(sourceFilesOut));
            response.put("question", question);
            response.put("sessionId", sessionId);
            response.put("retrievedChunks", similarDocs.size());
            response.put("citations", citations);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("查询失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "查询失败: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@RequestBody Map<String, Object> request) {
        SseEmitter emitter = new SseEmitter(0L);

        new Thread(() -> {
            try {
                String question = Objects.toString(request.get("question"), "").trim();
                if (question.isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("问题不能为空"));
                    emitter.complete();
                    return;
                }

                String sessionId = Objects.toString(request.get("sessionId"), "default");
                Set<String> sourceFiles = toStringSet(request.get("sourceFiles"));
                Set<String> fileTypes = toStringSet(request.get("fileTypes"));

                RAGService.RetrievalOptions options = new RAGService.RetrievalOptions(sourceFiles, fileTypes);
                List<Document> similarDocs = ragService.hybridSearch(question, options);

                if (similarDocs.isEmpty()) {
                    emitter.send(SseEmitter.event().name("token").data("抱歉，未找到相关文档内容，请确认已上传相关文档。"));
                    emitter.send(SseEmitter.event().name("meta").data(Map.of(
                        "sources", Collections.emptyList(),
                        "citations", Collections.emptyList(),
                        "sessionId", sessionId
                    )));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                    return;
                }

                String historyContext = buildHistoryContext(sessionId);
                String prompt = ragService.buildPrompt(question, similarDocs) + "\n\n### 对话历史:\n" + historyContext;

                StringBuilder answerBuilder = new StringBuilder();
                chatClient.prompt()
                    .user(prompt)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        answerBuilder.append(token);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .blockLast();

                String finalAnswer = answerBuilder.toString();
                sessionStoreService.appendTurn(sessionId, "user", question);
                sessionStoreService.appendTurn(sessionId, "assistant", finalAnswer);

                List<Map<String, Object>> citations = buildCitations(similarDocs);
                Set<String> sourceFilesOut = new LinkedHashSet<>();
                citations.forEach(c -> sourceFilesOut.add(Objects.toString(c.get("source"), "unknown")));

                emitter.send(SseEmitter.event().name("meta").data(Map.of(
                    "sources", new ArrayList<>(sourceFilesOut),
                    "citations", citations,
                    "sessionId", sessionId,
                    "retrievedChunks", similarDocs.size()
                )));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Objects.toString(e.getMessage(), "stream error")));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    private String ingestSingleFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        originalFilename = Paths.get(originalFilename).getFileName().toString();
        String extension = getExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型。当前支持: " + ALLOWED_EXTENSIONS);
        }

        if (file.getSize() <= 0) {
            throw new IllegalArgumentException("文件为空，无法解析");
        }

        String filename = System.nanoTime() + "_" + originalFilename;
        Path filepath = Paths.get(UPLOAD_DIR, filename);
        Files.write(filepath, file.getBytes());
        log.info("文件已保存: {}", filepath);

        try {
            List<Document> readDocs = readAsDocumentsWithFallback(filepath, extension);
            if (readDocs == null || readDocs.isEmpty()) {
                throw new IllegalArgumentException("文档未解析出可用内容");
            }

            for (Document d : readDocs) {
                d.getMetadata().put("source", filename);
                d.getMetadata().put("fileType", extension);
            }

            TextSplitter textSplitter = createAdaptiveSplitter(extension);
            List<Document> chunks = textSplitter.split(readDocs).stream()
                .filter(d -> d.getText() != null && !d.getText().trim().isBlank())
                .collect(Collectors.toList());

            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文档内容为空或无法切分为有效文本");
            }

            log.info("开始向量化并导入: {}, extension={}, chunks={}", filename, extension, chunks.size());
            vectorStore.accept(chunks);
            log.info("导入完成: {}", filename);
            return filename;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(filepath);
            } catch (Exception cleanupEx) {
                log.warn("清理失败文件失败: {}", filepath, cleanupEx);
            }
            throw new IllegalArgumentException("文档解析/向量化失败: " + e.getMessage());
        }
    }

    private List<Document> readAsDocumentsWithFallback(Path filepath, String extension) throws IOException {
        if (isTextLike(extension)) {
            String content = Files.readString(filepath, StandardCharsets.UTF_8);
            if (content == null || content.trim().isBlank()) {
                throw new IllegalArgumentException("文本文件内容为空");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("parser", "plain-text");
            metadata.put("extension", extension);
            return List.of(new Document(content, metadata));
        }

        if (isImageType(extension)) {
            throw new IllegalArgumentException("图片文件需要 OCR 才能用于文本问答。请先转为可复制文本的 PDF/TXT，或接入 OCR 引擎（Tesseract/PaddleOCR）");
        }

        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(filepath.toFile()));
        List<Document> docs = reader.read();
        if (docs == null || docs.isEmpty()) {
            throw new IllegalArgumentException("该文件无法提取文本，请确认文档未损坏且包含文本层");
        }
        return docs;
    }

    private boolean isTextLike(String extension) {
        return Set.of("txt", "md", "csv", "json", "xml", "html").contains(extension);
    }

    private boolean isImageType(String extension) {
        return Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff").contains(extension);
    }

    private String buildHistoryContext(String sessionId) {
        List<Map<String, String>> hist = sessionStoreService.getHistory(sessionId);
        if (hist == null || hist.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> turn : hist) {
            sb.append(turn.getOrDefault("role", "unknown"))
                .append(": ")
                .append(turn.getOrDefault("content", ""))
                .append("\n");
        }
        return sb.toString();
    }

    private TextSplitter createAdaptiveSplitter(String extension) {
        return switch (extension) {
            case "ppt", "pptx", "pdf" -> new TokenTextSplitter(700, 180, 5, 12000, true);
            case "doc", "docx", "md", "txt" -> new TokenTextSplitter(600, 150, 5, 10000, true);
            case "csv", "xls", "xlsx", "json", "xml", "html" -> new TokenTextSplitter(450, 120, 5, 8000, true);
            default -> new TokenTextSplitter(600, 150, 5, 10000, true);
        };
    }

    private List<Map<String, Object>> buildCitations(List<Document> docs) {
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String source = Objects.toString(doc.getMetadata().get("source"), "unknown");
            citations.add(Map.of(
                "index", i + 1,
                "source", extractReadableFileName(source),
                "snippet", trimSnippet(doc.getText())
            ));
        }
        return citations;
    }

    private String trimSnippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private String mergeVerification(String draftAnswer, String verifyResult) {
        if (verifyResult == null || verifyResult.isBlank()) {
            return draftAnswer;
        }
        String lower = verifyResult.toLowerCase(Locale.ROOT);
        if (lower.contains("verdict: fail") && lower.contains("revised_answer:")) {
            int idx = lower.indexOf("revised_answer:");
            return verifyResult.substring(idx + "revised_answer:".length()).trim();
        }
        return draftAnswer;
    }

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(Object value) {
        if (value == null) {
            return Collections.emptySet();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        String single = value.toString().trim();
        return single.isEmpty() ? Collections.emptySet() : Set.of(single);
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    private String extractReadableFileName(String source) {
        String filename = source;
        if (source.contains("/")) {
            filename = source.substring(source.lastIndexOf('/') + 1);
        }
        if (source.contains("\\")) {
            filename = source.substring(source.lastIndexOf('\\') + 1);
        }
        if (filename.matches("\\d+_.*")) {
            filename = filename.substring(filename.indexOf('_') + 1);
        }
        return filename;
    }
}
