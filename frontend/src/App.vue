<template>
  <div class="app-shell">
    <ChatAssistant />
  </div>
</template>

<script setup>
import ChatAssistant from './components/ChatAssistant.vue'
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #f0f2f5;
  color: #1a1a2e;
  height: 100vh;
  overflow: hidden;
}
.app-shell { height: 100vh; display: flex; flex-direction: column; }
</style>
