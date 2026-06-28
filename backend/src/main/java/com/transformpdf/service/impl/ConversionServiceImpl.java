package com.transformpdf.service.impl;

import com.transformpdf.entity.ConversionTask;
import com.transformpdf.service.ConversionService;
import com.transformpdf.service.FileStorageService;
import com.transformpdf.util.ImageProcessor;
import com.transformpdf.util.PdfContentExtractor;
import com.transformpdf.util.PdfContentExtractor.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversionServiceImpl implements ConversionService {

    private final FileStorageService fileStorageService;
    private final ImageProcessor imageProcessor;

    @Override
    public String convertImageToPdf(ConversionTask task) throws IOException {
        Path inputPath = fileStorageService.getFilePath(task.getFilePath());
        String outputFilename = replaceExtension(task.getOriginalFilename(), "pdf");
        String outputPath = "converted/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (PDDocument document = new PDDocument()) {
            BufferedImage bimg = ImageIO.read(inputPath.toFile());
            if (bimg == null) {
                throw new IOException("无法读取图片文件");
            }

            float width = bimg.getWidth();
            float height = bimg.getHeight();

            // Create page with image dimensions (in points, 72 dpi)
            PDPage page = new PDPage(new PDRectangle(width * 0.75f, height * 0.75f));
            document.addPage(page);

            byte[] imageBytes = Files.readAllBytes(inputPath);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, task.getOriginalFilename());
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }

            document.save(targetPath.toFile());
        }

        return outputPath;
    }

    @Override
    public String mergeImagesToPdf(List<ConversionTask> tasks, String outputName) throws IOException {
        String outputFilename = outputName != null ? outputName : "merged_images.pdf";
        if (!outputFilename.endsWith(".pdf")) outputFilename += ".pdf";
        String outputPath = "converted/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (PDDocument document = new PDDocument()) {
            for (ConversionTask task : tasks) {
                Path inputPath = fileStorageService.getFilePath(task.getFilePath());
                BufferedImage bimg = ImageIO.read(inputPath.toFile());
                if (bimg == null) {
                    log.warn("跳过无法读取的图片: {}", task.getOriginalFilename());
                    continue;
                }

                float width = bimg.getWidth();
                float height = bimg.getHeight();
                PDPage page = new PDPage(new PDRectangle(width * 0.75f, height * 0.75f));
                document.addPage(page);

                byte[] imageBytes = Files.readAllBytes(inputPath);
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, task.getOriginalFilename());
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }

            document.save(targetPath.toFile());
        }

        return outputPath;
    }

    @Override
    public String convertImageToWord(ConversionTask task) throws IOException {
        Path inputPath = fileStorageService.getFilePath(task.getFilePath());
        String outputFilename = replaceExtension(task.getOriginalFilename(), "docx");
        String outputPath = "converted/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();

            try (FileInputStream fis = new FileInputStream(inputPath.toFile())) {
                BufferedImage bimg = ImageIO.read(inputPath.toFile());
                if (bimg == null) {
                    throw new IOException("无法读取图片文件");
                }

                int width = bimg.getWidth();
                int height = bimg.getHeight();

                // Limit image size in Word document
                int maxWidth = 500;
                if (width > maxWidth) {
                    height = (int) ((float) height / width * maxWidth);
                    width = maxWidth;
                }

                try {
                    run.addPicture(fis, getPictureType(task.getOriginalFilename()),
                            task.getOriginalFilename(), Units.toEMU(width), Units.toEMU(height));
                } catch (Exception e) {
                    throw new IOException("添加图片到Word文档失败", e);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                document.write(fos);
            }
        }

        return outputPath;
    }

    /**
     * Convert PDF to Word with enhanced layout preservation.
     *
     * <p>Key improvements over the basic approach:
     * <ul>
     *   <li>Preserves font size, bold styling, and basic formatting</li>
     *   <li>Extracts and embeds images from PDF pages</li>
     *   <li>Detects headings by font size/weight for proper Word heading styles</li>
     *   <li>Groups text into proper paragraphs (not one-line-per-element)</li>
     *   <li>Detects tables with column-alignment recognition</li>
     *   <li>Preserves page boundaries with page breaks</li>
     * </ul>
     */
    @Override
    public String convertPdfToWord(ConversionTask task) throws IOException {
        Path inputPath = fileStorageService.getFilePath(task.getFilePath());
        String outputFilename = replaceExtension(task.getOriginalFilename(), "docx");
        String outputPath = "converted/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (PDDocument pdf = Loader.loadPDF(inputPath.toFile())) {
            PdfContentExtractor extractor = new PdfContentExtractor();
            List<PageContent> pages = extractor.extractContent(pdf);

            try (XWPFDocument docx = new XWPFDocument()) {

                for (int pi = 0; pi < pages.size(); pi++) {
                    PageContent page = pages.get(pi);
                    log.info("Building Word page {}/{}: {} blocks, {} images",
                            pi + 1, pages.size(), page.getBlocks().size(), page.getImages().size());

                    for (ContentBlock block : page.getBlocks()) {
                        switch (block.getType()) {
                            case HEADING -> writeHeading(docx, block);
                            case TABLE -> writeTable(docx, block);
                            case PARAGRAPH -> writeParagraph(docx, block);
                        }
                    }

                    // Insert embedded images found on this page
                    for (byte[] imageBytes : page.getImages()) {
                        try {
                            XWPFParagraph imgPara = docx.createParagraph();
                            imgPara.setAlignment(ParagraphAlignment.CENTER);
                            XWPFRun imgRun = imgPara.createRun();
                            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
                                imgRun.addPicture(bis, XWPFDocument.PICTURE_TYPE_PNG,
                                        "image.png", Units.toEMU(400), Units.toEMU(300));
                            }
                        } catch (Exception e) {
                            log.warn("Failed to embed image: {}", e.getMessage());
                        }
                    }

                    // Page break between pages (except after the last)
                    if (pi < pages.size() - 1) {
                        XWPFParagraph breakPara = docx.createParagraph();
                        breakPara.setPageBreak(true);
                    }
                }

                try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                    docx.write(fos);
                }
            }
        }

        return outputPath;
    }

    // ======================== Word Writing Helpers ========================

    /**
     * Write a heading block as a Word heading style.
     * Heading level 1 = largest font, level 3 = smallest heading.
     */
    private void writeHeading(XWPFDocument docx, ContentBlock block) {
        XWPFParagraph para = docx.createParagraph();

        // Map heading level to Word style
        String styleId = switch (block.getHeadingLevel()) {
            case 1 -> "Heading1";
            case 2 -> "Heading2";
            default -> "Heading3";
        };
        para.setStyle(styleId);

        XWPFRun run = para.createRun();
        run.setText(block.getText());
        run.setBold(true);

        // Scale font size: body=11pt, h1=18pt, h2=15pt, h3=13pt
        float fontSize = switch (block.getHeadingLevel()) {
            case 1 -> 18;
            case 2 -> 15;
            default -> 13;
        };
        run.setFontSize(fontSize);
    }

    /**
     * Write a paragraph block, preserving per-line font sizes and bold styling.
     */
    private void writeParagraph(XWPFDocument docx, ContentBlock block) {
        List<TextLine> lines = block.getLines();

        // For single-line paragraphs, use individual chunk formatting
        if (lines.size() == 1) {
            writeFormattedLine(docx.createParagraph(), lines.get(0));
            return;
        }

        // Multi-line paragraph — use a single paragraph with line breaks
        XWPFParagraph para = docx.createParagraph();
        for (int i = 0; i < lines.size(); i++) {
            TextLine line = lines.get(i);
            String text = line.getText().trim();
            if (text.isEmpty()) continue;

            if (i == 0) {
                writeFormattedLine(para, line);
            } else {
                // Add a line break within the same paragraph
                XWPFRun breakRun = para.createRun();
                breakRun.addBreak();
                writeChunksAsRuns(para, line);
            }
        }

        // Add spacing after paragraph
        para.setSpacingAfter(120); // 6pt after
    }

    /**
     * Write a single formatted line into a paragraph, respecting chunk-level formatting.
     */
    private void writeFormattedLine(XWPFParagraph para, TextLine line) {
        if (line.getChunks().size() == 1) {
            TextChunk chunk = line.getChunks().get(0);
            XWPFRun run = para.createRun();
            run.setText(chunk.getText());
            run.setFontSize(clampFontSize(chunk.getFontSize()));
            if (chunk.isBold()) run.setBold(true);
        } else {
            writeChunksAsRuns(para, line);
        }
    }

    /**
     * Write each text chunk as its own XWPFRun to preserve inline formatting differences.
     */
    private void writeChunksAsRuns(XWPFParagraph para, TextLine line) {
        for (TextChunk chunk : line.getChunks()) {
            XWPFRun run = para.createRun();
            run.setText(chunk.getText());
            run.setFontSize(clampFontSize(chunk.getFontSize()));
            if (chunk.isBold()) run.setBold(true);
        }
    }

    /**
     * Write a table block using column detection from X-positions.
     */
    private void writeTable(XWPFDocument docx, ContentBlock block) {
        List<TextLine> lines = block.getLines();
        if (lines.isEmpty()) return;

        // Detect columns by clustering X positions across all lines
        List<Float> columnXs = detectColumnPositions(lines);
        int numCols = columnXs.size();
        if (numCols < 2) {
            // Fallback: treat as formatted text
            writeParagraph(docx, new ContentBlock(BlockType.PARAGRAPH, lines, 0));
            return;
        }

        // Assign each chunk to its nearest column
        List<List<String>> rows = new ArrayList<>();
        for (TextLine line : lines) {
            List<String> cells = new ArrayList<>();
            // Initialize all cells as empty
            for (int c = 0; c < numCols; c++) cells.add("");

            for (TextChunk chunk : line.getChunks()) {
                int colIdx = findNearestColumn(chunk.getX(), columnXs);
                if (colIdx >= 0 && colIdx < numCols) {
                    String existing = cells.get(colIdx);
                    cells.set(colIdx, existing + (existing.isEmpty() ? "" : " ") + chunk.getText());
                }
            }

            // Only add non-empty rows
            if (cells.stream().anyMatch(s -> !s.trim().isEmpty())) {
                rows.add(cells);
            }
        }

        if (rows.isEmpty()) return;
        if (rows.size() < 2) {
            // Single row isn't really a table — write as text
            XWPFParagraph para = docx.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(rows.get(0).stream().filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("  |  ")));
            return;
        }

        // Create the Word table
        XWPFTable table = docx.createTable(rows.size(), numCols);

        // Apply borders
        setTableBorders(table);

        // Fill cells
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            List<String> cells = rows.get(r);
            for (int c = 0; c < numCols; c++) {
                String cellText = c < cells.size() ? cells.get(c).trim() : "";
                row.getCell(c).setText(cellText);

                // Bold the first row (likely a header)
                if (r == 0 && !cellText.isEmpty()) {
                    for (XWPFParagraph p : row.getCell(c).getParagraphs()) {
                        for (XWPFRun run : p.getRuns()) {
                            run.setBold(true);
                            run.setFontSize(10);
                        }
                    }
                }
            }
        }

        // Add spacing after table
        XWPFParagraph spacer = docx.createParagraph();
        spacer.setSpacingAfter(60);
    }

    /**
     * Detect column X positions by clustering left-edge X positions.
     * Uses simple clustering: group X positions that are within tolerance.
     */
    private List<Float> detectColumnPositions(List<TextLine> lines) {
        // Collect all left-edge X positions
        List<Float> allXs = new ArrayList<>();
        for (TextLine line : lines) {
            for (TextChunk chunk : line.getChunks()) {
                allXs.add(chunk.getX());
            }
        }

        if (allXs.isEmpty()) return Collections.emptyList();

        // Sort and cluster
        Collections.sort(allXs);

        float clusterTolerance = 15f; // pt tolerance for same column
        List<Float> clusters = new ArrayList<>();
        List<Float> currentCluster = new ArrayList<>();

        for (float x : allXs) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(x);
            } else if (x - currentCluster.get(0) <= clusterTolerance) {
                currentCluster.add(x);
            } else {
                // Finish current cluster
                float avg = (float) currentCluster.stream().mapToDouble(v -> v).average().orElse(0);
                clusters.add(avg);
                currentCluster.clear();
                currentCluster.add(x);
            }
        }

        // Don't forget last cluster
        if (!currentCluster.isEmpty()) {
            float avg = (float) currentCluster.stream().mapToDouble(v -> v).average().orElse(0);
            clusters.add(avg);
        }

        // Filter clusters that appear in enough lines (at least 40%)
        int minLines = Math.max(1, lines.size() * 2 / 5);
        List<Float> significant = new ArrayList<>();
        for (float cx : clusters) {
            int count = 0;
            for (TextLine line : lines) {
                boolean hasNear = line.getChunks().stream()
                        .anyMatch(c -> Math.abs(c.getX() - cx) <= clusterTolerance);
                if (hasNear) count++;
            }
            if (count >= minLines) {
                significant.add(cx);
            }
        }

        return significant;
    }

    private int findNearestColumn(float x, List<Float> columns) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < columns.size(); i++) {
            double dist = Math.abs(x - columns.get(i));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /**
     * Apply uniform borders to a table.
     */
    private void setTableBorders(XWPFTable table) {
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
    }

    /**
     * Clamp font size to a reasonable range for Word documents.
     */
    private float clampFontSize(float size) {
        return Math.min(Math.max(size, 6), 72);
    }

    @Override
    public String scanToImage(ConversionTask task) throws IOException {
        Path inputPath = fileStorageService.getFilePath(task.getFilePath());
        String outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "jpg");
        String outputPath = "scanned/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        BufferedImage original = ImageIO.read(inputPath.toFile());
        if (original == null) {
            throw new IOException("无法读取图片文件");
        }

        // Apply scanning effect: detect edges, perspective correct, enhance
        BufferedImage scanned = imageProcessor.applyScanEffect(original);
        ImageIO.write(scanned, "jpg", targetPath.toFile());

        return outputPath;
    }

    @Override
    public String scanToPdf(ConversionTask task) throws IOException {
        // First apply scan effect
        String scannedImagePath = scanToImage(task);

        Path scannedPath = fileStorageService.getFilePath(scannedImagePath);
        String outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "pdf");
        String outputPath = "scanned/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (PDDocument document = new PDDocument()) {
            BufferedImage bimg = ImageIO.read(scannedPath.toFile());
            float width = bimg.getWidth();
            float height = bimg.getHeight();

            PDPage page = new PDPage(new PDRectangle(width * 0.75f, height * 0.75f));
            document.addPage(page);

            byte[] imageBytes = Files.readAllBytes(scannedPath);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, outputFilename);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }

            document.save(targetPath.toFile());
        }

        return outputPath;
    }

    @Override
    public String scanToWord(ConversionTask task) throws IOException {
        // First apply scan effect
        String scannedImagePath = scanToImage(task);

        Path scannedPath = fileStorageService.getFilePath(scannedImagePath);
        String outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "docx");
        String outputPath = "scanned/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();

            try (FileInputStream fis = new FileInputStream(scannedPath.toFile())) {
                BufferedImage bimg = ImageIO.read(scannedPath.toFile());
                int width = bimg.getWidth();
                int height = bimg.getHeight();

                int maxWidth = 500;
                if (width > maxWidth) {
                    height = (int) ((float) height / width * maxWidth);
                    width = maxWidth;
                }

                try {
                    run.addPicture(fis, XWPFDocument.PICTURE_TYPE_JPEG,
                            outputFilename, Units.toEMU(width), Units.toEMU(height));
                } catch (Exception e) {
                    throw new IOException("添加图片到Word文档失败", e);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                document.write(fos);
            }
        }

        return outputPath;
    }

    @Override
    public String scanToImage(ConversionTask task, int[][] corners) throws IOException {
        Path inputPath = fileStorageService.getFilePath(task.getFilePath());
        String outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "jpg");
        String outputPath = "scanned/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        BufferedImage original = ImageIO.read(inputPath.toFile());
        if (original == null) throw new IOException("无法读取图片文件");

        // Use user-specified corners for perspective transform
        BufferedImage warped = imageProcessor.perspectiveTransform(original, corners);
        BufferedImage scanned = imageProcessor.enhanceScan(warped);
        ImageIO.write(scanned, "jpg", targetPath.toFile());

        return outputPath;
    }

    @Override
    public String scanToPdf(ConversionTask task, int[][] corners) throws IOException {
        String scannedImagePath = scanToImage(task, corners);

        Path scannedPath = fileStorageService.getFilePath(scannedImagePath);
        String outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "pdf");
        String outputPath = "scanned/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (PDDocument document = new PDDocument()) {
            BufferedImage bimg = ImageIO.read(scannedPath.toFile());
            float width = bimg.getWidth(), height = bimg.getHeight();
            PDPage page = new PDPage(new PDRectangle(width * 0.75f, height * 0.75f));
            document.addPage(page);

            byte[] imageBytes = Files.readAllBytes(scannedPath);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, outputFilename);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(targetPath.toFile());
        }
        return outputPath;
    }

    @Override
    public String scanToWord(ConversionTask task, int[][] corners) throws IOException {
        String scannedImagePath = scanToImage(task, corners);

        Path scannedPath = fileStorageService.getFilePath(scannedImagePath);
        String outputFilename = "scan_" + replaceExtension(task.getOriginalFilename(), "docx");
        String outputPath = "scanned/" + outputFilename;
        Path targetPath = fileStorageService.getFilePath(outputPath);

        Files.createDirectories(targetPath.getParent());

        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            try (FileInputStream fis = new FileInputStream(scannedPath.toFile())) {
                BufferedImage bimg = ImageIO.read(scannedPath.toFile());
                int width = bimg.getWidth(), height = bimg.getHeight();
                int maxWidth = 500;
                if (width > maxWidth) { height = (int) ((float) height / width * maxWidth); width = maxWidth; }
                try {
                    run.addPicture(fis, XWPFDocument.PICTURE_TYPE_JPEG, outputFilename, Units.toEMU(width), Units.toEMU(height));
                } catch (Exception e) { throw new IOException("添加图片到Word文档失败", e); }
            }
            try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) { document.write(fos); }
        }
        return outputPath;
    }

    private String replaceExtension(String filename, String newExt) {
        if (filename == null) return "output." + newExt;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot >= 0) {
            return filename.substring(0, lastDot) + "." + newExt;
        }
        return filename + "." + newExt;
    }

    private int getPictureType(String filename) {
        if (filename == null) return XWPFDocument.PICTURE_TYPE_PNG;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return XWPFDocument.PICTURE_TYPE_JPEG;
        } else if (lower.endsWith(".png")) {
            return XWPFDocument.PICTURE_TYPE_PNG;
        }
        return XWPFDocument.PICTURE_TYPE_PNG;
    }
}
