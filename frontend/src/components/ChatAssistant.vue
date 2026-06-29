<template>
  <div class="chat-container">

    <!-- ========== 顶部栏 ========== -->
    <header class="chat-header">
      <div class="header-left">
        <div class="logo">📋</div>
        <div class="header-text">
          <h1>干部人事档案智能审核助手</h1>
          <p class="header-desc">上传档案材料图片，选择场景，AI 自动处理</p>
        </div>
      </div>
      <div class="header-right">
        <div class="scenario-chips">
          <button v-for="s in scenarios" :key="s.id"
            :class="['chip', { active: selectedScenario === s.id }]"
            @click="switchScenario(s.id)">
            {{ s.label }}
          </button>
        </div>
      </div>
    </header>

    <!-- ========== 消息列表 ========== -->
    <main class="chat-messages" ref="messagesEl">
      <!-- 欢迎语 -->
      <div v-if="messages.length === 0" class="welcome">
        <div class="welcome-icon">🤖</div>
        <h2>有什么可以帮助您的？</h2>
        <p>选择场景，上传档案材料图片，开始智能处理</p>
        <div class="quick-actions">
          <button v-for="q in currentQuickQuestions" :key="q" class="quick-btn"
            @click="question = q">{{ q }}</button>
        </div>
      </div>

      <!-- 消息 -->
      <div v-for="(msg, idx) in messages" :key="idx" :class="['message-row', msg.role]">
        <div class="avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
        <div class="message-bubble">
          <div class="message-content" v-html="renderMarkdown(msg.content)"></div>
          <!-- 用户消息的图片预览 -->
          <div v-if="msg.images && msg.images.length" class="msg-images">
            <img v-for="(img, i) in msg.images" :key="i" :src="img" class="msg-img-thumb" />
          </div>
        </div>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="message-row assistant">
        <div class="avatar">🤖</div>
        <div class="message-bubble typing">
          <span class="dot"></span><span class="dot"></span><span class="dot"></span>
        </div>
      </div>
    </main>

    <!-- ========== 底部输入区 ========== -->
    <footer class="chat-input-area">
      <!-- 图片预览 -->
      <div v-if="previews.length" class="preview-row">
        <div v-for="(p, i) in previews" :key="i" class="preview-item">
          <img :src="p" />
          <button class="remove-btn" @click="removeFile(i)">×</button>
        </div>
      </div>

      <div class="input-row">
        <!-- 上传按钮 -->
        <label class="upload-btn" title="上传档案材料图片">
          📎
          <input type="file" accept="image/*" multiple hidden
            @change="onFileChange" />
        </label>

        <!-- 输入框 -->
        <textarea
          v-model="question"
          class="text-input"
          :placeholder="placeholder"
          rows="1"
          ref="inputEl"
          @keydown.enter.exact.prevent="sendMessage"
          @input="autoResize"
        ></textarea>

        <!-- 发送按钮 -->
        <button class="send-btn" :disabled="!canSend" @click="sendMessage">
          ➤
        </button>
      </div>
    </footer>

  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { marked } from 'marked'

// ---- 场景 ----
const scenarios = [
  { id: '1', label: '干部选拔任用' },
  { id: '2', label: '新进人员入职' },
  { id: '3', label: '干部档案转递' },
  { id: '4', label: '专项审核整治' },
  { id: '5', label: '日常材料归档' },
  { id: '6', label: '📋 材料录入' },
]
const selectedScenario = ref('1')

function switchScenario(id) {
  if (loading.value) return
  selectedScenario.value = id
  messages.value = []
  question.value = ''
  files.value = []
  previews.value = []
}

const isMaterialEntry = computed(() => selectedScenario.value === '6')

// ---- 快捷提问 ----
const reviewQuickQuestions = [
  '审核张三的材料是否符合干部选拔任用资格条件',
  '审核李四的档案转递手续是否完备',
  '审核王五的档案材料是否存在涂改和造假',
]
const entryQuickQuestions = [
  '上传干部履历表，提取基本信息',
  '上传任免审批表，提取职务信息',
  '上传年度考核表，提取考核结果',
]
const currentQuickQuestions = computed(() =>
  isMaterialEntry.value ? entryQuickQuestions : reviewQuickQuestions
)

// ---- 状态 ----
const question = ref('')
const messages = ref([])
const files = ref([])
const previews = ref([])
const loading = ref(false)
const messagesEl = ref(null)
const inputEl = ref(null)

const canSend = computed(() => {
  if (loading.value) return false
  // 材料录入：有文件即可发送，文字可选
  if (isMaterialEntry.value) return files.value.length > 0
  // 审核场景：必须有文字输入
  return question.value.trim().length > 0
})

const scenarioName = computed(() => {
  const s = scenarios.find(s => s.id === selectedScenario.value)
  return s ? s.label : '干部选拔任用'
})

const placeholder = computed(() => {
  if (isMaterialEntry.value) {
    return '材料录入 — 上传图片，点击发送即可自动提取字段（可附加文字说明）'
  }
  return `当前场景：${scenarioName.value} — 输入问题，Enter 发送`
})

// ---- 文件处理 ----
function onFileChange(e) {
  const newFiles = Array.from(e.target.files)
  files.value = [...files.value, ...newFiles].slice(0, 10) // 最多10张
  previews.value = []
  files.value.forEach(f => {
    const reader = new FileReader()
    reader.onload = ev => previews.value.push(ev.target.result)
    reader.readAsDataURL(f)
  })
  // 重置 input 值，否则再次选择相同文件不会触发 change
  e.target.value = ''
}

function removeFile(idx) {
  files.value.splice(idx, 1)
  previews.value.splice(idx, 1)
}

// ---- 解析 SSE 流 ----
async function* parseSSEStream(reader) {
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // SSE 事件以 \n\n 分隔
    const parts = buffer.split('\n\n')
    buffer = parts.pop() || ''

    for (const part of parts) {
      if (!part.trim()) continue

      const lines = part.split('\n')
      let eventName = ''
      let eventData = ''

      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventName = line.substring(6).trim()
        } else if (line.startsWith('data:')) {
          eventData = line.substring(5).trim()
        }
      }

      if (eventName && eventData) {
        try {
          yield { event: eventName, data: JSON.parse(eventData) }
        } catch {
          yield { event: eventName, data: eventData }
        }
      }
    }
  }
}

// ---- 发送消息 ----
async function sendMessage() {
  if (!canSend.value) return

  const q = question.value.trim()
  const imgPreviews = [...previews.value]
  const isEntry = isMaterialEntry.value

  // 构造用户消息
  const userMsg = {
    role: 'user',
    content: isEntry ? (q || `📋 材料录入：已上传 ${files.value.length} 张图片`) : q,
    images: imgPreviews,
  }
  messages.value.push(userMsg)

  const uploadingFiles = [...files.value]
  question.value = ''
  files.value = []
  previews.value = []
  loading.value = true

  messages.value.push({ role: 'assistant', content: '' })
  const aiMsg = messages.value[messages.value.length - 1]
  let firstChunk = true

  await nextTick()
  scrollToBottom()

  try {
    const formData = new FormData()
    if (isEntry) {
      // 材料录入：传文件 + 可选文字
      if (q) formData.append('question', q)
      for (const f of uploadingFiles) formData.append('files', f)
    } else {
      // 审核场景：传问题 + 文件
      const fullQuestion = `【场景：${scenarioName.value}】${q}`
      formData.append('question', fullQuestion)
      for (const f of uploadingFiles) formData.append('files', f)
    }

    const apiPath = isEntry ? '/api/material-entry' : '/api/chat'
    const resp = await fetch(apiPath, {
      method: 'POST',
      body: formData,
    })

    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}: ${resp.statusText}`)
    }

    // 流式读取 SSE 响应（审核和录入共用同一套解析逻辑）
    const reader = resp.body.getReader()
    for await (const { event, data } of parseSSEStream(reader)) {
      switch (event) {
        case 'token':
          if (firstChunk) {
            aiMsg.content = ''
            firstChunk = false
            loading.value = false
          }
          aiMsg.content += data.t || ''
          scrollToBottom()
          break

        case 'done':
          if (data.answer) {
            aiMsg.content = data.answer === '7'
              ? '相关功能还在开发中，敬请期待…'
              : data.answer
          }
          loading.value = false
          scrollToBottom()
          return

        case 'error':
          aiMsg.content = `❌ ${data.message || '服务器错误'}`
          loading.value = false
          scrollToBottom()
          return

        case 'status':
          if (firstChunk) {
            // 显示进度状态，带 ⏳ 前缀让用户感知到实时处理
            aiMsg.content = `⏳ _${data.text}_`
          }
          break
      }
    }

    if (loading.value) {
      aiMsg.content = aiMsg.content || '（无返回内容）'
      loading.value = false
    }
  } catch (err) {
    aiMsg.content = aiMsg.content || `❌ 请求失败：${err.message}`
    loading.value = false
  } finally {
    loading.value = false
    await nextTick()
    scrollToBottom()
  }
}

// ---- Markdown 渲染 ----
marked.setOptions({ breaks: true, gfm: true })
function renderMarkdown(text) {
  if (!text) return ''
  try {
    return marked.parse(text)
  } catch {
    return text.replace(/\n/g, '<br>')
  }
}

// ---- 辅助 ----
function autoResize() {
  const el = inputEl.value
  if (el) {
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 120) + 'px'
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = messagesEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

onMounted(() => {
  inputEl.value?.focus()
})
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  max-width: 1100px;
  margin: 0 auto;
  background: #fff;
  box-shadow: 0 0 40px rgba(0,0,0,.06);
}

/* ---- 顶部栏 ---- */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  background: linear-gradient(135deg, #1a3a5c 0%, #2a5298 100%);
  color: #fff;
  flex-shrink: 0;
  gap: 20px;
  flex-wrap: wrap;
}
.header-left { display: flex; align-items: center; gap: 14px; }
.logo { font-size: 36px; }
.header-text h1 { font-size: 20px; font-weight: 600; margin-bottom: 4px; }
.header-desc { font-size: 13px; opacity: .8; }
.header-right { flex-shrink: 0; }
.scenario-chips { display: flex; gap: 6px; flex-wrap: wrap; }
.chip {
  padding: 6px 14px;
  border-radius: 20px;
  border: 1px solid rgba(255,255,255,.35);
  background: transparent;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: all .2s;
  white-space: nowrap;
}
.chip:hover { background: rgba(255,255,255,.15); }
.chip.active { background: #fff; color: #1a3a5c; font-weight: 600; border-color: #fff; }

/* ---- 消息区 ---- */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: #f7f8fc;
}
.chat-messages::-webkit-scrollbar { width: 6px; }
.chat-messages::-webkit-scrollbar-thumb { background: #d0d5e0; border-radius: 3px; }

.welcome {
  text-align: center;
  padding: 60px 20px;
}
.welcome-icon { font-size: 56px; margin-bottom: 16px; }
.welcome h2 { font-size: 22px; margin-bottom: 8px; color: #1a3a5c; }
.welcome p { font-size: 14px; color: #7b8ba0; margin-bottom: 32px; }
.quick-actions { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
.quick-btn {
  padding: 10px 20px;
  border-radius: 24px;
  border: 1px solid #dbe4f0;
  background: #fff;
  color: #2a5298;
  font-size: 13px;
  cursor: pointer;
  transition: all .2s;
}
.quick-btn:hover { border-color: #2a5298; background: #f0f5ff; }

/* 消息行 */
.message-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  animation: fadeIn .3s ease;
}
.message-row.user { flex-direction: row-reverse; }
.avatar {
  width: 38px; height: 38px;
  border-radius: 50%;
  background: #e8edf5;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.message-bubble {
  max-width: 78%;
  padding: 14px 18px;
  border-radius: 18px;
  line-height: 1.7;
  font-size: 14px;
}
.message-row.user .message-bubble {
  background: linear-gradient(135deg, #2a5298, #1a3a5c);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.message-row.assistant .message-bubble {
  background: #fff;
  border: 1px solid #eef2f9;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 8px rgba(0,0,0,.04);
}

/* Markdown 渲染 */
.message-content :deep(h1) { font-size: 18px; margin: 12px 0 6px; }
.message-content :deep(h2) { font-size: 16px; margin: 10px 0 4px; }
.message-content :deep(h3) { font-size: 14px; margin: 8px 0 4px; }
.message-content :deep(p) { margin: 4px 0; }
.message-content :deep(ul), .message-content :deep(ol) { padding-left: 20px; margin: 6px 0; }
.message-content :deep(table) {
  width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 13px;
}
.message-content :deep(th) {
  background: #f0f4fa; padding: 8px 12px; text-align: left;
  border: 1px solid #dde4f0; font-weight: 600;
}
.message-content :deep(td) {
  padding: 6px 12px; border: 1px solid #eef2f9;
}
.message-content :deep(code) {
  background: #f0f4fa; padding: 2px 6px; border-radius: 4px; font-size: 12px;
}
.message-content :deep(strong) { color: #c0392b; }

/* 图片 */
.msg-images { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.msg-img-thumb {
  width: 80px; height: 80px; object-fit: cover;
  border-radius: 10px; border: 2px solid rgba(255,255,255,.4);
}

/* 打字动画 */
.typing { display: flex; gap: 4px; padding: 18px 24px; }
.dot {
  width: 8px; height: 8px; border-radius: 50%; background: #a0b0d0;
  animation: bounce 1.2s infinite;
}
.dot:nth-child(2) { animation-delay: .2s; }
.dot:nth-child(3) { animation-delay: .4s; }

/* ---- 底部输入 ---- */
.chat-input-area {
  padding: 14px 24px 20px;
  border-top: 1px solid #eef2f9;
  background: #fff;
  flex-shrink: 0;
}
.preview-row { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.preview-item {
  position: relative; width: 64px; height: 64px;
}
.preview-item img {
  width: 100%; height: 100%; object-fit: cover;
  border-radius: 10px; border: 1px solid #dde4f0;
}
.remove-btn {
  position: absolute; top: -6px; right: -6px;
  width: 20px; height: 20px; border-radius: 50%;
  border: none; background: #e74c3c; color: #fff;
  font-size: 12px; cursor: pointer; line-height: 20px; text-align: center;
}
.input-row { display: flex; align-items: flex-end; gap: 10px; }
.upload-btn {
  width: 40px; height: 40px; border-radius: 50%;
  background: #f0f4fa; display: flex; align-items: center;
  justify-content: center; font-size: 18px; cursor: pointer;
  transition: background .2s; flex-shrink: 0;
}
.upload-btn:hover { background: #dde4f0; }
.text-input {
  flex: 1; padding: 10px 16px; border-radius: 22px;
  border: 1px solid #dde4f0; font-size: 14px;
  outline: none; resize: none; font-family: inherit;
  line-height: 1.5; min-height: 42px; max-height: 120px;
}
.text-input:focus { border-color: #2a5298; box-shadow: 0 0 0 3px rgba(42,82,152,.08); }
.send-btn {
  width: 42px; height: 42px; border-radius: 50%;
  border: none; background: linear-gradient(135deg, #2a5298, #1a3a5c);
  color: #fff; font-size: 20px; cursor: pointer;
  transition: all .2s; flex-shrink: 0;
}
.send-btn:hover { transform: scale(1.06); }
.send-btn:disabled { background: #c0cde0; cursor: not-allowed; transform: none; }

@keyframes fadeIn { from { opacity: 0; transform: translateY(8px); } }
@keyframes bounce {
  0%, 80%, 100% { transform: translateY(0); }
  40% { transform: translateY(-8px); }
}
</style>
