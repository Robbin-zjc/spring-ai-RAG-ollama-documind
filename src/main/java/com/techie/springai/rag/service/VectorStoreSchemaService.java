package com.techie.springai.rag.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 向量表结构自检服务。
 *
 * <p>目的：避免“模型 embedding 维度”与“数据库向量列维度”不一致导致入库失败。
 * 在应用启动后自动检测并尝试修复（必要时清空向量表再改列类型）。
 */
@Service
public class VectorStoreSchemaService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreSchemaService.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${rag.embedding.dimensions:768}")
    private int expectedDimensions;

    public VectorStoreSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 启动后执行维度对齐检查。
     *
     * <p>注意：当维度不一致时会 TRUNCATE vector_store，请确保这是可接受行为。
     */
    @PostConstruct
    public void alignVectorDimensions() {
        try {
            Integer typmod = jdbcTemplate.query(
                """
                SELECT a.atttypmod
                FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'public'
                  AND c.relname = 'vector_store'
                  AND a.attname = 'embedding'
                  AND a.attnum > 0
                """,
                rs -> rs.next() ? rs.getInt(1) : null
            );

            if (typmod == null || typmod <= 4) {
                log.info("未检测到 vector_store.embedding 维度信息，跳过自动修复");
                return;
            }

            int currentDimensions = typmod - 4;
            if (currentDimensions == expectedDimensions) {
                log.info("Vector 维度检查通过: {}", currentDimensions);
                return;
            }

            log.warn("检测到向量维度不一致: DB={}, Expected={}，开始自动修复（清空向量表）", currentDimensions, expectedDimensions);
            jdbcTemplate.execute("TRUNCATE TABLE vector_store");
            jdbcTemplate.execute("ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(" + expectedDimensions + ")");
            log.warn("向量表维度已修复为 {}，请重新上传文档", expectedDimensions);
        } catch (Exception e) {
            log.error("自动修复向量维度失败，请手动执行表修复: {}", e.getMessage(), e);
        }
    }
}
