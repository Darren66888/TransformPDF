package com.transformpdf.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${app.upload.dir}")
    private String uploadDir;

    public String storeFile(MultipartFile file, String subDir) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String storedFilename = UUID.randomUUID().toString() + extension;

        Path targetDir = Paths.get(uploadDir, subDir).toAbsolutePath().normalize();
        Files.createDirectories(targetDir);

        Path targetLocation = targetDir.resolve(storedFilename);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

        return subDir + "/" + storedFilename;
    }

    public Path getFilePath(String relativePath) {
        return Paths.get(uploadDir).resolve(relativePath).toAbsolutePath().normalize();
    }

    public byte[] readFile(String relativePath) throws IOException {
        Path filePath = getFilePath(relativePath);
        return Files.readAllBytes(filePath);
    }

    public void deleteFile(String relativePath) throws IOException {
        Path filePath = getFilePath(relativePath);
        Files.deleteIfExists(filePath);
    }

    public String getUploadDir() {
        return uploadDir;
    }
}
