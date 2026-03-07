package com.techie.springai.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 会话存储服务（轻量级持久化）。
 *
 * <p>作用：
 * <ul>
 *   <li>为多轮问答提供上下文记忆（sessionId）</li>
 *   <li>将会话历史持久化到本地 JSON 文件，便于服务重启后恢复</li>
 * </ul>
 *
 * <p>当前实现基于内存 + 文件落盘，适合单机部署；
 * 若升级为多实例，建议迁移到 Redis / 数据库。
 */
@Service
public class SessionStoreService {

    /** 会话落盘文件位置。 */
    private static final String STORAGE_FILE = "uploads/sessions.json";

    /** 每个会话最多保留轮次，避免上下文无限膨胀。 */
    private static final int MAX_TURNS = 30;

    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SessionStoreService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ensureParentDir();
        loadFromDisk();
    }

    /**
     * 创建会话。
     *
     * @param name 会话名（可空）
     * @return 新生成的 sessionId
     */
    public String createSession(String name) {
        String sessionId = UUID.randomUUID().toString();
        SessionData data = new SessionData();
        data.setSessionId(sessionId);
        data.setName(name == null || name.isBlank() ? "会话-" + sessionId.substring(0, 8) : name.trim());
        data.setUpdatedAt(Instant.now().toString());
        sessions.put(sessionId, data);
        saveToDisk();
        return sessionId;
    }

    /**
     * 追加一轮对话到指定会话。
     *
     * <p>会自动裁剪历史长度，控制上下文与存储成本。
     */
    public void appendTurn(String sessionId, String role, String content) {
        SessionData data = sessions.computeIfAbsent(sessionId, this::newDefaultSession);
        data.getTurns().add(Map.of("role", role, "content", content));
        if (data.getTurns().size() > MAX_TURNS) {
            data.setTurns(new ArrayList<>(data.getTurns().subList(data.getTurns().size() - MAX_TURNS, data.getTurns().size())));
        }
        data.setUpdatedAt(Instant.now().toString());
        saveToDisk();
    }

    public List<Map<String, String>> getHistory(String sessionId) {
        return sessions.getOrDefault(sessionId, newDefaultSession(sessionId)).getTurns();
    }

    public boolean clearSession(String sessionId) {
        SessionData removed = sessions.remove(sessionId);
        saveToDisk();
        return removed != null;
    }

    public List<Map<String, Object>> listSessions() {
        return sessions.values().stream()
            .sorted(Comparator.comparing(SessionData::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
            .map(s -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", s.getSessionId());
                item.put("name", s.getName());
                item.put("turns", s.getTurns().size());
                item.put("updatedAt", Objects.toString(s.getUpdatedAt(), ""));
                return item;
            })
            .collect(Collectors.toList());
    }

    public Map<String, Object> getSession(String sessionId) {
        SessionData data = sessions.get(sessionId);
        if (data == null) {
            return Map.of();
        }
        return Map.of(
            "sessionId", data.getSessionId(),
            "name", data.getName(),
            "updatedAt", data.getUpdatedAt(),
            "history", data.getTurns()
        );
    }

    private SessionData newDefaultSession(String sessionId) {
        SessionData data = new SessionData();
        data.setSessionId(sessionId);
        data.setName("会话-" + sessionId.substring(0, Math.min(8, sessionId.length())));
        data.setUpdatedAt(Instant.now().toString());
        data.setTurns(new ArrayList<>());
        return data;
    }

    private void ensureParentDir() {
        File f = new File(STORAGE_FILE);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private synchronized void saveToDisk() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(STORAGE_FILE), sessions);
        } catch (IOException ignored) {
        }
    }

    private synchronized void loadFromDisk() {
        File f = new File(STORAGE_FILE);
        if (!f.exists()) {
            return;
        }
        try {
            Map<String, SessionData> loaded = objectMapper.readValue(f, new TypeReference<>() {});
            sessions.clear();
            sessions.putAll(loaded);
        } catch (IOException ignored) {
        }
    }

    public static class SessionData {
        private String sessionId;
        private String name;
        private String updatedAt;
        private List<Map<String, String>> turns = new ArrayList<>();

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public List<Map<String, String>> getTurns() { return turns; }
        public void setTurns(List<Map<String, String>> turns) { this.turns = turns; }
    }
}
