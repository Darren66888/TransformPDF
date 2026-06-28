import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 300000, // 5 minutes for image processing
})

/**
 * Upload image file
 */
export function uploadFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/convert/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * Convert to PDF
 */
export function convertToPdf(taskId) {
  return api.post('/convert/to-pdf', { taskId })
}

/**
 * Convert to Word
 */
export function convertToWord(taskId) {
  return api.post('/convert/to-word', { taskId })
}

/**
 * Scan to image
 */
export function scanToImage(taskId) {
  return api.post('/convert/scan-to-image', { taskId })
}

/**
 * Scan to PDF
 */
export function scanToPdf(taskId) {
  return api.post('/convert/scan-to-pdf', { taskId })
}

/**
 * Scan to Word
 */
export function scanToWord(taskId) {
  return api.post('/convert/scan-to-word', { taskId })
}

/**
 * Get task status
 */
export function getTask(taskId) {
  return api.get(`/convert/tasks/${taskId}`)
}

/**
 * Get all tasks
 */
export function getTasks() {
  return api.get('/convert/tasks')
}

/**
 * Get download URL
 */
export function getDownloadUrl(taskId) {
  return `/api/convert/download/${taskId}`
}

/**
 * Get preview URL
 */
export function getPreviewUrl(filePath) {
  return `/api/convert/preview/${filePath}`
}

/**
 * Merge multiple images into one PDF
 */
export function mergeToPdf(taskIds, outputName) {
  return api.post('/convert/merge-to-pdf', { taskIds, outputName })
}

/**
 * Delete a task and its files
 */
export function deleteTask(taskId) {
  return api.delete(`/convert/tasks/${taskId}`)
}

/**
 * Scan with user-specified corners
 */
export function scanWithCorners(taskId, corners, format) {
  return api.post('/convert/scan-doc', { taskId, corners, format })
}
