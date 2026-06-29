# 干部人事档案智能审核助手

基于 **Vue3 + Spring Boot + PaddleOCR + Dify** 搭建的智能档案审核系统。支持上传档案材料图片/PDF，自动 OCR 识别文字，调用 Dify AI 工作流完成材料分类、字段提取、审核分析，结果以流式 SSE 实时返回前端。

---

## 系统架构

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  前端 Vue3    │────▶│  后端 Spring Boot │────▶│  PaddleOCR Flask │
│  port 5173   │◀────│  port 8088       │◀────│  port 8866       │
└──────────────┘     └────────┬─────────┘     └──────────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │  Dify AI 平台     │
                     │  localhost/v1    │
                     └──────────────────┘
```

| 组件 | 技术栈 | 端口 | 用途 |
|---|---|---|---|
| **前端** | Vue 3 + Vite 5 | 5173（开发） | 用户界面，文件上传，SSE 流式显示 |
| **后端** | Spring Boot 3.2.5 + Java 17 | 8088 | API 编排，OCR 调用，Dify 调用，SSE 转发 |
| **OCR 服务** | Python Flask + PaddleOCR PP-OCRv4 | 8866 | 图片 / PDF 文字识别 |
| **Dify** | Dify 社区版 | localhost/v1 | LLM 工作流（分类、提取、审核） |

---

## 环境要求

| 组件 | 版本要求 |
|---|---|
| **JDK** | 17 或以上 |
| **Maven** | 3.6+ |
| **Node.js** | 18+ |
| **Python** | 3.9+（推荐 3.10） |
| **Dify** | 社区版（本地部署） |

---

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/judatou123/material_checking_agent.git
cd material_checking_agent
```

---

### 2. 启动 PaddleOCR 服务（端口 8866）

```bash
cd orc

# 首次使用：创建虚拟环境并安装依赖
python -m venv paddle2_env
.\paddle2_env\Scripts\python.exe -m pip install -r requirements.txt

# 启动 OCR 服务
.\start_ocr.bat
# 或手动：
# .\paddle2_env\Scripts\python.exe paddle_server.py
```

首次启动会下载 PP-OCRv4 模型（约 5 秒），之后控制台显示：

```
* Running on http://0.0.0.0:8866
```

验证：

```bash
curl http://localhost:8866/health
# → {"status":"ok","engine":"PaddleOCR","model":"PP-OCRv4","version":"2.8.1","device":"CPU"}
```

> **注意**：PaddleOCR 必须在启动后端**之前**运行，否则后端调用 OCR 会失败。

---

### 3. 配置并启动后端（端口 8088）

#### 3.1 修改配置

编辑 `backend/src/main/resources/application.yml`：

```yaml
server:
  port: 8088                          # 后端端口

dify:
  api-url: http://localhost/v1        # Dify API 地址（修改为你的 Dify 地址）
  api-key: app-xxxxxxxxxxxxxxxx       # 审核场景的 Dify API Key
  material-entry-api-key: app-xxxxxxxxxxxxxxxx  # 材料录入场景的 API Key

ocr:
  api-url: http://localhost:8866      # PaddleOCR 地址（默认即可）
```

#### 3.2 修改 PaddleOCR 调用地址（如有需要）

OCR 端点硬编码在 `ChatController.java` 顶部，如需修改：

```java
// backend/src/main/java/org/example/agent/controller/ChatController.java
private static final String OCR_URL = "http://localhost:8866/ocr";          // 第31行
private static final String OCR_PDF_URL = "http://localhost:8866/ocr-pdf"; // 第32行
```

#### 3.3 启动

```bash
cd backend

# Windows (Maven Wrapper)
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

启动成功后控制台显示：

```
Tomcat started on port 8088
```

---

### 4. 启动前端（端口 5173）

```bash
cd frontend

# 首次使用：安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端 Vite 开发服务器启动于 `http://localhost:5173`。

#### 修改后端代理地址

如果后端不在 `localhost:8088`，编辑 `frontend/vite.config.js`：

```js
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8088',  // ← 改为你的后端地址
        changeOrigin: true,
      },
    },
  },
})
```

---

### 5. 配置 Dify 工作流

1. 确保 Dify 社区版已部署并运行在 `localhost/v1`（或你配置的地址）
2. 在 Dify 后台创建两个应用：
   - **审核对话应用**（Chat 类型）→ 获取 API Key 填入 `dify.api-key`
   - **材料录入工作流**（Workflow 类型）→ 获取 API Key 填入 `dify.material-entry-api-key`
3. 导入工作流：在 Dify 后台 → 材料录入应用 → 导入 DSL，上传 `dify/material_checking_agent.yml`

> 工作流入参：`ocr_text`（OCR 识别文本，必填）、`user_question`（用户问题，可选）

---

### 6. 打开浏览器

访问 **http://localhost:5173**，选择场景，上传图片或 PDF，开始使用。

---

## 项目结构

```
material_checking_agent/
├── README.md
├── .gitignore
│
├── frontend/                          # Vue3 前端
│   ├── index.html
│   ├── package.json
│   ├── vite.config.js                 # Vite 配置 & API 代理
│   └── src/
│       ├── main.js
│       ├── App.vue
│       └── components/
│           └── ChatAssistant.vue      # 主聊天组件（SSE 解析在此）
│
├── backend/                           # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/org/example/agent/
│       │   ├── CheckingAgentApplication.java
│       │   ├── config/
│       │   │   ├── DifyProperties.java    # Dify 配置类
│       │   │   └── WebConfig.java         # CORS 配置
│       │   └── controller/
│       │       └── ChatController.java    # 所有 API 端点 & OCR/Dify 调用
│       └── resources/
│           └── application.yml            # 主配置文件 ⚙️
│
├── orc/                               # PaddleOCR 服务
│   ├── paddle_server.py               # Flask API 主程序
│   ├── start_ocr.bat                  # Windows 启动脚本
│   ├── eval_ocr.py                    # OCR 评测工具
│   └── requirements.txt               # Python 依赖
│
└── dify/                              # Dify 工作流
    └── material_checking_agent.yml    # 材料录入工作流 DSL
```

---

## 配置清单

需要修改的所有位置汇总：

| 文件 | 配置项 | 说明 |
|---|---|---|
| `backend/src/main/resources/application.yml` | `dify.api-url` | Dify 平台地址 |
| 同上 | `dify.api-key` | 审核场景 Dify API Key |
| 同上 | `dify.material-entry-api-key` | 材料录入 Dify API Key |
| 同上 | `ocr.api-url` | PaddleOCR 地址 |
| 同上 | `server.port` | 后端启动端口 |
| `backend/.../ChatController.java:31-32` | `OCR_URL` / `OCR_PDF_URL` | OCR 具体端点路径 |
| `frontend/vite.config.js` | `proxy['/api'].target` | 后端地址（开发模式） |
| `orc/paddle_server.py` | `app.run(port=8866)` | OCR 服务端口 |

---

## API 端点

### 后端（Spring Boot :8088）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/chat` | 审核对话（multipart/form-data：question + files） |
| `POST` | `/api/material-entry` | 材料录入（multipart/form-data：question 可选 + files） |
| `GET` | `/api/health` | 健康检查 |

### PaddleOCR（Flask :8866）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/health` | 健康检查 |
| `POST` | `/ocr` | 图片 OCR → `{"image":"<base64>"}` |
| `POST` | `/ocr-pdf` | PDF OCR → `{"pdf":"<base64>","dpi":200}` |
| `POST` | `/ocr-batch` | 批量图片 URL OCR |

---

## 场景说明

前端支持 6 个场景，前 5 个走 **审核对话**（Dify Chat API），第 6 个走 **材料录入**（Dify Workflow API）：

| 编号 | 场景 | 后端端点 | Dify API |
|---|---|---|---|
| 1 | 干部选拔任用 | `/api/chat` | `/chat-messages` |
| 2 | 新进人员入职 | `/api/chat` | `/chat-messages` |
| 3 | 干部档案转递 | `/api/chat` | `/chat-messages` |
| 4 | 专项审核整治 | `/api/chat` | `/chat-messages` |
| 5 | 日常材料归档 | `/api/chat` | `/chat-messages` |
| 6 | 📋 材料录入 | `/api/material-entry` | `/workflows/run` |

---

## 数据流

```
用户上传图片/PDF
  → 前端 FormData (multipart/form-data)
  → 后端 ChatController 接收
  → PaddleOCR 识别文字 (/ocr 或 /ocr-pdf)
  → 后端拼接 ocr_text
  → Dify 工作流 (分类 → 提取 → 清洗 → 报告)
  → SSE 流式返回 text_chunk / node_started → 前端逐字显示
```

完整链路图见 [数据流详解](#)（上文系统架构部分）。

---

## 常见问题

### Q: 前端显示 "❌ 请求失败"
- 检查后端是否已启动（`http://localhost:8088/api/health`）
- 检查 `vite.config.js` 中代理目标是否正确

### Q: OCR 识别失败
- 检查 PaddleOCR 服务是否已启动（`http://localhost:8866/health`）
- 检查 `application.yml` 中 `ocr.api-url` 和 `ChatController.java` 中 OCR 常量是否正确

### Q: Dify 返回 "功能正在开发中"
- 确认材料录入场景发送了 `user_question`（后端已自动兜底 "材料录入"）
- 检查 `dify.material-entry-api-key` 是否正确
- 确认 Dify 工作流 DSL 已正确导入

### Q: 前端显示不是流式输出
- 确认 `application.yml` 中 `spring.mvc.async.request-timeout` 足够大（默认 5 分钟）
- 检查网络代理/防火墙是否缓冲了 SSE 响应
