import json
import os
import sys
import uuid
from dataclasses import dataclass
from typing import List, Dict, Any, Optional

import requests
from PyQt5.QtCore import Qt, QThread, pyqtSignal
from PyQt5.QtGui import QFont
from PyQt5.QtWidgets import (
    QApplication,
    QFileDialog,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QPlainTextEdit,
    QSplitter,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
    QHeaderView,
    QAbstractItemView,
)

API_BASE_DEFAULT = "http://localhost:8080/api"
SUPPORTED_FILTER = "支持文档 (*.pdf *.doc *.docx *.ppt *.pptx *.xls *.xlsx *.txt *.md *.csv *.html *.xml *.json);;所有文件 (*)"


@dataclass
class QueryPayload:
    question: str
    session_id: str
    source_files: List[str]
    file_types: List[str]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "question": self.question,
            "sessionId": self.session_id,
            "sourceFiles": self.source_files,
            "fileTypes": self.file_types,
        }


class StreamWorker(QThread):
    token_received = pyqtSignal(str)
    meta_received = pyqtSignal(dict)
    error_received = pyqtSignal(str)
    done = pyqtSignal()

    def __init__(self, api_base: str, payload: Dict[str, Any]):
        super().__init__()
        self.api_base = api_base
        self.payload = payload

    def run(self):
        event_name: Optional[str] = None
        data_lines: List[str] = []

        try:
            with requests.post(
                f"{self.api_base}/query/stream",
                json=self.payload,
                stream=True,
                timeout=(10, 300),
                headers={"Accept": "text/event-stream; charset=utf-8"},
            ) as resp:
                if resp.status_code >= 400:
                    self.error_received.emit(resp.text or f"HTTP {resp.status_code}")
                    return

                for raw in resp.iter_lines(decode_unicode=False):
                    if raw is None:
                        continue
                    line = raw.decode("utf-8", errors="replace").strip() if isinstance(raw, bytes) else str(raw).strip()

                    if line == "":
                        if event_name and data_lines:
                            data = "\n".join(data_lines)
                            if event_name == "token":
                                self.token_received.emit(data)
                            elif event_name == "meta":
                                try:
                                    self.meta_received.emit(json.loads(data))
                                except Exception:
                                    self.meta_received.emit({"raw": data})
                            elif event_name == "error":
                                self.error_received.emit(data)
                            elif event_name == "done":
                                return
                        event_name = None
                        data_lines = []
                        continue

                    if line.startswith(":"):
                        continue
                    if line.startswith("event:"):
                        event_name = line.replace("event:", "", 1).strip()
                        continue
                    if line.startswith("data:"):
                        data_lines.append(line.replace("data:", "", 1).strip())

        except requests.exceptions.ConnectionError:
            self.error_received.emit("无法连接后端（localhost:8080）。请先启动 Spring Boot 服务。")
        except Exception as e:
            self.error_received.emit(str(e))
        finally:
            self.done.emit()


class RAGDesktop(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("RAG 智能文档助手 Pro")
        self.resize(1520, 940)

        self.session_id = str(uuid.uuid4())
        self.current_stream_worker = None

        self._apply_theme()

        root = QWidget()
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        layout.setContentsMargins(14, 14, 14, 14)
        layout.setSpacing(10)

        self.api_input = QLineEdit(API_BASE_DEFAULT)
        self.session_input = QLineEdit(self.session_id)

        top_card = QGroupBox("连接与会话")
        top_layout = QHBoxLayout(top_card)
        top_layout.addWidget(QLabel("API Base"))
        top_layout.addWidget(self.api_input, 3)
        top_layout.addWidget(QLabel("Current Session"))
        top_layout.addWidget(self.session_input, 2)

        self.test_conn_btn = QPushButton("测试连接")
        self.test_conn_btn.clicked.connect(self.test_connection)
        self.new_session_btn = QPushButton("新建会话")
        self.new_session_btn.clicked.connect(self.create_session_remote)
        self.load_sessions_btn = QPushButton("刷新会话")
        self.load_sessions_btn.clicked.connect(self.refresh_sessions)

        top_layout.addWidget(self.test_conn_btn)
        top_layout.addWidget(self.new_session_btn)
        top_layout.addWidget(self.load_sessions_btn)
        layout.addWidget(top_card)

        tabs = QTabWidget()
        tabs.addTab(self.build_chat_tab(), "智能问答")
        tabs.addTab(self.build_docs_tab(), "文档管理")
        layout.addWidget(tabs)

        self.status_label = QLabel("状态：就绪")
        layout.addWidget(self.status_label)

        self.refresh_documents()
        self.refresh_sessions()
        self.refresh_filter_options()

    def _apply_theme(self):
        self.setFont(QFont("Microsoft YaHei UI", 10))
        self.setStyleSheet(
            """
            QMainWindow, QWidget { background: #eaf2ff; color: #14233d; }
            QGroupBox {
                border: 1px solid #b9cbea;
                border-radius: 12px;
                margin-top: 10px;
                padding-top: 10px;
                background: #f6faff;
                font-weight: 700;
            }
            QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; color: #2457aa; }
            QLineEdit, QTextEdit, QPlainTextEdit, QListWidget, QTableWidget {
                background: #ffffff;
                color: #14233d;
                border: 1px solid #b8caea;
                border-radius: 10px;
                padding: 8px;
                selection-background-color: #b8d4ff;
            }
            QPushButton {
                background: #2f6feb;
                color: #ffffff;
                border: 1px solid #2b62cc;
                border-radius: 10px;
                padding: 8px 14px;
                font-weight: 700;
            }
            QPushButton:hover { background: #1f5fe0; }
            QPushButton:disabled { background: #cdd6e5; border-color: #cdd6e5; color: #6b7280; }
            QTabWidget::pane { border: 1px solid #b9cbea; border-radius: 10px; background: #f8fbff; }
            QTabBar::tab {
                background: #dfeafc;
                border: 1px solid #b9cbea;
                border-bottom: none;
                padding: 8px 14px;
                border-top-left-radius: 8px;
                border-top-right-radius: 8px;
                margin-right: 4px;
                color: #25467a;
            }
            QTabBar::tab:selected { background: #f8fbff; color: #10274a; }
            QHeaderView::section { background: #e7f0ff; color: #234067; border: 1px solid #b9cbea; padding: 6px; }
            """
        )

    def build_chat_tab(self) -> QWidget:
        panel = QWidget()
        panel_layout = QVBoxLayout(panel)
        splitter = QSplitter(Qt.Horizontal)

        left = QWidget()
        left_layout = QVBoxLayout(left)

        self.chat_history = QTextEdit()
        self.chat_history.setReadOnly(True)
        self.question_input = QPlainTextEdit()
        self.question_input.setPlaceholderText("输入问题后点击发送（流式）")
        self.question_input.setFixedHeight(120)

        row = QHBoxLayout()
        self.send_btn = QPushButton("发送（流式）")
        self.send_btn.clicked.connect(self.ask_stream)
        self.send_sync_btn = QPushButton("发送（非流式）")
        self.send_sync_btn.clicked.connect(self.ask_sync)
        row.addWidget(self.send_btn)
        row.addWidget(self.send_sync_btn)
        row.addStretch(1)

        left_layout.addWidget(self.chat_history)
        left_layout.addWidget(self.question_input)
        left_layout.addLayout(row)

        right = QWidget()
        right_layout = QVBoxLayout(right)

        session_box = QGroupBox("会话管理")
        session_layout = QVBoxLayout(session_box)
        self.session_list = QListWidget()
        self.session_list.itemDoubleClicked.connect(self.switch_session_from_list)

        session_row = QHBoxLayout()
        self.load_session_btn = QPushButton("加载会话内容")
        self.load_session_btn.clicked.connect(self.load_selected_session)
        self.delete_session_btn = QPushButton("删除会话")
        self.delete_session_btn.clicked.connect(self.delete_selected_session)
        session_row.addWidget(self.load_session_btn)
        session_row.addWidget(self.delete_session_btn)
        session_layout.addWidget(self.session_list)
        session_layout.addLayout(session_row)

        filter_box = QGroupBox("检索范围（列表多选）")
        filter_layout = QGridLayout(filter_box)
        self.source_filter_list = QListWidget()
        self.source_filter_list.setSelectionMode(QAbstractItemView.MultiSelection)
        self.type_filter_list = QListWidget()
        self.type_filter_list.setSelectionMode(QAbstractItemView.MultiSelection)
        self.refresh_filters_btn = QPushButton("刷新检索选项")
        self.refresh_filters_btn.clicked.connect(self.refresh_filter_options)
        self.clear_filters_btn = QPushButton("清空选择")
        self.clear_filters_btn.clicked.connect(self.clear_filter_selection)
        filter_layout.addWidget(QLabel("sourceFiles"), 0, 0)
        filter_layout.addWidget(self.source_filter_list, 1, 0)
        filter_layout.addWidget(QLabel("fileTypes"), 0, 1)
        filter_layout.addWidget(self.type_filter_list, 1, 1)
        filter_layout.addWidget(self.refresh_filters_btn, 2, 0)
        filter_layout.addWidget(self.clear_filters_btn, 2, 1)

        citation_box = QGroupBox("引用来源")
        citation_layout = QVBoxLayout(citation_box)
        self.citation_list = QListWidget()
        citation_layout.addWidget(self.citation_list)

        right_layout.addWidget(session_box, 4)
        right_layout.addWidget(filter_box, 4)
        right_layout.addWidget(citation_box, 5)

        splitter.addWidget(left)
        splitter.addWidget(right)
        splitter.setSizes([960, 520])
        panel_layout.addWidget(splitter)
        return panel

    def build_docs_tab(self) -> QWidget:
        panel = QWidget()
        layout = QVBoxLayout(panel)

        row = QHBoxLayout()
        self.upload_btn = QPushButton("上传文档（可多选）")
        self.upload_btn.clicked.connect(self.upload_documents_unified)
        self.upload_multi_btn = QPushButton("批量上传（强制多文件）")
        self.upload_multi_btn.clicked.connect(self.upload_documents_batch)
        self.refresh_btn = QPushButton("刷新列表")
        self.refresh_btn.clicked.connect(self.refresh_documents)
        self.delete_btn = QPushButton("删除选中文档")
        self.delete_btn.clicked.connect(self.delete_selected_document)

        row.addWidget(self.upload_btn)
        row.addWidget(self.upload_multi_btn)
        row.addWidget(self.refresh_btn)
        row.addWidget(self.delete_btn)
        row.addStretch(1)

        self.doc_table = QTableWidget(0, 4)
        self.doc_table.setHorizontalHeaderLabels(["ID", "文件名", "路径", "Chunk数"])
        self.doc_table.setSelectionBehavior(self.doc_table.SelectRows)
        self.doc_table.setEditTriggers(self.doc_table.NoEditTriggers)
        self.doc_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        self.doc_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.doc_table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.doc_table.horizontalHeader().setSectionResizeMode(3, QHeaderView.ResizeToContents)

        layout.addLayout(row)
        layout.addWidget(self.doc_table)
        return panel

    def get_api_base(self) -> str:
        return self.api_input.text().strip().rstrip("/")

    def get_session_id(self) -> str:
        s = self.session_input.text().strip()
        return s if s else "default"

    def set_status(self, text: str):
        self.status_label.setText(f"状态：{text}")

    def test_connection(self):
        try:
            resp = requests.get(f"{self.get_api_base()}/documents", timeout=8)
            if resp.status_code >= 400:
                raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
            self.set_status("后端连接正常")
            QMessageBox.information(self, "连接成功", "后端可访问。")
        except Exception as e:
            self.set_status("后端连接失败")
            QMessageBox.critical(self, "连接失败", str(e))

    def create_session_remote(self):
        try:
            resp = requests.post(f"{self.get_api_base()}/sessions", json={}, timeout=20)
            if resp.status_code >= 400:
                raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
            self.session_id = resp.json().get("sessionId", str(uuid.uuid4()))
            self.session_input.setText(self.session_id)
            self.chat_history.clear()
            self.citation_list.clear()
            self.refresh_sessions()
            self.set_status("已创建新会话")
        except Exception as e:
            QMessageBox.critical(self, "失败", str(e))

    def refresh_sessions(self):
        try:
            resp = requests.get(f"{self.get_api_base()}/sessions", timeout=20)
            if resp.status_code >= 400:
                return
            sessions = resp.json()
            self.session_list.clear()
            for s in sessions:
                sid = s.get("sessionId", "")
                name = s.get("name", sid[:8])
                turns = s.get("turns", 0)
                item = QListWidgetItem(f"{name} | {sid[:8]} | {turns}轮")
                item.setData(Qt.UserRole, sid)
                self.session_list.addItem(item)
        except Exception:
            pass

    def switch_session_from_list(self, item: QListWidgetItem):
        sid = item.data(Qt.UserRole)
        if sid:
            self.session_input.setText(sid)
            self.load_session_by_id(sid)

    def load_selected_session(self):
        item = self.session_list.currentItem()
        if not item:
            QMessageBox.warning(self, "提示", "请先选择会话")
            return
        self.load_session_by_id(item.data(Qt.UserRole))

    def load_session_by_id(self, sid: str):
        try:
            resp = requests.get(f"{self.get_api_base()}/sessions/{sid}", timeout=20)
            if resp.status_code >= 400:
                raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
            data = resp.json()
            self.chat_history.clear()
            for t in data.get("history", []):
                prefix = "你" if t.get("role") == "user" else "助手"
                self.chat_history.append(f"[{prefix}] {t.get('content', '')}\n")
            self.session_input.setText(sid)
            self.set_status("会话已加载")
        except Exception as e:
            QMessageBox.critical(self, "加载失败", str(e))

    def delete_selected_session(self):
        item = self.session_list.currentItem()
        if not item:
            QMessageBox.warning(self, "提示", "请先选择会话")
            return
        sid = item.data(Qt.UserRole)
        if QMessageBox.question(self, "确认", f"确认删除会话 {sid[:8]} ?") != QMessageBox.Yes:
            return
        try:
            requests.delete(f"{self.get_api_base()}/sessions/{sid}", timeout=20)
            self.refresh_sessions()
            self.set_status("会话已删除")
        except Exception as e:
            QMessageBox.critical(self, "删除失败", str(e))

    def selected_list_values(self, widget: QListWidget) -> List[str]:
        return [i.text().strip() for i in widget.selectedItems()]

    def build_payload(self) -> QueryPayload:
        return QueryPayload(
            question=self.question_input.toPlainText().strip(),
            session_id=self.get_session_id(),
            source_files=self.selected_list_values(self.source_filter_list),
            file_types=self.selected_list_values(self.type_filter_list),
        )

    def ask_sync(self):
        payload = self.build_payload()
        if not payload.question:
            QMessageBox.warning(self, "提示", "问题不能为空")
            return
        self.chat_history.append(f"\n[你] {payload.question}\n")
        self.chat_history.append("[助手] 思考中...\n")
        self.question_input.clear()

        try:
            resp = requests.post(f"{self.get_api_base()}/query", json=payload.to_dict(), timeout=180)
            if resp.status_code >= 400:
                raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
            data = resp.json()
            self.chat_history.append(data.get("answer", "") + "\n")
            self.render_citations(data.get("citations", []))
            self.refresh_sessions()
        except Exception as e:
            QMessageBox.critical(self, "请求失败", str(e))

    def ask_stream(self):
        payload = self.build_payload()
        if not payload.question:
            QMessageBox.warning(self, "提示", "问题不能为空")
            return
        if self.current_stream_worker and self.current_stream_worker.isRunning():
            QMessageBox.information(self, "提示", "已有流式请求在进行中")
            return

        self.chat_history.append(f"\n[你] {payload.question}\n")
        self.chat_history.append("[助手] ")
        self.question_input.clear()

        self.send_btn.setEnabled(False)
        self.send_sync_btn.setEnabled(False)

        self.current_stream_worker = StreamWorker(self.get_api_base(), payload.to_dict())
        self.current_stream_worker.token_received.connect(self.on_stream_token)
        self.current_stream_worker.meta_received.connect(self.on_stream_meta)
        self.current_stream_worker.error_received.connect(self.on_stream_error)
        self.current_stream_worker.done.connect(self.on_stream_done)
        self.current_stream_worker.start()

    def on_stream_token(self, token: str):
        cursor = self.chat_history.textCursor()
        cursor.movePosition(cursor.End)
        cursor.insertText(token)
        self.chat_history.setTextCursor(cursor)
        self.chat_history.ensureCursorVisible()

    def on_stream_meta(self, meta: dict):
        self.render_citations(meta.get("citations", []) if isinstance(meta, dict) else [])

    def on_stream_error(self, msg: str):
        self.chat_history.append(f"\n[错误] {msg}\n")

    def on_stream_done(self):
        self.send_btn.setEnabled(True)
        self.send_sync_btn.setEnabled(True)
        self.chat_history.append("\n")
        self.refresh_sessions()

    def render_citations(self, citations: List[dict]):
        self.citation_list.clear()
        for c in citations:
            self.citation_list.addItem(QListWidgetItem(f"[{c.get('index', '-')}] {c.get('source', 'unknown')}\n{c.get('snippet', '')}"))

    def refresh_filter_options(self):
        try:
            resp = requests.get(f"{self.get_api_base()}/filters/options", timeout=30)
            if resp.status_code >= 400:
                return
            data = resp.json()
            self.source_filter_list.clear()
            self.type_filter_list.clear()
            for s in data.get("sourceFiles", []):
                self.source_filter_list.addItem(QListWidgetItem(str(s)))
            for t in data.get("fileTypes", []):
                self.type_filter_list.addItem(QListWidgetItem(str(t)))
        except Exception:
            pass

    def clear_filter_selection(self):
        self.source_filter_list.clearSelection()
        self.type_filter_list.clearSelection()

    def refresh_documents(self):
        try:
            resp = requests.get(f"{self.get_api_base()}/documents", timeout=30)
            if resp.status_code >= 400:
                raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
            docs = resp.json()
            self.doc_table.setRowCount(0)
            for i, doc in enumerate(docs):
                self.doc_table.insertRow(i)
                self.doc_table.setItem(i, 0, QTableWidgetItem(str(doc.get("id", ""))))
                self.doc_table.setItem(i, 1, QTableWidgetItem(str(doc.get("filename", ""))))
                self.doc_table.setItem(i, 2, QTableWidgetItem(str(doc.get("fullPath", ""))))
                self.doc_table.setItem(i, 3, QTableWidgetItem(str(doc.get("chunkCount", ""))))
            self.refresh_filter_options()
            self.set_status(f"文档列表已刷新，共 {len(docs)} 条")
        except Exception as e:
            self.set_status("加载文档失败")
            QMessageBox.critical(self, "加载失败", str(e))

    def _build_file_tuples(self, file_paths: List[str], field_name: str) -> (List[Any], List[Any]):
        files = []
        handles = []
        for p in file_paths:
            h = open(p, "rb")
            handles.append(h)
            files.append((field_name, (os.path.basename(p), h)))
        return files, handles

    def _handle_upload_response(self, resp: requests.Response, title: str):
        if resp.status_code >= 400:
            raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
        payload = resp.json() if "application/json" in resp.headers.get("Content-Type", "") else {"raw": resp.text}
        QMessageBox.information(self, title, json.dumps(payload, ensure_ascii=False, indent=2))
        self.refresh_documents()

    def upload_documents_unified(self):
        file_paths, _ = QFileDialog.getOpenFileNames(self, "选择文档（可多选）", "", SUPPORTED_FILTER)
        if not file_paths:
            return
        try:
            self.set_status(f"上传中（{len(file_paths)}）")
            field = "file" if len(file_paths) == 1 else "files"
            files, handles = self._build_file_tuples(file_paths, field)
            try:
                resp = requests.post(f"{self.get_api_base()}/upload", files=files, timeout=900)
            finally:
                for h in handles:
                    h.close()
            self._handle_upload_response(resp, "上传结果")
        except Exception as e:
            self.set_status("上传失败")
            QMessageBox.critical(self, "上传失败", str(e))

    def upload_documents_batch(self):
        file_paths, _ = QFileDialog.getOpenFileNames(self, "选择多个文档（批量接口）", "", SUPPORTED_FILTER)
        if not file_paths:
            return
        if len(file_paths) < 2:
            QMessageBox.information(self, "提示", "批量上传建议至少选择 2 个文件；单文件请用“上传文档（可多选）”。")
            return
        try:
            self.set_status(f"批量上传中（{len(file_paths)}）")
            files, handles = self._build_file_tuples(file_paths, "files")
            try:
                resp = requests.post(f"{self.get_api_base()}/upload/batch", files=files, timeout=900)
            finally:
                for h in handles:
                    h.close()
            self._handle_upload_response(resp, "批量上传结果")
        except Exception as e:
            self.set_status("批量上传失败")
            QMessageBox.critical(self, "批量上传失败", str(e))

    def delete_selected_document(self):
        row = self.doc_table.currentRow()
        if row < 0:
            QMessageBox.warning(self, "提示", "请先选择一行文档")
            return

        filename_item = self.doc_table.item(row, 1)
        if not filename_item:
            return

        filename = filename_item.text().strip()
        if QMessageBox.question(self, "确认删除", f"确认删除文档 {filename} ?") != QMessageBox.Yes:
            return

        try:
            resp = requests.delete(f"{self.get_api_base()}/documents", params={"filename": filename}, timeout=60)
            if resp.status_code >= 400:
                raise RuntimeError(resp.text or f"HTTP {resp.status_code}")
            QMessageBox.information(self, "删除成功", json.dumps(resp.json(), ensure_ascii=False, indent=2))
            self.refresh_documents()
        except Exception as e:
            QMessageBox.critical(self, "删除失败", str(e))


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("RAG Desktop")
    win = RAGDesktop()
    win.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
