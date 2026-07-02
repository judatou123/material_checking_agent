package org.example.agent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.config.DifyProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final DifyProperties dify;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OCR 专用线程池（由 OcrConfig 注入），控制并行任务数 */
    @Qualifier("ocrExecutor")
    private final ExecutorService ocrExecutor;

    private static final String OCR_URL = "http://localhost:8866/ocr";
    private static final String OCR_PDF_URL = "http://localhost:8866/ocr-pdf";
    private static final long SSE_TIMEOUT = 300000L; // 5 分钟

    // ═══════════════════════════════════════════════════════════
    // /api/chat —— 每个文件独立 OCR → Dify 流水线，多文件并行
    // ═══════════════════════════════════════════════════════════

    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter chat(
            @RequestParam("question") String question,
            @RequestParam(value = "files", required = false) List<MultipartFile> files
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            Path tempDir = null;
            try {
                // Step 0: 文件落盘
                tempDir = writeFilesToDisk(files);
                List<FileInfo> fileInfos = scanTempDir(tempDir);
                if (fileInfos.isEmpty()) {
                    sendEvent(emitter, "error", Map.of("message", "未上传有效文件"));
                    emitter.complete();
                    return;
                }

                int total = fileInfos.size();

                // Step 1: 每个文件独立流水线 OCR→Dify，并行执行
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (FileInfo info : fileInfos) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                            processSingleFileChat(info, total, question, emitter),
                            ocrExecutor);
                    futures.add(future);
                }

                // Step 2: 等待全部文件处理完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // Step 3: 通知前端全部完成
                sendEvent(emitter, "all_done", Map.of());
                emitter.complete();

            } catch (Exception e) {
                log.error("Chat error", e);
                try { sendEvent(emitter, "error", Map.of("message", e.getMessage())); } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                deleteTempDir(tempDir);
            }
        });

        emitter.onTimeout(() -> log.warn("SSE timeout"));
        emitter.onError(ex -> log.error("SSE error", ex));
        return emitter;
    }

    /**
     * 单个文件的完整流水线：读盘 → OCR → Dify chat-messages（流式）
     */
    private void processSingleFileChat(FileInfo info, int total, String question, SseEmitter emitter) {
        try {
            // Phase 1: OCR
            sendFileStatus(emitter, info, total, info.name + " 正在识别文字…");

            String ocrText;
            if (info.isPdf) {
                ocrText = ocrPdf(info.path, info.name);
            } else {
                ocrText = ocrImage(info.path, info.name);
            }

            // OCR 完即删文件
            try { Files.deleteIfExists(info.path); } catch (Exception ignored) {}

            sendFileStatus(emitter, info, total, info.name + " 识别完成，AI审核中…");

            // Phase 2: 流式调 Dify，每个 token 带上 file 信息
            String answer = streamDifyPerFile(question, ocrText, info, total, emitter);

            // Phase 3: 单个文件完成
            sendEvent(emitter, "done", Map.of(
                    "file", info.name,
                    "index", info.index,
                    "total", total,
                    "answer", answer
            ));

        } catch (Exception e) {
            log.error("文件处理失败: {}", info.name, e);
            try { Files.deleteIfExists(info.path); } catch (Exception ignored) {}
            sendEvent(emitter, "done", Map.of(
                    "file", info.name,
                    "index", info.index,
                    "total", total,
                    "answer", "处理失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 流式调用 Dify chat-messages，为单个文件逐 token 转发。
     * 所有 SSE 事件自动附带 file/index/total。
     */
    private String streamDifyPerFile(String question, String ocrText,
                                      FileInfo info, int total, SseEmitter emitter) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", Map.of("question", question, "ocr_text", ocrText));
        body.put("query", question);
        body.put("response_mode", "streaming");
        body.put("user", "admin");

        String jsonBody = objectMapper.writeValueAsString(body);

        HttpURLConnection conn = (HttpURLConnection) URI.create(dify.getApiUrl() + "/chat-messages").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + dify.getApiKey());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(600000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder fullAnswer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith("data: ")) {
                    String json = line.substring(6);
                    try {
                        Map<String, Object> event = objectMapper.readValue(json, new TypeReference<>() {});
                        String eventType = (String) event.get("event");

                        if ("message".equals(eventType)) {
                            String token = (String) event.get("answer");
                            if (token != null && !token.isEmpty()) {
                                fullAnswer.append(token);
                                sendEvent(emitter, "token", fileMeta(info, total, "t", token));
                            }
                        } else if ("message_end".equals(eventType)) {
                            break;
                        } else if ("error".equals(eventType)) {
                            String msg = Objects.toString(event.getOrDefault("message", "Dify 返回错误"), "");
                            throw new RuntimeException(msg);
                        }
                        // workflow_started/finished/node_* 忽略（chat-messages 不走 workflow 模式）
                    } catch (RuntimeException re) { throw re; }
                    catch (Exception e) {
                        log.warn("Failed to parse Dify SSE line: {}", line, e);
                    }
                }
            }
        }
        conn.disconnect();

        return normalizeAnswer(stripThink(fullAnswer.toString()));
    }

    // ═══════════════════════════════════════════════════════════
    // /api/material-entry —— 每个文件独立 OCR → Dify Workflow
    // ═══════════════════════════════════════════════════════════

    @PostMapping(value = "/material-entry", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter materialEntry(
            @RequestParam(value = "question", required = false, defaultValue = "") String question,
            @RequestParam(value = "files", required = false) List<MultipartFile> files
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            Path tempDir = null;
            try {
                tempDir = writeFilesToDisk(files);
                List<FileInfo> fileInfos = scanTempDir(tempDir);
                if (fileInfos.isEmpty()) {
                    sendEvent(emitter, "error", Map.of("message", "未上传有效文件"));
                    emitter.complete();
                    return;
                }

                int total = fileInfos.size();
                String userQuestion = (question == null || question.isBlank()) ? "材料录入" : question;

                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (FileInfo info : fileInfos) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                            processSingleFileWorkflow(info, total, userQuestion, emitter),
                            ocrExecutor);
                    futures.add(future);
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                sendEvent(emitter, "all_done", Map.of());
                emitter.complete();

            } catch (Exception e) {
                log.error("Material entry error", e);
                try { sendEvent(emitter, "error", Map.of("message", e.getMessage())); } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                deleteTempDir(tempDir);
            }
        });

        emitter.onTimeout(() -> log.warn("Material Entry SSE timeout"));
        emitter.onError(ex -> log.error("Material Entry SSE error", ex));
        return emitter;
    }

    /**
     * 单个文件的完整流水线：读盘 → OCR → Dify Workflow（流式）
     */
    private void processSingleFileWorkflow(FileInfo info, int total, String question, SseEmitter emitter) {
        try {
            sendFileStatus(emitter, info, total, info.name + " 正在识别文字…");

            String ocrText;
            if (info.isPdf) {
                ocrText = ocrPdf(info.path, info.name);
            } else {
                ocrText = ocrImage(info.path, info.name);
            }

            try { Files.deleteIfExists(info.path); } catch (Exception ignored) {}

            sendFileStatus(emitter, info, total, info.name + " 识别完成，AI提取中…");

            String answer = streamDifyWorkflowPerFile(ocrText, question, info, total, emitter);

            sendEvent(emitter, "done", Map.of(
                    "file", info.name,
                    "index", info.index,
                    "total", total,
                    "answer", answer
            ));

        } catch (Exception e) {
            log.error("文件处理失败: {}", info.name, e);
            try { Files.deleteIfExists(info.path); } catch (Exception ignored) {}
            sendEvent(emitter, "done", Map.of(
                    "file", info.name,
                    "index", info.index,
                    "total", total,
                    "answer", "处理失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 流式调用 Dify Workflow，为单个文件逐 token 转发。
     */
    private String streamDifyWorkflowPerFile(String ocrText, String question,
                                              FileInfo info, int total, SseEmitter emitter) throws Exception {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("ocr_text", ocrText);
        inputs.put("user_question", question != null ? question : "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "streaming");
        body.put("user", "admin");

        String jsonBody = objectMapper.writeValueAsString(body);

        HttpURLConnection conn = (HttpURLConnection) URI.create(dify.getApiUrl() + "/workflows/run").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + dify.getMaterialEntryApiKey());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(600000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder fullAnswer = new StringBuilder();
        boolean hadTextChunk = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith("data: ")) {
                    String json = line.substring(6);
                    try {
                        Map<String, Object> event = objectMapper.readValue(json, new TypeReference<>() {});
                        String eventType = (String) event.get("event");

                        if ("text_chunk".equals(eventType)) {
                            hadTextChunk = true;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inner = (Map<String, Object>) event.get("data");
                            if (inner != null) {
                                String token = (String) inner.get("text");
                                if (token != null && !token.isEmpty()) {
                                    fullAnswer.append(token);
                                    sendEvent(emitter, "token", fileMeta(info, total, "t", token));
                                }
                            }

                        } else if ("workflow_finished".equals(eventType)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inner = (Map<String, Object>) event.get("data");
                            if (inner != null) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> outputs = (Map<String, Object>) inner.get("outputs");
                                if (outputs != null) {
                                    String outputText = (String) outputs.getOrDefault("text", "");
                                    if (!outputText.isEmpty()) {
                                        if (!hadTextChunk) {
                                            fullAnswer = new StringBuilder(outputText);
                                        } else if (fullAnswer.isEmpty()) {
                                            fullAnswer.append(outputText);
                                        }
                                    }
                                }
                            }
                            break;

                        } else if ("error".equals(eventType)) {
                            String msg = Objects.toString(event.getOrDefault("message", "Dify 返回错误"), "");
                            throw new RuntimeException(msg);

                        } else if ("workflow_started".equals(eventType)) {
                            log.info("Dify workflow started for {}", info.name);
                            sendFileStatus(emitter, info, total, "AI 工作流已启动（" + info.name + "）");

                        } else if ("node_started".equals(eventType)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inner = (Map<String, Object>) event.get("data");
                            String nodeTitle = inner != null ? (String) inner.getOrDefault("title", "") : "";
                            String statusText = mapNodeTitle(nodeTitle);
                            if (!statusText.isEmpty()) {
                                log.info("Dify node: {} → {}", nodeTitle, statusText);
                                sendFileStatus(emitter, info, total, statusText + "（" + info.name + "）");
                            }

                        } else if ("node_finished".equals(eventType)) {
                            log.debug("Dify node finished: {}", event.get("node_id"));
                        }
                    } catch (RuntimeException re) { throw re; }
                    catch (Exception e) {
                        log.warn("Failed to parse Dify SSE line: {}", line, e);
                    }
                }
            }
        }
        conn.disconnect();

        String finalAnswer = normalizeAnswer(stripThink(fullAnswer.toString()));

        // 无 text_chunk 时模拟流式
        if (!hadTextChunk && !finalAnswer.isEmpty()) {
            simulateStreamingPerFile(emitter, finalAnswer, info, total);
        }

        return finalAnswer;
    }

    // ═══════════════════════════════════════════════════════════
    // 磁盘 I/O 工具
    // ═══════════════════════════════════════════════════════════

    private Path writeFilesToDisk(List<MultipartFile> files) throws Exception {
        if (files == null || files.isEmpty()) return null;
        Path tempDir = Files.createTempDirectory("ocr-uploads-");
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String name = file.getOriginalFilename();
            if (name == null) name = "unknown";
            Path dest = tempDir.resolve(System.currentTimeMillis() + "_" + name);
            file.transferTo(dest);
            log.debug("File written to disk: {}", dest);
        }
        return tempDir;
    }

    private List<FileInfo> scanTempDir(Path tempDir) {
        if (tempDir == null) return Collections.emptyList();
        java.io.File[] diskFiles = tempDir.toFile().listFiles();
        if (diskFiles == null) return Collections.emptyList();
        Arrays.sort(diskFiles, java.util.Comparator.comparing(java.io.File::getName));

        List<FileInfo> infos = new ArrayList<>();
        for (int i = 0; i < diskFiles.length; i++) {
            String fullName = diskFiles[i].getName();
            int underscoreIdx = fullName.indexOf('_');
            String originalName = underscoreIdx >= 0 ? fullName.substring(underscoreIdx + 1) : fullName;
            infos.add(new FileInfo(i, diskFiles[i].toPath(), originalName, isPdfByName(originalName)));
        }
        return infos;
    }

    private void deleteTempDir(Path tempDir) {
        if (tempDir == null) return;
        try {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception e) {
            log.warn("Failed to clean temp dir: {}", tempDir, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // OCR
    // ═══════════════════════════════════════════════════════════

    private String ocrImage(Path filePath, String filename) throws Exception {
        byte[] bytes = Files.readAllBytes(filePath);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        bytes = null;

        String reqBody = "{\"image\":\"" + base64 + "\"}";
        base64 = null;

        HttpURLConnection conn = (HttpURLConnection) URI.create(OCR_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.getBytes(StandardCharsets.UTF_8));
        }

        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        Map<String, Object> result = objectMapper.readValue(resp, new TypeReference<>() {});
        String text = (String) result.getOrDefault("full_text", "");
        return "【" + filename + "】\n" + text + "\n\n";
    }

    private String ocrPdf(Path filePath, String filename) throws Exception {
        byte[] bytes = Files.readAllBytes(filePath);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        bytes = null;

        String reqBody = "{\"pdf\":\"" + base64 + "\", \"dpi\": 200}";
        base64 = null;

        HttpURLConnection conn = (HttpURLConnection) URI.create(OCR_PDF_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(300000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.getBytes(StandardCharsets.UTF_8));
        }

        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        Map<String, Object> result = objectMapper.readValue(resp, new TypeReference<>() {});

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(filename).append("】\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) result.get("pages");
        if (pages != null) {
            for (Map<String, Object> page : pages) {
                int pageNum = (int) page.getOrDefault("page", 0);
                String text = (String) page.getOrDefault("full_text", "");
                sb.append("--- 第").append(pageNum).append("页 ---\n");
                sb.append(text).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // SSE / 工具
    // ═══════════════════════════════════════════════════════════

    /** 发送 SSE 状态事件，携带文件信息 */
    private void sendFileStatus(SseEmitter emitter, FileInfo info, int total, String text) {
        sendEvent(emitter, "status", fileMeta(info, total, "text", text));
    }

    /** 构建带 file/index/total 的元数据 Map */
    private Map<String, Object> fileMeta(FileInfo info, int total, String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        map.put("file", info.name);
        map.put("index", info.index);
        map.put("total", total);
        return map;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("Failed to send SSE event '{}': {}", name, e.getMessage());
        }
    }

    /** 模拟逐字流式（带文件信息） */
    private void simulateStreamingPerFile(SseEmitter emitter, String text, FileInfo info, int total) {
        int i = 0;
        while (i < text.length()) {
            int len = 2 + (int) (Math.random() * 4);
            if (i + len > text.length()) len = text.length() - i;
            String chunk = text.substring(i, i + len);
            i += len;
            try {
                sendEvent(emitter, "token", fileMeta(info, total, "t", chunk));
                Thread.sleep(20 + (int) (Math.random() * 11));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                break;
            }
        }
    }

    private String stripThink(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        String trimmed = answer.trim();
        if ("7".equals(trimmed)) {
            return "相关功能还在开发中，敬请期待…";
        }
        return answer;
    }

    private String mapNodeTitle(String title) {
        if (title == null) return "";
        return switch (title) {
            case "Material_Classifier" -> "正在分析材料类型…";
            case "Field_Extractor"    -> "正在提取关键字段…";
            case "Normalize_Fields"   -> "正在标准化日期与字段…";
            case "Format_Report"      -> "正在生成结构化报告…";
            case "Final_Output"       -> "正在输出结果…";
            default -> "";
        };
    }

    private boolean isPdfByName(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "ocr_api", OCR_URL, "dify_api", dify.getApiUrl());
    }

    // ═══════════════════════════════════════════════════════════
    // 内部数据结构
    // ═══════════════════════════════════════════════════════════

    /** 磁盘上的文件信息 */
    private record FileInfo(int index, Path path, String name, boolean isPdf) {}
}
