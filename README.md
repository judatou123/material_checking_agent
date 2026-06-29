
                            前端 (Vue3)                                    
  ChatAssistant.vue                                                        

                                                                           
  ① 用户选择文件                                                          
     <input type="file" accept="image/*,.pdf" multiple />                  
                                                                           
  ② onFileChange(e)                                                        
     ├─ 图片 → FileReader.readAsDataURL() → previews 存 base64 缩略图      
     └─ PDF  → pdfIconDataUrl()          → previews 存 SVG 红色图标        
     files.value = [File, File, ...]  ← 原始 File 对象，最多10个           
                                                                           
  ③ 用户点击发送 sendMessage()                                              
     formData = new FormData()                                                  ├─ formData.append('question', '...')     ← 文字（可选）             
     └─ formData.append('files', file)         ← 原始 File，逐个 append    
                                                                           
  ④ fetch('/api/material-entry', { method:'POST', body: formData })        
     ↑ multipart/form-data，文件以二进制流传输                              

                                    │
                                    ▼

                         后端 (Spring Boot :8088)                           
  ChatController.java                                                       

│                                                                           
│  ⑤ @PostMapping("/api/material-entry")                                   │
│     materialEntry(@RequestParam question,                                  │
│                   @RequestParam List<MultipartFile> files)                 │
│                                                                           │
│     CompletableFuture.runAsync(() -> {                                    │
│                                                                           │
│  ⑥ Step 1: OCR                                                            │
│     sendEvent("status", "正在识别图片文字…")  ──► 前端显示 "⏳ ..."       │
│                                                                           │
│     ocrText = runOcrSync(files)                                           │
│       │                                                                    │
│       ├─ for each file:                                                   │
│       │    isPdf(file)?                                                    │
│       │    │  YES → ocrPdf(file)      NO → ocrImage(file)                 │
│       │    │                                                               │
│       │    ├─ ocrImage():                                                 │
│       │    │   file bytes → Base64编码                                     │
│       │    │   POST http://localhost:8866/ocr                             │
│       │    │   Body: {"image":"<base64>"}                                 │
│       │    │   ← {"success":true, "full_text":"...", "blocks":[...]}     │
│       │    │   返回: "【文件名.jpg】\n识别文字...\n\n"                    │
│       │    │                                                               │
│       │    └─ ocrPdf():                                                   │
│       │        file bytes → Base64编码                                     │
│       │        POST http://localhost:8866/ocr-pdf                         │
│       │        Body: {"pdf":"<base64>", "dpi":200}                       │
│       │        ← {"success":true,                                          │
│       │           "pages":[{"page":1,"full_text":"..."}, ...]}           │
│       │        逐页拼成:                                                  │
│       │        "【文件名.pdf】\n--- 第1页 ---\n文字...\n--- 第2页 ---\n" │
│       │                                                                   │
│       └─ sb.append(...)  →  最终 ocrText = 所有文件识别结果拼接            │
│                                                                           │
│  ⑦ Step 2: 调用 Dify                                                       │
│     userQuestion = question.isBlank() ? "材料录入" : question              │
│     streamDifyWorkflow(ocrText, userQuestion, emitter)                     │
│       │                                                                    │
│       │  POST http://localhost/v1/workflows/run                           │
│       │  Header: Authorization: Bearer {materialEntryApiKey}               │
│       │  Body: {"inputs":{"ocr_text":"...", "user_question":"..."},        │
│       │         "response_mode":"streaming", "user":"admin"}               │
│       │                                                                    │
│       │  逐个转发收到的 Dify 事件:                                         │
│       │  ├─ text_chunk      → sendEvent("token", {t: "xxx"})              │
│       │  ├─ node_started    → sendEvent("status", "正在分析材料类型…")    │
│       │  ├─ workflow_finished → 取 outputs.text，无流式则 simulateStreaming│
│       │  └─ error           → sendEvent("error", ...)                     │
│       │                                                                    │
│       └─ sendEvent("done", {answer: finalAnswer})                         │
│                                                                           │
│     }) // CompletableFuture 异步执行                                       │
│                                                                           │
│     返回 SseEmitter → Spring MVC 以 text/event-stream 响应               │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       PaddleOCR (Flask :8866)                              │
│  paddle_server.py                                                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  POST /ocr         ←  后端发 {"image":"<base64>"}                         │
│    1. base64 decode → PIL Image                                           │
│    2. 最长边 >3000px → 等比缩放                                           │
│    3. ocr.ocr(img_array, cls=True) → PP-OCRv4 识别                        │
│    4. 返回 {"full_text":"行1\n行2\n...", "blocks":[...], "elapsed":0.5}  │
│                                                                           │
│  POST /ocr-pdf     ←  后端发 {"pdf":"<base64>", "dpi":200}               │
│    1. base64 decode → PDF bytes                                           │
│    2. fitz.open(pdf_bytes) → 逐页渲染为 PIL Image (zoom=dpi/72)          │
│    3. 每页调用 ocr.ocr(...)                                               │
│    4. 返回 {"pages":[{"page":1,"full_text":"...","blocks":[...]}, ...]}  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           Dify (localhost/v1)                              │
│  材料录入工作流 (6节点)                                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  POST /workflows/run  (response_mode: streaming)                          │
│                                                                           │
│  Start → Material_Classifier(LLM) → Field_Extractor(LLM)                  │
│       → Normalize_Fields(Code) → Format_Report(Code) → Final_Output      │
│                                                                           │
│  返回 SSE:                                                                 │
│    event: workflow_started                                                 │
│    event: node_started    data: {title: "Material_Classifier"}            │
│    event: node_finished   data: {title: "Material_Classifier"}            │
│    event: node_started    data: {title: "Field_Extractor"}               │
│    ...                                                                     │
│    event: text_chunk      data: {text: "="}                               │
│    event: text_chunk      data: {text: "=="}                              │
│    ...                                                                     │
│    event: workflow_finished data: {outputs: {text: "完整报告..."}}        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         前端 SSE 解析                                      │
│  parseSSEStream(reader)                                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ⑧ 逐事件处理:                                                             │
│                                                                           │
│  case 'status':  aiMsg.content = "⏳ _正在分析材料类型…_"   ← 替换显示    │
│  case 'token':   aiMsg.content += data.t                     ← 追加文字    │
│  case 'done':    aiMsg.content = data.answer                 ← 最终替换    │
│  case 'error':   aiMsg.content = "❌ ..."                     ← 错误显示    │
│                                                                           │
│  loading.value = false  ← 第一个 token 到达时关闭打字动画                  │
└─────────────────────────────────────────────────────────────────────────┘
