package com.transformpdf.controller;

import com.transformpdf.entity.ConversionTask;
import com.transformpdf.repository.ConversionTaskRepository;
import com.transformpdf.service.ConversionService;
import com.transformpdf.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/convert")
@RequiredArgsConstructor
@Slf4j
public class ConversionController {

    private final ConversionService conversionService;
    private final FileStorageService fileStorageService;
    private final ConversionTaskRepository taskRepository;

    @Value("${server.port:8080}")
    private String serverPort;

    /**
     * Upload an image file
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ApiResponse.error("请选择要上传的文件");
            }

            String originalFilename = file.getOriginalFilename();
            String contentType = file.getContentType();

            // Validate file type (images or PDF)
            if (contentType == null || (!contentType.startsWith("image/") && !contentType.equals("application/pdf"))) {
                return ApiResponse.error("仅支持 JPG、PNG、PDF 格式的文件");
            }

            // Store the file
            String storedPath = fileStorageService.storeFile(file, "originals");

            // Create task record
            ConversionTask task = new ConversionTask();
            task.setOriginalFilename(originalFilename);
            task.setStoredFilename(storedPath.substring(storedPath.lastIndexOf("/") + 1));
            task.setFilePath(storedPath);
            task.setFileSize(file.getSize());
            task.setFileType(contentType);
            task.setStatus(ConversionTask.TaskStatus.PENDING);
            task = taskRepository.save(task);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", task.getId());
            result.put("filename", originalFilename);
            result.put("filePath", storedPath);
            result.put("previewUrl", "/api/convert/preview/" + storedPath);

            return ApiResponse.success("文件上传成功", result);
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return ApiResponse.error("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * Convert image to PDF
     */
    @PostMapping("/to-pdf")
    public ApiResponse<Map<String, Object>> convertToPdf(@RequestBody Map<String, Long> request) {
        return processConversion(request.get("taskId"), ConversionTask.ConversionType.IMAGE_TO_PDF);
    }

    /**
     * Convert PDF to Word
     */
    @PostMapping("/to-word")
    public ApiResponse<Map<String, Object>> convertToWord(@RequestBody Map<String, Long> request) {
        return processConversion(request.get("taskId"), ConversionTask.ConversionType.PDF_TO_WORD);
    }

    /**
     * Apply scan effect and output as image
     */
    @PostMapping("/scan-to-image")
    public ApiResponse<Map<String, Object>> scanToImage(@RequestBody Map<String, Long> request) {
        return processConversion(request.get("taskId"), ConversionTask.ConversionType.IMAGE_SCAN_TO_IMAGE);
    }

    /**
     * Apply scan effect and output as PDF
     */
    @PostMapping("/scan-to-pdf")
    public ApiResponse<Map<String, Object>> scanToPdf(@RequestBody Map<String, Long> request) {
        return processConversion(request.get("taskId"), ConversionTask.ConversionType.IMAGE_SCAN_TO_PDF);
    }

    /**
     * Apply scan effect and output as Word
     */
    @PostMapping("/scan-to-word")
    public ApiResponse<Map<String, Object>> scanToWord(@RequestBody Map<String, Long> request) {
        return processConversion(request.get("taskId"), ConversionTask.ConversionType.IMAGE_SCAN_TO_WORD);
    }

    /**
     * Scan with user-specified corners: [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
     * corners order: top-left, top-right, bottom-right, bottom-left
     */
    @PostMapping("/scan-doc")
    public ApiResponse<Map<String, Object>> scanWithCorners(@RequestBody Map<String, Object> request) {
        try {
            Long taskId = ((Number) request.get("taskId")).longValue();
            String format = (String) request.getOrDefault("format", "image");

            @SuppressWarnings("unchecked")
            List<List<Number>> cornersList = (List<List<Number>>) request.get("corners");
            if (cornersList == null || cornersList.size() != 4) {
                return ApiResponse.error("请提供4个角点坐标");
            }

            int[][] corners = new int[4][2];
            for (int i = 0; i < 4; i++) {
                corners[i][0] = cornersList.get(i).get(0).intValue();
                corners[i][1] = cornersList.get(i).get(1).intValue();
            }

            ConversionTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) return ApiResponse.error("任务不存在");

            task.setConversionType(switch (format) {
                case "pdf" -> ConversionTask.ConversionType.IMAGE_SCAN_TO_PDF;
                case "word" -> ConversionTask.ConversionType.IMAGE_SCAN_TO_WORD;
                default -> ConversionTask.ConversionType.IMAGE_SCAN_TO_IMAGE;
            });
            task.setStatus(ConversionTask.TaskStatus.PROCESSING);
            taskRepository.save(task);

            String outputPath;
            String outputFilename;
            switch (format) {
                case "pdf" -> {
                    outputPath = conversionService.scanToPdf(task, corners);
                    outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "pdf");
                }
                case "word" -> {
                    outputPath = conversionService.scanToWord(task, corners);
                    outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "docx");
                }
                default -> {
                    outputPath = conversionService.scanToImage(task, corners);
                    outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "jpg");
                }
            }

            task.setOutputPath(outputPath);
            task.setOutputFilename(outputFilename);
            task.setStatus(ConversionTask.TaskStatus.COMPLETED);
            taskRepository.save(task);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", task.getId());
            result.put("outputFilename", outputFilename);
            result.put("downloadUrl", "/api/convert/download/" + task.getId());
            result.put("previewUrl", "/api/convert/preview/" + outputPath);

            return ApiResponse.success("扫描完成", result);
        } catch (Exception e) {
            log.error("扫描失败", e);
            return ApiResponse.error("扫描失败: " + e.getMessage());
        }
    }

    /**
     * Merge multiple images into a single PDF
     */
    @PostMapping("/merge-to-pdf")
    public ApiResponse<Map<String, Object>> mergeToPdf(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> taskIds = ((List<Number>) request.get("taskIds")).stream()
                    .map(Number::longValue).toList();
            String outputName = (String) request.get("outputName");

            if (taskIds == null || taskIds.isEmpty()) {
                return ApiResponse.error("请选择至少一张图片");
            }

            List<ConversionTask> tasks = taskRepository.findAllById(taskIds);
            if (tasks.isEmpty()) {
                return ApiResponse.error("未找到有效的任务");
            }

            String outputPath = conversionService.mergeImagesToPdf(tasks, outputName);
            String outputFilename = outputName != null && outputName.endsWith(".pdf")
                    ? outputName : "merged_images.pdf";

            // Create a record for tracking
            ConversionTask mergedTask = new ConversionTask();
            mergedTask.setOriginalFilename(outputFilename);
            mergedTask.setStoredFilename(outputFilename);
            mergedTask.setFilePath(outputPath);
            mergedTask.setConversionType(ConversionTask.ConversionType.IMAGE_TO_PDF);
            mergedTask.setStatus(ConversionTask.TaskStatus.COMPLETED);
            mergedTask.setOutputPath(outputPath);
            mergedTask.setOutputFilename(outputFilename);
            mergedTask = taskRepository.save(mergedTask);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", mergedTask.getId());
            result.put("outputFilename", outputFilename);
            result.put("downloadUrl", "/api/convert/download/" + mergedTask.getId());
            result.put("imageCount", tasks.size());

            return ApiResponse.success("合并完成，共 " + tasks.size() + " 张图片", result);
        } catch (Exception e) {
            log.error("合并PDF失败", e);
            return ApiResponse.error("合并失败: " + e.getMessage());
        }
    }

    /**
     * Download converted file
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long taskId) {
        var optTask = taskRepository.findById(taskId)
                .filter(task -> task.getStatus() == ConversionTask.TaskStatus.COMPLETED);
        if (optTask.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            ConversionTask task = optTask.get();
            Path filePath = fileStorageService.getFilePath(task.getOutputPath());
            Resource resource = new FileSystemResource(filePath.toFile());

            String encodedFilename = URLEncoder.encode(task.getOutputFilename(), StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodedFilename)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (Exception e) {
            log.error("文件下载失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete task and associated files
     */
    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable Long taskId) {
        return taskRepository.findById(taskId)
                .map(task -> {
                    // Delete original file
                    if (task.getFilePath() != null) {
                        try { fileStorageService.deleteFile(task.getFilePath()); } catch (Exception e) { log.warn("删除原文件失败: {}", e.getMessage()); }
                    }
                    // Delete output file
                    if (task.getOutputPath() != null) {
                        try { fileStorageService.deleteFile(task.getOutputPath()); } catch (Exception e) { log.warn("删除输出文件失败: {}", e.getMessage()); }
                    }
                    taskRepository.delete(task);
                    return ApiResponse.<Void>success("删除成功", null);
                })
                .orElse(ApiResponse.error("任务不存在"));
    }

    /**
     * Preview uploaded file
     */
    @GetMapping("/preview/{*filePath}")
    public ResponseEntity<Resource> previewFile(@PathVariable String filePath) {
        try {
            Path path = fileStorageService.getFilePath(filePath);
            Resource resource = new FileSystemResource(path.toFile());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = "image/jpeg";
            if (filePath.endsWith(".png")) {
                contentType = "image/png";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all conversion tasks
     */
    @GetMapping("/tasks")
    public ApiResponse<List<ConversionTask>> getTasks() {
        return ApiResponse.success(taskRepository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * Get task by ID
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ConversionTask> getTask(@PathVariable Long taskId) {
        return taskRepository.findById(taskId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("任务不存在"));
    }

    private ApiResponse<Map<String, Object>> processConversion(Long taskId, ConversionTask.ConversionType type) {
        try {
            ConversionTask task = taskRepository.findById(taskId)
                    .orElse(null);
            if (task == null) {
                return ApiResponse.error("任务不存在");
            }

            task.setConversionType(type);
            task.setStatus(ConversionTask.TaskStatus.PROCESSING);
            taskRepository.save(task);

            String outputPath;
            String outputFilename;

            switch (type) {
                case IMAGE_TO_PDF:
                    outputPath = conversionService.convertImageToPdf(task);
                    outputFilename = replaceExtension(task.getOriginalFilename(), "pdf");
                    break;
                case PDF_TO_WORD:
                    outputPath = conversionService.convertPdfToWord(task);
                    outputFilename = replaceExtension(task.getOriginalFilename(), "docx");
                    break;
                case IMAGE_SCAN_TO_IMAGE:
                    outputPath = conversionService.scanToImage(task);
                    outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "jpg");
                    break;
                case IMAGE_SCAN_TO_PDF:
                    outputPath = conversionService.scanToPdf(task);
                    outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "pdf");
                    break;
                case IMAGE_SCAN_TO_WORD:
                    outputPath = conversionService.scanToWord(task);
                    outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "docx");
                    break;
                default:
                    return ApiResponse.error("不支持的转换类型");
            }

            task.setOutputPath(outputPath);
            task.setOutputFilename(outputFilename);
            task.setStatus(ConversionTask.TaskStatus.COMPLETED);
            taskRepository.save(task);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", task.getId());
            result.put("outputFilename", outputFilename);
            result.put("downloadUrl", "/api/convert/download/" + task.getId());
            result.put("previewUrl", "/api/convert/preview/" + outputPath);

            return ApiResponse.success("转换完成", result);
        } catch (IOException e) {
            log.error("转换失败", e);
            // Update task status
            if (taskId != null) {
                taskRepository.findById(taskId).ifPresent(task -> {
                    task.setStatus(ConversionTask.TaskStatus.FAILED);
                    task.setErrorMessage(e.getMessage());
                    taskRepository.save(task);
                });
            }
            return ApiResponse.error("转换失败: " + e.getMessage());
        }
    }

    private String replaceExtension(String filename, String newExt) {
        if (filename == null) return "output." + newExt;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot >= 0) {
            return filename.substring(0, lastDot) + "." + newExt;
        }
        return filename + "." + newExt;
    }
}
