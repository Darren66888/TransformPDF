package com.transformpdf.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversion_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false)
    private String storedFilename;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "conversion_type")
    @Enumerated(EnumType.STRING)
    private ConversionType conversionType;

    @Column(name = "output_filename")
    private String outputFilename;

    @Column(name = "output_path")
    private String outputPath;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "is_scanned")
    private Boolean isScanned = false;

    @Column(name = "scan_area_json", columnDefinition = "TEXT")
    private String scanAreaJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum ConversionType {
        IMAGE_TO_PDF,
        IMAGE_TO_WORD,
        PDF_TO_WORD,
        IMAGE_SCAN_TO_PDF,
        IMAGE_SCAN_TO_WORD,
        IMAGE_SCAN_TO_IMAGE
    }

    public enum TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
