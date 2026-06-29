package org.example.agent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.config.DifyProperties;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final DifyProperties dify;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String OCR_URL = "http://localhost:8866/ocr";
    private static final String OCR_PDF_URL = "http://localhost:8866/ocr-pdf";
    private static final long SSE_TIMEOUT = 300000L; // 5 分钟

    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter chat(
            @RequestParam("question") String question,
            @RequestParam(value = "files", required = false) List<MultipartFile> files
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: OCR
                sendEvent(emitter, "status", Map.of("text", "正在识别图片文字…"));
                String ocrText = runOcrSync(files);
                log.info("OCR done: {} chars", ocrText.length());

                // Step 2: 通知前端开始 AI 审核
                sendEvent(emitter, "status", Map.of("text", "正在调用AI审核…"));

                // Step 3: 流式调用 Dify
                streamDify(question, ocrText, emitter);

            } catch (Exception e) {
                log.error("Chat error", e);
                try {
                    sendEvent(emitter, "error", Map.of("message", e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        // 清理回调
        emitter.onTimeout(() -> log.warn("SSE timeout"));
        emitter.onError(ex -> log.error("SSE error", ex));

        return emitter;
    }

    /**
     * 同步 OCR（支持图片和 PDF，按文件类型路由到不同 PaddleOCR 端点）
     */
    private String runOcrSync(List<MultipartFile> files) throws Exception {
        if (files == null || files.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            try {
                if (isPdf(file)) {
                    sb.append(ocrPdf(file));
                } else {
                    sb.append(ocrImage(file));
                }
            } catch (Exception e) {
                log.error("OCR failed: {}", file.getOriginalFilename(), e);
                sb.append("【").append(file.getOriginalFilename()).append("】\n[识别失败]\n\n");
            }
        }
        return sb.toString();
    }

    /** 判断文件是否为 PDF */
    private boolean isPdf(MultipartFile file) {
        String contentType = file.getContentType();
        if ("application/pdf".equals(contentType)) return true;
        String name = file.getOriginalFilename();
        return name != null && name.toLowerCase().endsWith(".pdf");
    }

    /** 图片 OCR：POST /ocr */
    private String ocrImage(MultipartFile file) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(file.getBytes());
        String reqBody = "{\"image\":\"" + base64 + "\"}";

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
        return "【" + file.getOriginalFilename() + "】\n" + text + "\n\n";
    }

    /** PDF OCR：POST /ocr-pdf，按页聚合结果 */
    private String ocrPdf(MultipartFile file) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(file.getBytes());
        String reqBody = "{\"pdf\":\"" + base64 + "\", \"dpi\": 200}";

        HttpURLConnection conn = (HttpURLConnection) URI.create(OCR_PDF_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(300000); // PDF 多页可能较慢

        try (OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.getBytes(StandardCharsets.UTF_8));
        }

        String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        Map<String, Object> result = objectMapper.readValue(resp, new TypeReference<>() {});

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(file.getOriginalFilename()).append("】\n");

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

    /**
     * 流式调用 Dify，逐 token 转发到前端
     */
    private void streamDify(String question, String ocrText, SseEmitter emitter) throws Exception {
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

        // 发送请求体
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        // 读取 Dify SSE 流
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
                                sendEvent(emitter, "token", Map.of("t", token));
                            }

                        } else if ("message_end".equals(eventType)) {
                            // Dify 流结束
                            break;

                        } else if ("error".equals(eventType)) {
                            String msg = Objects.toString(event.getOrDefault("message", "Dify 返回错误"), "");
                            sendEvent(emitter, "error", Map.of("message", msg));
                            emitter.complete();
                            return;

                        } else if ("workflow_started".equals(eventType)) {
                            // 工作流开始，可选通知前端
                            log.info("Dify workflow started");

                        } else if ("workflow_finished".equals(eventType)) {
                            // 工作流结束
                            log.info("Dify workflow finished");

                        } else if ("node_started".equals(eventType) || "node_finished".equals(eventType)) {
                            // 节点状态变化，可选择性转发
                            // 对于复杂工作流，可以发送节点状态给前端展示进度
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse Dify SSE line: {}", line, e);
                    }
                }
            }
        }
        conn.disconnect();

        // 发送最终结果（已去除 think 标签）
        String finalAnswer = normalizeAnswer(stripThink(fullAnswer.toString()));
        sendEvent(emitter, "done", Map.of("answer", finalAnswer));
        emitter.complete();
    }

    /**
     * 发送 SSE 事件（内部已处理 IOException）
     */
    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("Failed to send SSE event '{}': {}", name, e.getMessage());
        }
    }

    /**
     * 模拟逐字流式发送（当 Dify Workflow 的 Code 节点一次性输出完整文本时使用）
     */
    private void simulateStreaming(SseEmitter emitter, String text) {
        // 每次发送 2~5 个字符，间隔 20~30ms，模拟打字机效果
        int i = 0;
        while (i < text.length()) {
            int len = 2 + (int) (Math.random() * 4); // 2~5 chars
            if (i + len > text.length()) len = text.length() - i;
            String chunk = text.substring(i, i + len);
            i += len;
            try {
                sendEvent(emitter, "token", Map.of("t", chunk));
                Thread.sleep(20 + (int) (Math.random() * 11)); // 20~30ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                break; // 客户端断开时停止
            }
        }
    }

    private String stripThink(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    /**
     * 处理 Dify 占位返回值：如果完整答案只有 "7"，替换为开发中提示
     */
    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        String trimmed = answer.trim();
        if ("7".equals(trimmed)) {
            return "相关功能还在开发中，敬请期待…";
        }
        return answer;
    }

    // ═══════════════════════════════════════════════════════════
    // 材料录入（调用 Dify Workflow）
    // ═══════════════════════════════════════════════════════════

    @PostMapping(value = "/material-entry", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter materialEntry(
            @RequestParam(value = "question", required = false, defaultValue = "") String question,
            @RequestParam(value = "files", required = false) List<MultipartFile> files
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: OCR
                sendEvent(emitter, "status", Map.of("text", "正在识别图片文字…"));
                String ocrText = runOcrSync(files);
                log.info("Material Entry OCR done: {} chars", ocrText.length());

                if (ocrText.isEmpty()) {
                    sendEvent(emitter, "error", Map.of("message", "未识别到文字内容，请检查上传的图片"));
                    emitter.complete();
                    return;
                }

                // Step 2: 通知前端开始提取
                sendEvent(emitter, "status", Map.of("text", "正在调用AI提取字段…"));

                // Step 3: 流式调用 Dify Workflow
                // 如果用户没有输入文字，默认传"材料录入"，避免 Dify 走到"功能开发中"分支
                String userQuestion = (question == null || question.isBlank()) ? "材料录入" : question;
                streamDifyWorkflow(ocrText, userQuestion, emitter);

            } catch (Exception e) {
                log.error("Material entry error", e);
                try {
                    sendEvent(emitter, "error", Map.of("message", e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.warn("Material Entry SSE timeout"));
        emitter.onError(ex -> log.error("Material Entry SSE error", ex));

        return emitter;
    }

    /**
     * 流式调用 Dify Workflow（/workflows/run），逐 token 转发到前端
     */
    private void streamDifyWorkflow(String ocrText, String question, SseEmitter emitter) throws Exception {
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

        // 读取 Dify Workflow SSE 流
        // Workflow API SSE 格式: text_chunk（流式文本）、workflow_finished（结束）
        // 注意：Code 节点拼的字符串不会产生 text_chunk，workflow_finished 一次性返回 → 需模拟流式
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
                                    sendEvent(emitter, "token", Map.of("t", token));
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
                                        // 只有在没有收到 text_chunk 时才用 outputs.text 替换
                                        // 否则以流式累积的 text_chunk 为准（outputs.text 兜底）
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
                            String msg = Objects.toString(
                                    event.getOrDefault("message", "Dify 返回错误"), "");
                            sendEvent(emitter, "error", Map.of("message", msg));
                            emitter.complete();
                            return;

                        } else if ("workflow_started".equals(eventType)) {
                            log.info("Dify workflow started");
                            sendEvent(emitter, "status", Map.of("text", "AI 工作流已启动，正在处理…"));

                        } else if ("node_started".equals(eventType)) {
                            // 转发节点状态到前端，让用户看到实时进度
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inner = (Map<String, Object>) event.get("data");
                            String nodeTitle = inner != null ? (String) inner.getOrDefault("title", "") : "";
                            String statusText = mapNodeTitle(nodeTitle);
                            if (!statusText.isEmpty()) {
                                log.info("Dify node started: {} → {}", nodeTitle, statusText);
                                sendEvent(emitter, "status", Map.of("text", statusText));
                            }

                        } else if ("node_finished".equals(eventType)) {
                            log.debug("Dify node finished: {}", event.get("node_id"));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse Dify SSE line: {}", line, e);
                    }
                }
            }
        }
        conn.disconnect();

        String finalAnswer = normalizeAnswer(stripThink(fullAnswer.toString()));

        // 如果 Dify 没有发送 text_chunk（Code 节点输出等场景），后端模拟逐字流式
        if (!hadTextChunk && !finalAnswer.isEmpty()) {
            simulateStreaming(emitter, finalAnswer);
        }

        sendEvent(emitter, "done", Map.of("answer", finalAnswer));
        emitter.complete();
    }

    /**
     * 将 Dify 节点标题映射为用户可读的中文进度提示
     */
    private String mapNodeTitle(String title) {
        if (title == null) return "";
        return switch (title) {
            case "Material_Classifier" -> "正在分析材料类型…";
            case "Field_Extractor"    -> "正在提取关键字段…";
            case "Normalize_Fields"   -> "正在标准化日期与字段…";
            case "Format_Report"      -> "正在生成结构化报告…";
            case "Final_Output"       -> "正在输出结果…";
            default -> ""; // 未知节点不发送状态，避免干扰
        };
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "ocr_api", OCR_URL, "dify_api", dify.getApiUrl());
    }
}
