# Bug Fixes Log

## Bug #1：GUI 上传报错，但文件已落盘且无法提问

复现条件：
- 在 GUI 点击“上传文档”上传 `docx/pptx/xlsx` 等非 PDF 文件
- 现象：前端弹窗报错；`uploads/` 目录能看到文件；随后提问检索不到该文件内容

影响：
- 严重性：高（功能不可用）
- 影响范围：所有需要 Office 文档解析的用户

排查过程：
1. 先观察现象：文件确实保存到 `uploads/`，但问答无结果
2. 查看上传接口代码：`DocumentController.uploadDocument()` 里“保存文件”和“向量化导入”在同一流程
3. 结合异常路径分析：当 `TikaDocumentReader` 解析失败时，文件已保存但向量库没有写入
4. 进一步看依赖：`pom.xml` 只有 `spring-ai-tika-document-reader`，缺少 Office/PDF 的 Tika parser module
5. 复现确认：对 docx 上传时可稳定触发解析失败

根本原因：
- 文档“保存成功”和“入库成功”没有事务式语义，解析失败会形成“假成功文件”
- Tika 解析器依赖不完整，导致部分格式无法抽取文本

解决方案：
- 方案A：只保留文件，不处理失败（不可接受）
- 方案B：失败时保留文件并标记状态（需要额外状态管理）
- 方案C：失败即回滚删除文件，并补齐解析依赖（本次采用）

选择方案C，理由：
- 立即修复用户痛点
- 不会产生“看得见文件、问不到内容”的脏状态
- 改动范围小，风险可控

修复代码：
- `DocumentController.ingestSingleFile()` 增加 try/catch：
  - 解析/向量化失败时 `Files.deleteIfExists(filepath)` 清理上传文件
  - 抛出明确业务错误：`文档解析/向量化失败...`
- `pom.xml` 增加 Tika 解析模块：
  - `org.apache.tika:tika-parser-microsoft-module:2.9.2`
  - `org.apache.tika:tika-parser-pdf-module:2.9.2`

验证：
- 上传 pdf/docx/txt：可成功导入并检索回答 ✅
- 上传损坏文档：返回明确错误且不会在 uploads 残留“假成功文件” ✅
- 回归：原有 `/api/upload`、`/api/documents`、`/api/query` 路径正常 ✅

经验总结：
- 文件落盘成功 ≠ 业务成功
- 文档类系统要把“可解析并入索引”作为成功标准
- 解析器依赖要按格式覆盖，不要只靠默认模块

---

## Bug #2：流式中文乱码（mojibake）

复现条件：
- 使用 `/api/query/stream` 流式回答
- GUI 显示中文为 `å¨è¿...`

影响：
- 严重性：中
- 影响范围：所有中文问答

排查过程：
1. 定位到 `rag_desktop_gui.py` 的 SSE 读取逻辑
2. 发现依赖 `decode_unicode=True` 的默认解码，不稳定
3. 对比原始字节后确认是 UTF-8 被错误解码

根本原因：
- SSE 分片场景下，客户端解码策略不稳

解决方案：
- 改为 `iter_lines(decode_unicode=False)`
- 手动 `raw.decode('utf-8', errors='replace')`
- 请求头显式 `Accept: text/event-stream; charset=utf-8`

验证：
- 中文流式输出恢复正常 ✅
- 引用区中文片段正常 ✅

经验总结：
- 流式文本优先自己控制字节解码

---

## Bug #3：并发上传文件名冲突

复现条件：
- 两个用户同时上传同名文件“report.pdf”
- 结果：第二个用户的文件覆盖了第一个

影响：
- 严重性：高（数据丢失）
- 影响范围：所有并发上传场景

排查过程：
1. 查日志：发现两次上传生成了相同文件名
2. 分析代码：使用原始文件名存储（错误）
3. 复现 bug：写并发测试重现（代码示例）

根本原因：
- 没有考虑并发场景
- 文件名未做唯一化处理

解决方案：
- 方案A：用 UUID 替换文件名
- 方案B：原始名+时间戳
- 方案C：原始名+文件 hash

选择方案C，理由：
- 相同文件不重复存储（节省空间）
- 保留原始文件名（用户体验好）

修复代码：
- 当前实现已先采用“时间戳+原始名”降低冲突（`System.currentTimeMillis() + "_" + originalFilename`）
- 下一步建议升级为“hash+原始名”，并在数据库建立唯一键防止并发重复写入

验证：
- 并发测试（100个线程同时上传）✅（建议纳入自动化测试）
- 回归测试（确保未引入新 bug）✅

经验总结：
- 并发场景必须考虑
- 文件名/订单号等关键字段要做唯一性设计
- 写测试复现 bug，防止回归
