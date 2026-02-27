package com.techie.springai.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaEmbeddingModelService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingModelService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}")
    private String embeddingModel;

    @Value("${rag.embedding.auto-pull:true}")
    private boolean autoPull;

    @Value("${rag.embedding.pull-timeout-seconds:300}")
    private int pullTimeoutSeconds;

    public OllamaEmbeddingModelService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void ensureEmbeddingModelReady() {
        if (isModelAvailable()) {
            return;
        }
        if (!autoPull) {
            throw new IllegalStateException("Embedding 模型未安装: " + embeddingModel + "。请先执行: ollama pull " + embeddingModel);
        }

        pullModel();

        if (!isModelAvailable()) {
            throw new IllegalStateException("Embedding 模型拉取后仍不可用: " + embeddingModel + "。请检查 Ollama 服务日志。");
        }
    }

    private boolean isModelAvailable() {
        try {
            String url = ollamaBaseUrl + "/api/tags";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return false;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return false;
            }

            for (JsonNode model : models) {
                String name = model.path("name").asText("");
                if (name.equals(embeddingModel) || name.startsWith(embeddingModel + ":")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("检查 Ollama 模型失败: {}", e.getMessage());
            return false;
        }
    }

    private void pullModel() {
        String url = ollamaBaseUrl + "/api/pull";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("name", embeddingModel);
        body.put("stream", false);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.setRequestFactory(clientHttpRequestFactory());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("拉取 embedding 模型失败，HTTP " + response.getStatusCode().value());
            }
            log.info("已触发 Ollama 模型拉取: {}", embeddingModel);
        } catch (Exception e) {
            throw new IllegalStateException("自动拉取 embedding 模型失败: " + e.getMessage(), e);
        }
    }

    private org.springframework.http.client.SimpleClientHttpRequestFactory clientHttpRequestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Duration.ofSeconds(Math.max(30, pullTimeoutSeconds)).toMillis();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}
