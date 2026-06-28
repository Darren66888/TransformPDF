<template>
  <div class="app">
    <!-- Header -->
    <header class="header">
      <div class="header-inner">
        <div class="brand">
          <div class="brand-icon">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
              <line x1="16" y1="13" x2="8" y2="13"/>
              <line x1="16" y1="17" x2="8" y2="17"/>
              <polyline points="10 9 9 9 8 9"/>
            </svg>
          </div>
          <div>
            <h1 class="brand-name">TransformPDF</h1>
            <p class="brand-desc">智能图片转换 · 文档扫描</p>
          </div>
        </div>
        <div class="header-actions">
          <el-button text class="history-btn" @click="showHistory = !showHistory">
            <el-icon><Clock /></el-icon>
            转换历史
            <el-icon class="arrow" :class="{ open: showHistory }"><ArrowDown /></el-icon>
          </el-button>
        </div>
      </div>
    </header>

    <!-- History Panel (collapsible) -->
    <transition name="slide-down">
      <div v-if="showHistory" class="history-panel">
        <div class="history-inner">
          <div class="history-toolbar">
            <span class="history-title">转换历史</span>
            <el-button size="small" text @click="loadHistory" :loading="loadingHistory">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>
          <el-table :data="tasks" size="small" empty-text="暂无记录" max-height="240">
            <el-table-column prop="originalFilename" label="文件" min-width="180" show-overflow-tooltip />
            <el-table-column prop="conversionType" label="类型" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="getTagType(row.conversionType)" effect="plain" round>
                  {{ getConversionLabel(row.conversionType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <span :class="'status-dot status-' + row.status.toLowerCase()"></span>
                {{ getStatusLabel(row.status) }}
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" align="center">
              <template #default="{ row }">
                <el-button v-if="row.status === 'COMPLETED'" type="primary" link size="small"
                  @click="window.open(getDownloadUrl(row.id))">下载</el-button>
                <el-button type="danger" link size="small"
                  @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </transition>

    <!-- Main Content -->
    <main class="main">
      <!-- Mode Selector -->
      <div class="mode-bar">
        <div class="mode-tabs">
          <button v-for="tab in tabs" :key="tab.key"
            class="mode-tab" :class="{ active: mode === tab.key }"
            @click="mode = tab.key">
            <component :is="tab.icon" />
            <span>{{ tab.label }}</span>
          </button>
        </div>
      </div>

      <!-- Content Area -->
      <div class="content">
        <!-- Upload Zone -->
        <div class="upload-zone" :class="{ 'has-files': files.length > 0 }">
          <div v-if="files.length === 0" class="upload-empty"
            @dragover.prevent="dragOver = true"
            @dragleave="dragOver = false"
            @drop.prevent="handleDrop"
            :class="{ dragover: dragOver }">
            <div class="upload-visual">
              <div class="upload-circle">
                <el-icon :size="36"><UploadFilled /></el-icon>
              </div>
              <div class="upload-rings"></div>
            </div>
            <h3>{{ mode === 'toWord' ? '拖拽 PDF 文件到这里' : '拖拽图片到这里' }}</h3>
            <p>或者点击选择文件</p>
            <input type="file" ref="fileInput" :multiple="mode !== 'toWord'" :accept="mode === 'toWord' ? '.pdf' : '.jpg,.jpeg,.png'" @change="handleFileSelect" hidden />
            <el-button type="primary" round size="large" class="upload-btn" @click="$refs.fileInput.click()">
              <el-icon><Plus /></el-icon>
              {{ mode === 'toWord' ? '选择 PDF 文件' : '选择图片' }}
            </el-button>
            <span class="upload-hint">{{ mode === 'toWord' ? '支持 PDF 格式，单个文件' : '支持 JPG / PNG，可多选' }}</span>
          </div>

          <!-- File Gallery -->
          <div v-else class="gallery">
            <!-- Scan mode tip -->
            <div v-if="mode === 'scan'" class="scan-tip">
              <el-icon><InfoFilled /></el-icon>
              <span>请点击图片手动框选文档区域，拖动四角调整选区后进行扫描</span>
            </div>
            <div class="gallery-toolbar">
              <div class="toolbar-left">
                <el-checkbox v-if="mode !== 'toWord'" v-model="allSelected" :indeterminate="isIndeterminate" @change="toggleAll">
                  全选
                </el-checkbox>
                <span class="file-count">{{ mode === 'toWord' ? files[0]?.file.name : files.length + ' 张图片 · 已选 ' + selectedCount + ' 张' }}</span>
              </div>
              <div class="toolbar-right">
                <input v-if="mode !== 'toWord'" type="file" ref="fileInput2" multiple accept=".jpg,.jpeg,.png" @change="handleFileSelect" hidden />
                <el-button v-if="mode !== 'toWord'" text size="small" @click="$refs.fileInput2.click()">
                  <el-icon><Plus /></el-icon> 继续添加
                </el-button>
                <el-button text size="small" type="danger" @click="clearFiles" :disabled="files.length === 0">
                  <el-icon><Delete /></el-icon> 清空
                </el-button>
              </div>
            </div>

            <div class="gallery-grid">
              <div v-for="(file, index) in files" :key="file.uid"
                class="file-card" :class="{ selected: file.selected, dragging: file.dragging }"
                draggable="true"
                @dragstart="handleDragStart(index, $event)"
                @dragover.prevent="handleDragOver(index, $event)"
                @drop.prevent="handleDropOnCard(index, $event)"
                @dragend="handleDragEnd">
                <div class="card-check" @click.stop="file.selected = !file.selected">
                  <el-checkbox v-model="file.selected" @click.stop />
                </div>
                <div class="card-index">{{ index + 1 }}</div>
                <div class="card-img" @click="mode === 'scan' ? openScanEditor(file) : showPreview(file)">
                  <img v-if="file.file.type !== 'application/pdf'" :src="file.url" :alt="file.file.name" />
                  <div v-else class="pdf-preview">
                    <el-icon :size="48" color="#e74c3c"><Document /></el-icon>
                    <span>PDF 文件</span>
                  </div>
                  <div class="card-overlay">
                    <el-icon :size="24"><ZoomIn /></el-icon>
                  </div>
                </div>
                <div class="card-info">
                  <span class="card-name" :title="file.file.name">{{ file.file.name }}</span>
                  <span class="card-size">{{ formatSize(file.file.size) }}</span>
                </div>
                <button class="card-remove" @click.stop="removeFile(index)" title="移除">
                  <el-icon :size="14"><Close /></el-icon>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Action Panel -->
        <div v-if="files.length > 0" class="action-panel">
          <div class="panel-card">
            <h3 class="panel-title">
              <el-icon><Operation /></el-icon>
              转换设置
            </h3>

            <!-- Mode-specific options -->
            <div v-if="mode === 'toPdf'" class="panel-section">
              <div class="option-group">
                <label class="option-label">输出文件名</label>
                <el-input v-model="outputName" placeholder="merged_images.pdf" size="default" clearable>
                  <template #suffix>.pdf</template>
                </el-input>
              </div>
              <div class="option-info">
                <el-icon><InfoFilled /></el-icon>
                <span>已选 {{ selectedCount }} 张图片将按顺序合并为一个 PDF</span>
              </div>
              <el-button type="primary" size="large" round block
                :loading="converting" :disabled="selectedCount === 0"
                @click="doMergePdf" class="action-btn">
                <el-icon v-if="!converting"><Document /></el-icon>
                {{ converting ? '转换中...' : '合并为 PDF' }}
              </el-button>
            </div>

            <div v-else-if="mode === 'toWord'" class="panel-section">
              <div class="option-info">
                <el-icon><InfoFilled /></el-icon>
                <span>将 PDF 文件转换为 Word 文档</span>
              </div>
              <el-button type="primary" size="large" round block
                :loading="converting" :disabled="selectedCount === 0"
                @click="doPdfToWord" class="action-btn">
                <el-icon v-if="!converting"><Notebook /></el-icon>
                {{ converting ? '转换中...' : '转换为 Word' }}
              </el-button>
            </div>

            <div v-else-if="mode === 'scan'" class="panel-section">
              <label class="option-label">扫描输出格式</label>
              <div class="scan-choices">
                <label v-for="opt in scanOptions" :key="opt.value"
                  class="scan-choice" :class="{ active: scanType === opt.value }"
                  @click="scanType = opt.value">
                  <el-radio v-model="scanType" :value="opt.value" />
                  <component :is="opt.icon" />
                  <span>{{ opt.label }}</span>
                </label>
              </div>
              <el-button type="primary" size="large" round block
                :loading="converting" :disabled="selectedCount === 0 || !scanType"
                @click="doBatchScan" class="action-btn">
                <el-icon v-if="!converting"><Camera /></el-icon>
                {{ converting ? '扫描中...' : `扫描 ${selectedCount} 张图片` }}
              </el-button>
            </div>

            <!-- Batch progress -->
            <div v-if="batchProgress.total > 0" class="progress-section">
              <div class="progress-header">
                <span>处理进度</span>
                <span>{{ batchProgress.done }} / {{ batchProgress.total }}</span>
              </div>
              <el-progress :percentage="Math.round(batchProgress.done / batchProgress.total * 100)"
                :stroke-width="8" :show-text="false" />
              <div v-if="batchProgress.current" class="progress-file">{{ batchProgress.current }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Results -->
      <transition name="fade-up">
        <div v-if="results.length > 0" class="results-section">
          <div class="results-header">
            <h3><el-icon><CircleCheck /></el-icon> 转换完成</h3>
            <el-button text size="small" @click="downloadAll" :disabled="results.length === 0">
              <el-icon><Download /></el-icon> 全部下载
            </el-button>
          </div>
          <div class="results-grid">
            <div v-for="r in results" :key="r.taskId" class="result-card">
              <div class="result-icon" :class="getResultType(r.outputFilename)">
                {{ getResultExt(r.outputFilename) }}
              </div>
              <div class="result-info">
                <span class="result-name">{{ r.outputFilename }}</span>
                <span v-if="r.imageCount" class="result-meta">{{ r.imageCount }} 张图片</span>
              </div>
              <el-button type="primary" size="small" round @click="downloadResult(r)">
                <el-icon><Download /></el-icon> 下载
              </el-button>
            </div>
          </div>
        </div>
      </transition>
    </main>

    <!-- Image Preview Dialog -->
    <el-dialog v-model="previewVisible" width="auto" :show-close="true" class="preview-dialog"
      destroy-on-close append-to-body>
      <img :src="previewFile?.url" alt="preview" class="preview-img" />
      <template #footer>
        <span class="preview-name">{{ previewFile?.file?.name }}</span>
      </template>
    </el-dialog>

    <!-- Scan Editor Dialog -->
    <el-dialog v-model="scanEditorVisible" title="" fullscreen :show-close="false"
      class="scan-dialog" destroy-on-close append-to-body>
      <div class="scan-editor">
        <!-- Toolbar -->
        <div class="scan-toolbar">
          <div class="scan-toolbar-left">
            <el-icon :size="20"><Camera /></el-icon>
            <span class="scan-toolbar-title">框选文档区域</span>
            <span class="scan-toolbar-hint">拖动四角选择文档，然后点击「扫描」</span>
          </div>
          <div class="scan-toolbar-right">
            <el-button @click="resetCorners">重置选区</el-button>
            <el-button @click="scanEditorVisible = false">取消</el-button>
            <el-button type="primary" @click="confirmScan" :loading="scanProcessing">
              <el-icon v-if="!scanProcessing"><Camera /></el-icon>
              {{ scanProcessing ? '处理中...' : '扫描' }}
            </el-button>
          </div>
        </div>

        <!-- Canvas Area -->
        <div class="scan-canvas-wrap" ref="scanCanvasWrap">
          <canvas ref="scanCanvas" class="scan-canvas"
            @mousedown="onCanvasMouseDown"
            @mousemove="onCanvasMouseMove"
            @mouseup="onCanvasMouseUp"
            @mouseleave="onCanvasMouseUp"
            @touchstart.prevent="onCanvasTouchStart"
            @touchmove.prevent="onCanvasTouchMove"
            @touchend.prevent="onCanvasMouseUp">
          </canvas>
        </div>

        <!-- Format selector -->
        <div class="scan-format-bar">
          <span class="scan-format-label">输出格式：</span>
          <el-radio-group v-model="scanOutputFormat" size="default">
            <el-radio-button value="image">扫描图片</el-radio-button>
            <el-radio-button value="pdf">扫描PDF</el-radio-button>
            <el-radio-button value="word">扫描Word</el-radio-button>
          </el-radio-group>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, nextTick, markRaw } from 'vue'
import { ElMessage, ElNotification, ElMessageBox } from 'element-plus'
import {
  uploadFile, convertToPdf, convertToWord,
  scanToImage, scanToPdf, scanToWord,
  mergeToPdf, getTasks, getDownloadUrl, deleteTask, scanWithCorners
} from './api'
import {
  Document, Notebook, Camera, UploadFilled, Plus, Delete,
  ZoomIn, Close, Clock, ArrowDown, Refresh, Operation,
  InfoFilled, CircleCheck, Download, Switch, Picture
} from '@element-plus/icons-vue'

// Tabs config
const tabs = [
  { key: 'toPdf', label: '图片转PDF', icon: markRaw(Document) },
  { key: 'toWord', label: 'PDF转Word', icon: markRaw(Notebook) },
  { key: 'scan', label: '文档扫描', icon: markRaw(Camera) },
]
const scanOptions = [
  { value: 'image', label: '扫描图片', icon: markRaw(Picture) },
  { value: 'pdf', label: '扫描PDF', icon: markRaw(Document) },
  { value: 'word', label: '扫描Word', icon: markRaw(Notebook) },
]

// State
const mode = ref('toPdf')
const files = ref([]) // { uid, file, url, selected, dragging }
const outputName = ref('')
const scanType = ref('pdf')
const converting = ref(false)
const results = ref([])
const showHistory = ref(false)
const tasks = ref([])
const loadingHistory = ref(false)
const dragOver = ref(false)
const previewVisible = ref(false)
const previewFile = ref(null)
const batchProgress = reactive({ total: 0, done: 0, current: '' })
const fileInput = ref(null)
const fileInput2 = ref(null)
let uidCounter = 0
let dragFromIndex = -1

// Scan editor state
const scanEditorVisible = ref(false)
const scanEditorFile = ref(null)
const scanOutputFormat = ref('image')
const scanProcessing = ref(false)
const scanCanvas = ref(null)
const scanCanvasWrap = ref(null)
const scanCorners = reactive({ pts: [] }) // [{x,y}, {x,y}, {x,y}, {x,y}]
let draggingCornerIdx = -1
let scanImg = null // loaded Image object
let scanScale = 1
let scanOffsetX = 0
let scanOffsetY = 0

// Computed
const selectedCount = computed(() => files.value.filter(f => f.selected).length)
const allSelected = computed(() => files.value.length > 0 && files.value.every(f => f.selected))
const isIndeterminate = computed(() => {
  const sel = files.value.filter(f => f.selected).length
  return sel > 0 && sel < files.value.length
})

// File operations
function handleFileSelect(e) {
  const newFiles = Array.from(e.target.files)
  addFiles(newFiles)
  e.target.value = ''
}

function handleDrop(e) {
  dragOver.value = false
  const newFiles = Array.from(e.dataTransfer.files).filter(f =>
    mode.value === 'toWord' ? f.type === 'application/pdf' : f.type.startsWith('image/')
  )
  addFiles(newFiles)
}

function addFiles(newFiles) {
  for (const file of newFiles) {
    if (mode.value === 'toWord') {
      if (file.type !== 'application/pdf') continue
      // PDF转Word: only keep one file
      files.value.forEach(f => URL.revokeObjectURL(f.url))
      files.value = []
    } else {
      if (!file.type.match(/^image\/(jpeg|png)$/)) continue
    }
    files.value.push({
      uid: ++uidCounter,
      file,
      url: URL.createObjectURL(file),
      selected: true,
      dragging: false,
    })
  }
}

function removeFile(index) {
  URL.revokeObjectURL(files.value[index].url)
  files.value.splice(index, 1)
}

function clearFiles() {
  files.value.forEach(f => URL.revokeObjectURL(f.url))
  files.value = []
  results.value = []
}

function toggleAll(val) {
  files.value.forEach(f => f.selected = val)
}

// Drag & drop reorder
function handleDragStart(index, e) {
  dragFromIndex = index
  files.value[index].dragging = true
  e.dataTransfer.effectAllowed = 'move'
}

function handleDragOver(index, e) {
  if (dragFromIndex === -1 || dragFromIndex === index) return
  e.dataTransfer.dropEffect = 'move'
}

function handleDropOnCard(index, e) {
  e.preventDefault()
  if (dragFromIndex === -1 || dragFromIndex === index) return
  const item = files.value.splice(dragFromIndex, 1)[0]
  files.value.splice(index, 0, item)
  dragFromIndex = -1
}

function handleDragEnd() {
  files.value.forEach(f => f.dragging = false)
  dragFromIndex = -1
}

function showPreview(file) {
  previewFile.value = file
  previewVisible.value = true
}

// Upload helper
async function doUpload(file) {
  const res = await uploadFile(file)
  if (res.data.code === 200) return res.data.data.taskId
  throw new Error(res.data.message || '上传失败')
}

// Batch: merge selected images into one PDF
async function doMergePdf() {
  const selected = files.value.filter(f => f.selected)
  if (selected.length === 0) return ElMessage.warning('请至少选择一张图片')

  converting.value = true
  results.value = []
  batchProgress.total = selected.length + 1
  batchProgress.done = 0
  batchProgress.current = '上传图片中...'

  try {
    const taskIds = []
    for (let i = 0; i < selected.length; i++) {
      batchProgress.current = `上传 ${i + 1}/${selected.length}: ${selected[i].file.name}`
      const id = await doUpload(selected[i].file)
      taskIds.push(id)
      batchProgress.done = i + 1
    }

    batchProgress.current = '合并 PDF 中...'
    const name = outputName.value ? outputName.value.replace(/\.pdf$/i, '') + '.pdf' : null
    const res = await mergeToPdf(taskIds, name)

    if (res.data.code === 200) {
      results.value = [res.data.data]
      batchProgress.done = batchProgress.total
      ElNotification({ title: '合并成功', message: `${selected.length} 张图片已合并为 PDF`, type: 'success' })
    } else {
      ElMessage.error(res.data.message || '合并失败')
    }
  } catch (err) {
    ElMessage.error('操作失败: ' + (err.response?.data?.message || err.message))
  } finally {
    converting.value = false
    setTimeout(() => { batchProgress.total = 0 }, 2000)
  }
}

// PDF to Word conversion
async function doPdfToWord() {
  const selected = files.value.filter(f => f.selected)
  if (selected.length === 0) return ElMessage.warning('请选择一个 PDF 文件')

  converting.value = true
  results.value = []
  batchProgress.total = 2
  batchProgress.done = 0

  try {
    batchProgress.current = `上传 ${selected[0].file.name}`
    const id = await doUpload(selected[0].file)
    batchProgress.done = 1

    batchProgress.current = `转换 ${selected[0].file.name}`
    const res = await convertToWord(id)
    if (res.data.code === 200) {
      results.value.push(res.data.data)
      ElNotification({ title: '转换完成', message: 'PDF 已转换为 Word 文档', type: 'success' })
    } else {
      ElMessage.error(res.data.message || '转换失败')
    }
  } catch (err) {
    ElMessage.error('操作失败: ' + (err.response?.data?.message || err.message))
  } finally {
    converting.value = false
    setTimeout(() => { batchProgress.total = 0 }, 2000)
  }
}

// Batch: scan each
async function doBatchScan() {
  const selected = files.value.filter(f => f.selected)
  if (selected.length === 0) return ElMessage.warning('请至少选择一张图片')
  if (!scanType.value) return ElMessage.warning('请选择扫描输出格式')

  converting.value = true
  results.value = []
  batchProgress.total = selected.length * 2
  batchProgress.done = 0

  const scanFn = { image: scanToImage, pdf: scanToPdf, word: scanToWord }[scanType.value]

  try {
    for (let i = 0; i < selected.length; i++) {
      batchProgress.current = `上传 ${selected[i].file.name}`
      const id = await doUpload(selected[i].file)
      batchProgress.done = i * 2 + 1

      batchProgress.current = `扫描 ${selected[i].file.name}`
      const res = await scanFn(id)
      if (res.data.code === 200) results.value.push(res.data.data)
      batchProgress.done = i * 2 + 2
    }
    ElNotification({ title: '扫描完成', message: `${results.value.length} 个扫描件已生成`, type: 'success' })
  } catch (err) {
    ElMessage.error('操作失败: ' + (err.response?.data?.message || err.message))
  } finally {
    converting.value = false
    setTimeout(() => { batchProgress.total = 0 }, 2000)
  }
}

// ============ Scan Editor ============
function openScanEditor(file) {
  scanEditorFile.value = file
  scanOutputFormat.value = 'image'
  scanProcessing.value = false
  scanEditorVisible.value = true
  nextTick(() => initScanCanvas(file))
}

function initScanCanvas(file) {
  const canvas = scanCanvas.value
  const wrap = scanCanvasWrap.value
  if (!canvas || !wrap) return

  const img = new Image()
  img.onload = () => {
    scanImg = img
    const wrapW = wrap.clientWidth
    const wrapH = wrap.clientHeight

    scanScale = Math.min(wrapW / img.width, wrapH / img.height, 1)
    const drawW = img.width * scanScale
    const drawH = img.height * scanScale
    scanOffsetX = (wrapW - drawW) / 2
    scanOffsetY = (wrapH - drawH) / 2

    canvas.width = wrapW
    canvas.height = wrapH

    const margin = 0.1
    scanCorners.pts = [
      { x: img.width * margin, y: img.height * margin },
      { x: img.width * (1 - margin), y: img.height * margin },
      { x: img.width * (1 - margin), y: img.height * (1 - margin) },
      { x: img.width * margin, y: img.height * (1 - margin) },
    ]
    drawScanCanvas()
  }
  img.src = file.url
}

function drawScanCanvas() {
  const canvas = scanCanvas.value
  if (!canvas || !scanImg) return
  const ctx = canvas.getContext('2d')
  const w = canvas.width, h = canvas.height

  ctx.clearRect(0, 0, w, h)

  const drawW = scanImg.width * scanScale
  const drawH = scanImg.height * scanScale
  ctx.drawImage(scanImg, scanOffsetX, scanOffsetY, drawW, drawH)

  // Dim outside quad
  ctx.save()
  ctx.fillStyle = 'rgba(0, 0, 0, 0.45)'
  ctx.fillRect(0, 0, w, h)
  ctx.globalCompositeOperation = 'destination-out'

  const pts = scanCorners.pts
  if (pts.length === 4) {
    ctx.beginPath()
    ctx.moveTo(pts[0].x * scanScale + scanOffsetX, pts[0].y * scanScale + scanOffsetY)
    for (let i = 1; i < 4; i++) ctx.lineTo(pts[i].x * scanScale + scanOffsetX, pts[i].y * scanScale + scanOffsetY)
    ctx.closePath()
    ctx.fill()
  }
  ctx.restore()

  if (pts.length === 4) {
    // Outline
    ctx.strokeStyle = '#4f6ef7'
    ctx.lineWidth = 2.5
    ctx.setLineDash([])
    ctx.beginPath()
    ctx.moveTo(pts[0].x * scanScale + scanOffsetX, pts[0].y * scanScale + scanOffsetY)
    for (let i = 1; i < 4; i++) ctx.lineTo(pts[i].x * scanScale + scanOffsetX, pts[i].y * scanScale + scanOffsetY)
    ctx.closePath()
    ctx.stroke()

    // Corner handles
    for (let i = 0; i < 4; i++) {
      const cx = pts[i].x * scanScale + scanOffsetX
      const cy = pts[i].y * scanScale + scanOffsetY
      ctx.fillStyle = '#fff'
      ctx.strokeStyle = '#4f6ef7'
      ctx.lineWidth = 2.5
      ctx.beginPath()
      ctx.arc(cx, cy, 10, 0, Math.PI * 2)
      ctx.fill()
      ctx.stroke()
      ctx.fillStyle = '#4f6ef7'
      ctx.beginPath()
      ctx.arc(cx, cy, 4, 0, Math.PI * 2)
      ctx.fill()
    }

    // Dashed edge lines
    ctx.strokeStyle = 'rgba(79, 110, 247, 0.4)'
    ctx.lineWidth = 1
    ctx.setLineDash([6, 4])
    ctx.beginPath()
    for (let i = 0; i < 4; i++) {
      const next = (i + 1) % 4
      ctx.moveTo(pts[i].x * scanScale + scanOffsetX, pts[i].y * scanScale + scanOffsetY)
      ctx.lineTo(pts[next].x * scanScale + scanOffsetX, pts[next].y * scanScale + scanOffsetY)
    }
    ctx.stroke()
    ctx.setLineDash([])
  }
}

function canvasToImgCoord(clientX, clientY) {
  const canvas = scanCanvas.value
  const rect = canvas.getBoundingClientRect()
  return {
    x: (clientX - rect.left - scanOffsetX) / scanScale,
    y: (clientY - rect.top - scanOffsetY) / scanScale
  }
}

function findNearestCorner(imgX, imgY) {
  let minDist = Infinity, idx = -1
  for (let i = 0; i < scanCorners.pts.length; i++) {
    const dx = scanCorners.pts[i].x - imgX
    const dy = scanCorners.pts[i].y - imgY
    const d = Math.sqrt(dx * dx + dy * dy)
    if (d < minDist) { minDist = d; idx = i }
  }
  return minDist < 30 / scanScale ? idx : -1
}

function onCanvasMouseDown(e) {
  const { x, y } = canvasToImgCoord(e.clientX, e.clientY)
  draggingCornerIdx = findNearestCorner(x, y)
}

function onCanvasMouseMove(e) {
  if (draggingCornerIdx < 0) return
  const { x, y } = canvasToImgCoord(e.clientX, e.clientY)
  scanCorners.pts[draggingCornerIdx].x = Math.max(0, Math.min(scanImg.width, x))
  scanCorners.pts[draggingCornerIdx].y = Math.max(0, Math.min(scanImg.height, y))
  drawScanCanvas()
}

function onCanvasMouseUp() { draggingCornerIdx = -1 }

function onCanvasTouchStart(e) {
  const t = e.touches[0]
  const { x, y } = canvasToImgCoord(t.clientX, t.clientY)
  draggingCornerIdx = findNearestCorner(x, y)
}

function onCanvasTouchMove(e) {
  if (draggingCornerIdx < 0) return
  const t = e.touches[0]
  const { x, y } = canvasToImgCoord(t.clientX, t.clientY)
  scanCorners.pts[draggingCornerIdx].x = Math.max(0, Math.min(scanImg.width, x))
  scanCorners.pts[draggingCornerIdx].y = Math.max(0, Math.min(scanImg.height, y))
  drawScanCanvas()
}

function resetCorners() {
  if (!scanImg) return
  const m = 0.1
  scanCorners.pts = [
    { x: scanImg.width * m, y: scanImg.height * m },
    { x: scanImg.width * (1 - m), y: scanImg.height * m },
    { x: scanImg.width * (1 - m), y: scanImg.height * (1 - m) },
    { x: scanImg.width * m, y: scanImg.height * (1 - m) },
  ]
  drawScanCanvas()
}

async function confirmScan() {
  if (!scanEditorFile.value) return
  scanProcessing.value = true
  try {
    const res = await uploadFile(scanEditorFile.value.file)
    if (res.data.code !== 200) { ElMessage.error('上传失败'); return }
    const taskId = res.data.data.taskId

    const corners = scanCorners.pts.map(p => [Math.round(p.x), Math.round(p.y)])
    const scanRes = await scanWithCorners(taskId, corners, scanOutputFormat.value)
    if (scanRes.data.code === 200) {
      results.value.push(scanRes.data.data)
      scanEditorVisible.value = false
      ElNotification({ title: '扫描成功', message: scanRes.data.data.outputFilename, type: 'success' })
    } else {
      ElMessage.error(scanRes.data.message || '扫描失败')
    }
  } catch (err) {
    ElMessage.error('扫描失败: ' + (err.response?.data?.message || err.message))
  } finally {
    scanProcessing.value = false
  }
}

// Download
function downloadResult(r) {
  window.open(getDownloadUrl(r.taskId), '_blank')
}
function downloadAll() {
  results.value.forEach(r => {
    setTimeout(() => window.open(getDownloadUrl(r.taskId), '_blank'), 300)
  })
}

// History
async function loadHistory() {
  loadingHistory.value = true
  try {
    const res = await getTasks()
    if (res.data.code === 200) tasks.value = res.data.data
  } catch { /* ignore */ }
  loadingHistory.value = false
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.originalFilename}」及其文件吗？此操作不可恢复。`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    const res = await deleteTask(row.id)
    if (res.data.code === 200) {
      ElMessage.success('已删除')
      loadHistory()
    } else {
      ElMessage.error(res.data.message || '删除失败')
    }
  } catch { /* user cancelled */ }
}

onMounted(loadHistory)

// Helpers
function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}
function formatTime(t) { return t ? new Date(t).toLocaleString('zh-CN') : '-' }
function getConversionLabel(type) {
  return { IMAGE_TO_PDF: '图片→PDF', IMAGE_TO_WORD: '图片→Word', PDF_TO_WORD: 'PDF→Word',
    IMAGE_SCAN_TO_IMAGE: '扫描→图片', IMAGE_SCAN_TO_PDF: '扫描→PDF', IMAGE_SCAN_TO_WORD: '扫描→Word' }[type] || type
}
function getTagType(type) {
  if (type?.includes('PDF')) return 'danger'
  if (type?.includes('WORD')) return ''
  if (type?.includes('IMAGE')) return 'success'
  return 'info'
}
function getStatusLabel(s) {
  return { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' }[s] || s
}
function getResultExt(name) { return name?.split('.').pop()?.toUpperCase() || '?' }
function getResultType(name) {
  if (name?.endsWith('.pdf')) return 'type-pdf'
  if (name?.endsWith('.docx')) return 'type-word'
  return 'type-img'
}
</script>

<style>
/* ============ Reset & Base ============ */
* { margin: 0; padding: 0; box-sizing: border-box; }
:root {
  --bg: #f0f2f5;
  --surface: #ffffff;
  --primary: #4f6ef7;
  --primary-light: #eef1ff;
  --primary-dark: #3a56d4;
  --text: #1a1a2e;
  --text-sec: #6b7280;
  --border: #e5e7eb;
  --success: #10b981;
  --danger: #ef4444;
  --warning: #f59e0b;
  --radius: 12px;
  --shadow: 0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04);
  --shadow-lg: 0 10px 40px rgba(0,0,0,0.08);
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
  -webkit-font-smoothing: antialiased;
}
.app { min-height: 100vh; display: flex; flex-direction: column; }

/* ============ Header ============ */
.header {
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  position: sticky; top: 0; z-index: 100;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}
.header-inner {
  max-width: 1200px; margin: 0 auto;
  padding: 0 24px; height: 60px;
  display: flex; align-items: center; justify-content: space-between;
}
.brand { display: flex; align-items: center; gap: 12px; }
.brand-icon {
  width: 40px; height: 40px; border-radius: 10px;
  background: linear-gradient(135deg, var(--primary), #7c3aed);
  display: flex; align-items: center; justify-content: center;
  color: #fff; box-shadow: 0 4px 12px rgba(79,110,247,0.3);
}
.brand-name { font-size: 18px; font-weight: 700; letter-spacing: -0.5px; }
.brand-desc { font-size: 12px; color: var(--text-sec); margin-top: -2px; }
.history-btn { font-size: 14px; color: var(--text-sec); }
.history-btn .arrow { transition: transform 0.3s; margin-left: 4px; }
.history-btn .arrow.open { transform: rotate(180deg); }

/* ============ History Panel ============ */
.history-panel {
  background: var(--surface); border-bottom: 1px solid var(--border);
  box-shadow: 0 4px 12px rgba(0,0,0,0.04);
}
.history-inner { max-width: 1200px; margin: 0 auto; padding: 16px 24px; }
.history-toolbar {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
}
.history-title { font-weight: 600; font-size: 15px; }
.slide-down-enter-active, .slide-down-leave-active { transition: all 0.3s ease; }
.slide-down-enter-from, .slide-down-leave-to { opacity: 0; transform: translateY(-10px); }

/* ============ Main ============ */
.main { flex: 1; max-width: 1200px; margin: 0 auto; padding: 24px; width: 100%; }

/* Mode Bar */
.mode-bar { margin-bottom: 24px; display: flex; justify-content: center; }
.mode-tabs {
  display: inline-flex; background: var(--surface);
  border-radius: 12px; padding: 4px; box-shadow: var(--shadow);
  border: 1px solid var(--border);
}
.mode-tab {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 24px; border: none; background: transparent;
  border-radius: 10px; cursor: pointer; font-size: 14px; font-weight: 500;
  color: var(--text-sec); transition: all 0.25s;
}
.mode-tab:hover { color: var(--text); background: var(--bg); }
.mode-tab.active {
  background: var(--primary); color: #fff;
  box-shadow: 0 2px 8px rgba(79,110,247,0.3);
}
.mode-tab .el-icon { font-size: 16px; }

/* Content Layout */
.content { display: flex; gap: 24px; align-items: flex-start; }

/* ============ Upload Zone ============ */
.upload-zone { flex: 1; min-width: 0; }
.upload-zone:not(.has-files) { max-width: 640px; margin: 0 auto; }

.upload-empty {
  background: var(--surface); border: 2px dashed var(--border);
  border-radius: var(--radius); padding: 60px 40px;
  text-align: center; cursor: pointer; transition: all 0.3s;
}
.upload-empty.dragover {
  border-color: var(--primary); background: var(--primary-light);
  transform: scale(1.01);
}
.upload-empty:hover { border-color: var(--primary); }

.upload-visual { position: relative; display: inline-block; margin-bottom: 20px; }
.upload-circle {
  width: 72px; height: 72px; border-radius: 50%;
  background: linear-gradient(135deg, var(--primary), #7c3aed);
  display: flex; align-items: center; justify-content: center;
  color: #fff; position: relative; z-index: 1;
  box-shadow: 0 8px 24px rgba(79,110,247,0.25);
}
.upload-rings {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  width: 100px; height: 100px; border-radius: 50%;
  border: 2px solid rgba(79,110,247,0.15);
  animation: pulse-ring 2s ease-out infinite;
}
@keyframes pulse-ring {
  0% { transform: translate(-50%, -50%) scale(0.8); opacity: 1; }
  100% { transform: translate(-50%, -50%) scale(1.4); opacity: 0; }
}
.upload-empty h3 { font-size: 18px; margin-bottom: 8px; font-weight: 600; }
.upload-empty p { color: var(--text-sec); margin-bottom: 20px; font-size: 14px; }
.upload-btn { padding: 12px 32px; }
.upload-hint { display: block; margin-top: 12px; font-size: 12px; color: #9ca3af; }

/* ============ File Gallery ============ */
.gallery {
  background: var(--surface); border-radius: var(--radius);
  border: 1px solid var(--border); box-shadow: var(--shadow);
  overflow: hidden;
}
.gallery-toolbar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px; border-bottom: 1px solid var(--border);
  background: #fafbfc;
}
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; align-items: center; gap: 4px; }
.file-count { font-size: 13px; color: var(--text-sec); }

.gallery-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 14px; padding: 16px;
  max-height: 520px; overflow-y: auto;
}

/* File Card */
.file-card {
  position: relative; border-radius: 10px; overflow: hidden;
  border: 2px solid var(--border); background: #fff;
  transition: all 0.2s; cursor: grab;
}
.file-card:hover { border-color: #c0c4cc; box-shadow: 0 4px 12px rgba(0,0,0,0.06); }
.file-card.selected { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(79,110,247,0.12); }
.file-card.dragging { opacity: 0.4; transform: scale(0.95); }

.card-check {
  position: absolute; top: 8px; left: 8px; z-index: 2;
  background: rgba(255,255,255,0.9); border-radius: 6px; padding: 2px;
  backdrop-filter: blur(4px);
}
.card-check .el-checkbox { --el-checkbox-checked-bg-color: var(--primary); }

.card-index {
  position: absolute; top: 8px; right: 8px; z-index: 2;
  width: 22px; height: 22px; border-radius: 50%;
  background: rgba(0,0,0,0.5); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600;
}
.card-img {
  width: 100%; aspect-ratio: 4/3; overflow: hidden; cursor: pointer;
  background: #f5f5f5; position: relative;
}
.card-img img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.3s; }
.card-img:hover img { transform: scale(1.05); }
.pdf-preview {
  width: 100%; height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 8px;
  background: linear-gradient(135deg, #fff5f5 0%, #fef2f2 100%);
}
.pdf-preview span { font-size: 12px; color: var(--text-sec); font-weight: 500; }
.card-overlay {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  background: rgba(0,0,0,0.3); opacity: 0; transition: opacity 0.2s;
  color: #fff;
}
.card-img:hover .card-overlay { opacity: 1; }
.card-info { padding: 8px 10px; }
.card-name {
  display: block; font-size: 12px; font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.card-size { font-size: 11px; color: var(--text-sec); }
.card-remove {
  position: absolute; bottom: 8px; right: 8px;
  width: 24px; height: 24px; border-radius: 50%;
  border: none; background: rgba(0,0,0,0.5); color: #fff;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; opacity: 0; transition: all 0.2s;
}
.file-card:hover .card-remove { opacity: 1; }
.card-remove:hover { background: var(--danger); }

/* ============ Action Panel ============ */
.action-panel { width: 300px; flex-shrink: 0; position: sticky; top: 84px; }
.panel-card {
  background: var(--surface); border-radius: var(--radius);
  border: 1px solid var(--border); box-shadow: var(--shadow);
  padding: 20px;
}
.panel-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 15px; font-weight: 600; margin-bottom: 20px;
  padding-bottom: 12px; border-bottom: 1px solid var(--border);
}
.panel-title .el-icon { color: var(--primary); }
.panel-section { display: flex; flex-direction: column; gap: 16px; }

.option-label { font-size: 13px; font-weight: 500; color: var(--text-sec); display: block; margin-bottom: 6px; }
.option-group { display: flex; flex-direction: column; }
.option-info {
  display: flex; align-items: flex-start; gap: 8px;
  font-size: 12px; color: var(--text-sec); line-height: 1.5;
  background: var(--primary-light); padding: 10px 12px; border-radius: 8px;
}
.option-info .el-icon { color: var(--primary); margin-top: 2px; flex-shrink: 0; }

.action-btn { margin-top: 4px; height: 44px; font-size: 15px; }

/* Scan choices */
.scan-choices { display: flex; flex-direction: column; gap: 8px; }
.scan-choice {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border: 1px solid var(--border);
  border-radius: 8px; cursor: pointer; transition: all 0.2s; font-size: 14px;
}
.scan-choice:hover { border-color: var(--primary); background: var(--primary-light); }
.scan-choice.active { border-color: var(--primary); background: var(--primary-light); color: var(--primary); }
.scan-choice .el-icon { font-size: 18px; }

/* Progress */
.progress-section { margin-top: 8px; }
.progress-header {
  display: flex; justify-content: space-between;
  font-size: 12px; color: var(--text-sec); margin-bottom: 8px;
}
.progress-file {
  font-size: 11px; color: var(--text-sec);
  margin-top: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* ============ Results ============ */
.results-section {
  margin-top: 24px; background: var(--surface);
  border-radius: var(--radius); border: 1px solid var(--border);
  box-shadow: var(--shadow); padding: 20px;
  max-width: 1200px; margin-left: auto; margin-right: auto; width: 100%;
}
.results-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.results-header h3 {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; color: var(--success);
}
.results-grid { display: flex; flex-direction: column; gap: 10px; }
.result-card {
  display: flex; align-items: center; gap: 14px;
  padding: 12px 16px; border: 1px solid var(--border);
  border-radius: 10px; transition: all 0.2s;
}
.result-card:hover { border-color: var(--primary); background: #fafbff; }
.result-icon {
  width: 44px; height: 44px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; color: #fff;
  flex-shrink: 0;
}
.type-pdf { background: linear-gradient(135deg, #ef4444, #dc2626); }
.type-word { background: linear-gradient(135deg, #3b82f6, #2563eb); }
.type-img { background: linear-gradient(135deg, #10b981, #059669); }
.result-info { flex: 1; min-width: 0; }
.result-name { display: block; font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.result-meta { font-size: 12px; color: var(--text-sec); }

/* Status dots */
.status-dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px;
}
.status-pending { background: #d1d5db; }
.status-processing { background: var(--warning); animation: pulse 1s infinite; }
.status-completed { background: var(--success); }
.status-failed { background: var(--danger); }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

/* Fade up transition */
.fade-up-enter-active { transition: all 0.4s ease; }
.fade-up-leave-active { transition: all 0.3s ease; }
.fade-up-enter-from { opacity: 0; transform: translateY(16px); }
.fade-up-leave-to { opacity: 0; transform: translateY(-10px); }

/* Preview dialog */
.preview-dialog .el-dialog__body { padding: 0; }
.preview-img { max-width: 90vw; max-height: 80vh; display: block; }
.preview-name { font-size: 13px; color: var(--text-sec); }

/* ============ Scan Editor ============ */
.scan-dialog .el-dialog__header { display: none; }
.scan-dialog .el-dialog__body { padding: 0; height: 100vh; display: flex; flex-direction: column; }
.scan-editor { display: flex; flex-direction: column; height: 100%; background: #1a1a2e; }

.scan-toolbar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 20px; background: #16162a; border-bottom: 1px solid rgba(255,255,255,0.08);
  flex-shrink: 0;
}
.scan-toolbar-left { display: flex; align-items: center; gap: 10px; color: #fff; }
.scan-toolbar-title { font-size: 16px; font-weight: 600; }
.scan-toolbar-hint { font-size: 13px; color: rgba(255,255,255,0.5); }
.scan-toolbar-right { display: flex; gap: 8px; }

.scan-canvas-wrap {
  flex: 1; position: relative; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
  cursor: crosshair;
}
.scan-canvas { display: block; }

.scan-format-bar {
  display: flex; align-items: center; gap: 12px; justify-content: center;
  padding: 12px 20px; background: #16162a; border-top: 1px solid rgba(255,255,255,0.08);
  flex-shrink: 0;
}
.scan-format-label { color: rgba(255,255,255,0.6); font-size: 14px; }

/* Scan tip */
.scan-tip {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; margin: 12px 16px 0;
  background: #fff7ed; border: 1px solid #fed7aa; border-radius: 8px;
  font-size: 13px; color: #9a3412;
}
.scan-tip .el-icon { color: #f97316; font-size: 16px; flex-shrink: 0; }

/* ============ Responsive ============ */
@media (max-width: 860px) {
  .content { flex-direction: column; }
  .action-panel { width: 100%; position: static; }
  .gallery-grid { grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); }
  .upload-zone:not(.has-files) { max-width: 100%; }
}
@media (max-width: 600px) {
  .main { padding: 16px; }
  .mode-tabs { width: 100%; }
  .mode-tab { flex: 1; justify-content: center; padding: 10px 12px; font-size: 13px; }
  .upload-empty { padding: 40px 20px; }
  .gallery-grid { grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 10px; padding: 12px; }
}
@media (max-width: 600px) {
  .main { padding: 16px; }
  .mode-tabs { width: 100%; }
  .mode-tab { flex: 1; justify-content: center; padding: 10px 12px; font-size: 13px; }
  .upload-empty { padding: 40px 20px; }
}
</style>
