package com.transformpdf.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced PDF content extractor that captures:
 * <ul>
 *   <li>Text with precise position (x, y, width, height)</li>
 *   <li>Font properties (name, size, bold detection)</li>
 *   <li>Embedded images from page resources</li>
 *   <li>Structured grouping into paragraphs, headings, and tables</li>
 * </ul>
 *
 * <p>Inspired by the PDFBox+POI approach described in the CSDN article,
 * but significantly enhanced with position-aware extraction for layout preservation.</p>
 */
@Slf4j
public class PdfContentExtractor {

    /**
     * Represents a single text fragment with its position and font metadata.
     */
    @Getter
    public static class TextChunk {
        private final String text;
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final float fontSize;
        private final String fontName;
        private final boolean isBold;

        public TextChunk(String text, float x, float y, float width, float height,
                         float fontSize, String fontName, boolean isBold) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.fontSize = fontSize;
            this.fontName = fontName;
            this.isBold = isBold;
        }

        @Override
        public String toString() {
            return String.format("'%s' @(%.0f,%.0f) %.0fpt %s", text, x, y, fontSize, isBold ? "bold" : "");
        }
    }

    /**
     * Represents a line of text — one or more TextChunks at the same Y level.
     */
    @Getter
    public static class TextLine {
        private final List<TextChunk> chunks;
        private final float y;
        private final float height;

        public TextLine(List<TextChunk> chunks) {
            this.chunks = chunks;
            this.y = chunks.stream().map(TextChunk::getY).reduce(Float.MAX_VALUE, Math::min);
            this.height = chunks.stream().map(TextChunk::getHeight).reduce(0f, Math::max);
        }

        public String getText() {
            return chunks.stream().map(TextChunk::getText).collect(Collectors.joining(" "));
        }

        public float getMaxFontSize() {
            return chunks.stream().map(TextChunk::getFontSize).reduce(0f, Math::max);
        }

        public boolean isBold() {
            return chunks.stream().anyMatch(TextChunk::isBold);
        }

        public float getLeftX() {
            return chunks.stream().map(TextChunk::getX).reduce(Float.MAX_VALUE, Math::min);
        }
    }

    /**
     * Represents a structured block of content within a PDF page.
     */
    public enum BlockType { PARAGRAPH, HEADING, TABLE }

    @Getter
    public static class ContentBlock {
        private final BlockType type;
        private final List<TextLine> lines;
        private final int headingLevel; // 1-3 for headings, 0 for non-headings

        public ContentBlock(BlockType type, List<TextLine> lines, int headingLevel) {
            this.type = type;
            this.lines = lines;
            this.headingLevel = headingLevel;
        }

        public String getText() {
            return lines.stream().map(TextLine::getText).collect(Collectors.joining("\n"));
        }

        public float getMaxFontSize() {
            return lines.stream().flatMap(l -> l.getChunks().stream())
                    .map(TextChunk::getFontSize).reduce(0f, Math::max);
        }

        public boolean isBold() {
            return lines.stream().anyMatch(TextLine::isBold);
        }
    }

    /**
     * Represents a full page's extracted content.
     */
    @Getter
    public static class PageContent {
        private final int pageNumber;
        private final List<ContentBlock> blocks;
        private final List<byte[]> images; // PNG bytes of embedded images

        public PageContent(int pageNumber, List<ContentBlock> blocks, List<byte[]> images) {
            this.pageNumber = pageNumber;
            this.blocks = blocks;
            this.images = images;
        }
    }

    // ======================== Public API ========================

    /**
     * Extract structured content from all pages of a PDF.
     *
     * @param document the loaded PDF document
     * @return list of PageContent, one per page
     * @throws IOException if extraction fails
     */
    public List<PageContent> extractContent(PDDocument document) throws IOException {
        List<PageContent> result = new ArrayList<>();
        int totalPages = document.getNumberOfPages();

        for (int i = 0; i < totalPages; i++) {
            PDPage page = document.getPage(i);
            log.info("Extracting page {}/{}", i + 1, totalPages);

            List<byte[]> images = extractImages(page);
            List<TextLine> lines = extractTextLines(document, i + 1);
            List<ContentBlock> blocks = groupIntoBlocks(lines);

            result.add(new PageContent(i + 1, blocks, images));
        }

        return result;
    }

    /**
     * Extract all text lines from a single PDF page with position and font info.
     */
    public List<TextLine> extractTextLines(PDDocument document, int pageNumber) throws IOException {
        List<TextChunk> allChunks = new ArrayList<>();

        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
                for (TextPosition tp : textPositions) {
                    String ch = tp.getUnicode();
                    if (ch == null || ch.trim().isEmpty()) continue;

                    String fontName = "default";
                    float fontSize = tp.getFontSizeInPt();
                    boolean isBold = false;
                    try {
                        if (tp.getFont() != null) {
                            fontName = tp.getFont().getName();
                            if (fontName != null) {
                                isBold = fontName.toLowerCase().contains("bold")
                                        || fontName.toLowerCase().contains("heavy")
                                        || fontName.toLowerCase().contains("black");
                            }
                        }
                    } catch (Exception ignored) {
                        // Some PDF fonts may throw on getName()
                    }

                    allChunks.add(new TextChunk(
                            ch,
                            tp.getX(),
                            tp.getY(),
                            tp.getWidth(),
                            tp.getHeight(),
                            fontSize,
                            fontName,
                            isBold
                    ));
                }
            }
        };

        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.getText(document);

        return groupIntoLines(allChunks);
    }

    // ======================== Image Extraction ========================

    /**
     * Extract embedded images from a PDF page's resources.
     */
    private List<byte[]> extractImages(PDPage page) {
        List<byte[]> images = new ArrayList<>();
        try {
            PDResources resources = page.getResources();
            if (resources == null) return images;

            for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
                try {
                    org.apache.pdfbox.pdmodel.graphics.PDXObject xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject) {
                        PDImageXObject img = (PDImageXObject) xobj;
                        BufferedImage bimg = img.getImage();
                        if (bimg != null) {
                            // Skip very small images (likely icons, decorations)
                            if (bimg.getWidth() < 50 || bimg.getHeight() < 50) continue;

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(bimg, "png", baos);
                            images.add(baos.toByteArray());
                            log.debug("  Extracted image: {}x{}", bimg.getWidth(), bimg.getHeight());
                        }
                    }
                } catch (Exception e) {
                    log.debug("  Skipped resource {}: {}", name.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract images from page: {}", e.getMessage());
        }
        log.info("  Extracted {} images", images.size());
        return images;
    }

    // ======================== Text Line Grouping ========================

    /**
     * Group text chunks into lines by Y-coordinate, then merge consecutive
     * characters within each line into word-level chunks.
     *
     * <p>PDFBox typically returns one chunk per CHARACTER. We must first group
     * by Y into lines, then merge adjacent characters (small X gaps) into
     * word-level chunks. Otherwise every character looks like a "column" and
     * everything gets misdetected as a table.</p>
     */
    private List<TextLine> groupIntoLines(List<TextChunk> chunks) {
        if (chunks.isEmpty()) return Collections.emptyList();

        // Sort by Y (top to bottom), then X (left to right)
        List<TextChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.<TextChunk>comparingDouble(c -> c.getY())
                .thenComparingDouble(c -> c.getX()));

        // Step 1: Group chunks by Y into raw lines, tolerating small Y variance
        List<List<TextChunk>> rawLines = new ArrayList<>();
        List<TextChunk> currentLine = new ArrayList<>();
        float currentY = sorted.get(0).getY();

        for (TextChunk chunk : sorted) {
            float avgHeight = currentLine.isEmpty() ? chunk.getHeight()
                    : (float) currentLine.stream().mapToDouble(TextChunk::getHeight).average().orElse(chunk.getHeight());
            float tolerance = Math.max(avgHeight * 0.6f, 2f);

            if (!currentLine.isEmpty() && Math.abs(chunk.getY() - currentY) > tolerance) {
                // New line detected — finish the current one
                rawLines.add(new ArrayList<>(currentLine));
                currentLine.clear();
            }
            currentLine.add(chunk);
            currentY = chunk.getY();
        }
        if (!currentLine.isEmpty()) {
            rawLines.add(new ArrayList<>(currentLine));
        }

        // Step 2: Within each raw line, merge character-chunks into word-chunks
        List<TextLine> lines = new ArrayList<>();
        for (List<TextChunk> rawLine : rawLines) {
            List<TextChunk> merged = mergeIntoWords(rawLine);
            if (!merged.isEmpty()) {
                lines.add(new TextLine(merged));
            }
        }

        return lines;
    }

    /**
     * Merge adjacent character-level chunks into word-level chunks.
     * Two characters are merged if the horizontal gap between them is ≤ 3pt
     * (normal character spacing). Larger gaps (inter-word, column gaps)
     * start a new word-chunk.
     */
    private List<TextChunk> mergeIntoWords(List<TextChunk> chars) {
        if (chars.isEmpty()) return Collections.emptyList();

        // Sort left-to-right
        chars.sort(Comparator.comparingDouble(TextChunk::getX));

        final float MERGE_GAP = 3.5f; // pt — gaps ≤ this are character spacing, not word spacing
        List<TextChunk> words = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        float wordX = chars.get(0).getX();
        float wordY = chars.get(0).getY();
        float wordH = chars.get(0).getHeight();
        float wordFontSize = chars.get(0).getFontSize();
        boolean wordBold = chars.get(0).isBold();
        String wordFont = chars.get(0).getFontName();
        TextChunk prev = null;

        for (TextChunk ch : chars) {
            if (prev != null) {
                float gap = ch.getX() - (prev.getX() + prev.getWidth());
                if (gap > MERGE_GAP) {
                    // Gap too large for char spacing → finish current word
                    if (buf.length() > 0) {
                        float wordW = prev.getX() + prev.getWidth() - wordX;
                        words.add(new TextChunk(buf.toString(), wordX, wordY,
                                Math.max(wordW, 1), wordH, wordFontSize, wordFont, wordBold));
                    }
                    buf = new StringBuilder();
                    wordX = ch.getX();
                    wordY = ch.getY();
                    wordH = ch.getHeight();
                    wordFontSize = ch.getFontSize();
                    wordBold = ch.isBold();
                    wordFont = ch.getFontName();
                }
            }
            buf.append(ch.getText());
            wordH = Math.max(wordH, ch.getHeight());
            wordFontSize = Math.max(wordFontSize, ch.getFontSize());
            wordBold = wordBold || ch.isBold();
            prev = ch;
        }

        // Flush last word
        if (buf.length() > 0 && prev != null) {
            float wordW = prev.getX() + prev.getWidth() - wordX;
            words.add(new TextChunk(buf.toString(), wordX, wordY,
                    Math.max(wordW, 1), wordH, wordFontSize, wordFont, wordBold));
        }

        return words;
    }

    // ======================== Block Grouping ========================

    /**
     * Group text lines into content blocks:
     * <ul>
     *   <li>Headings: lines with significantly larger font or bold</li>
     *   <li>Tables: consecutive lines with column-aligned text</li>
     *   <li>Paragraphs: everything else, grouped by line spacing</li>
     * </ul>
     */
    private List<ContentBlock> groupIntoBlocks(List<TextLine> lines) {
        if (lines.isEmpty()) return Collections.emptyList();

        // First pass: detect the body font size (the most common font size)
        float bodyFontSize = detectBodyFontSize(lines);

        List<ContentBlock> blocks = new ArrayList<>();
        List<TextLine> pendingLines = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            TextLine line = lines.get(i);
            String text = line.getText().trim();
            if (text.isEmpty()) {
                // Empty line = paragraph boundary
                flushPending(blocks, pendingLines);
                continue;
            }

            // Check if this line could be a heading
            boolean isHeadingLine = isHeading(line, bodyFontSize);

            // Check if this line and next few form a table
            int tableEnd = detectTableRange(lines, i);
            if (tableEnd > i && !isHeadingLine) {
                flushPending(blocks, pendingLines);

                List<TextLine> tableLines = new ArrayList<>(lines.subList(i, tableEnd));
                blocks.add(new ContentBlock(BlockType.TABLE, tableLines, 0));
                i = tableEnd - 1;
                continue;
            }

            if (isHeadingLine) {
                flushPending(blocks, pendingLines);
                List<TextLine> headingLine = Collections.singletonList(line);
                int level = line.getMaxFontSize() > bodyFontSize * 1.5f ? 1
                          : line.getMaxFontSize() > bodyFontSize * 1.2f ? 2 : 3;
                blocks.add(new ContentBlock(BlockType.HEADING, headingLine, level));
                continue;
            }

            pendingLines.add(line);
        }

        flushPending(blocks, pendingLines);
        return blocks;
    }

    /**
     * Detect the body font size as the most common font size across all lines.
     */
    private float detectBodyFontSize(List<TextLine> lines) {
        Map<Integer, Integer> sizeCount = new HashMap<>();
        for (TextLine line : lines) {
            int rounded = Math.round(line.getMaxFontSize());
            sizeCount.merge(rounded, 1, Integer::sum);
        }
        return sizeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> (float) e.getKey())
                .orElse(11f);
    }

    /**
     * Check if a line looks like a heading:
     * - Significantly larger font than body
     * - Or bold and slightly larger
     * - And not too long (headings are usually short)
     */
    private boolean isHeading(TextLine line, float bodyFontSize) {
        float fontSize = line.getMaxFontSize();
        String text = line.getText().trim();

        // Headings are typically short (1-60 chars)
        if (text.length() > 60) return false;

        // Must be at least 40% larger than body text, OR bold AND at least 25% larger
        // This prevents minor font size variations from being treated as headings
        if (fontSize >= bodyFontSize * 1.4f) return true;
        if (line.isBold() && fontSize >= bodyFontSize * 1.25f) return true;

        return false;
    }

    /**
     * Detect if lines starting at index form a table.
     *
     * <p>Key insight: in a table, consecutive chunks within a line have LARGE gaps
     * (column spacing, typically 30-80pt). In normal paragraphs, gaps between words
     * are small (2-12pt). We detect tables by finding lines with large inter-chunk
     * gaps that are consistent across consecutive lines.</p>
     *
     * <p>Requirements for a table (all must be met):
     * <ul>
     *   <li>At least 4 consecutive lines</li>
     *   <li>Each line must have at least one LARGE gap (&ge;25pt) between consecutive chunks</li>
     *   <li>Each line must have 2-10 chunks (not too many, not too few)</li>
     *   <li>The gap positions must be consistent across &ge;70% of lines</li>
     * </ul>
     */
    private int detectTableRange(List<TextLine> lines, int startIdx) {
        if (startIdx >= lines.size() - 3) return startIdx;

        final float MIN_GAP_PT = 25f;   // Minimum gap between table columns (points)
        final float GAP_TOLERANCE = 15f; // Tolerance for gap position alignment
        final int MIN_TABLE_ROWS = 4;    // Minimum consecutive rows to be a table
        final int MAX_CHUNKS_PER_ROW = 10; // A table row shouldn't have too many "columns"

        List<List<Float>> lineGaps = new ArrayList<>();
        int endIdx = startIdx;
        int maxConsecutive = 0;
        int currentConsecutive = 0;

        for (int i = startIdx; i < Math.min(lines.size(), startIdx + 50); i++) {
            TextLine line = lines.get(i);
            String text = line.getText().trim();
            if (text.isEmpty()) {
                if (currentConsecutive >= MIN_TABLE_ROWS) break;
                lineGaps.clear();
                currentConsecutive = 0;
                continue;
            }

            // Sort chunks left-to-right
            List<TextChunk> sorted = new ArrayList<>(line.getChunks());
            sorted.sort(Comparator.comparingDouble(TextChunk::getX));

            // Skip lines with too many chunks (they're paragraphs, not tables)
            if (sorted.size() > MAX_CHUNKS_PER_ROW) {
                if (currentConsecutive >= MIN_TABLE_ROWS) break;
                lineGaps.clear();
                currentConsecutive = 0;
                continue;
            }

            // Find large gaps between consecutive chunks
            List<Float> gaps = new ArrayList<>();
            for (int c = 1; c < sorted.size(); c++) {
                float prevEnd = sorted.get(c - 1).getX() + sorted.get(c - 1).getWidth();
                float gap = sorted.get(c).getX() - prevEnd;
                if (gap >= MIN_GAP_PT) {
                    float boundary = prevEnd + gap / 2f;
                    gaps.add(boundary);
                }
            }

            if (!gaps.isEmpty()) {
                lineGaps.add(gaps);
                currentConsecutive++;
                if (currentConsecutive > maxConsecutive) {
                    maxConsecutive = currentConsecutive;
                    endIdx = i + 1;
                }
            } else {
                if (currentConsecutive >= MIN_TABLE_ROWS) break;
                lineGaps.clear();
                currentConsecutive = 0;
            }
        }

        if (maxConsecutive < MIN_TABLE_ROWS) return startIdx;
        if (!hasConsistentGaps(lineGaps, GAP_TOLERANCE)) return startIdx;

        return endIdx;
    }

    /**
     * Check if gap positions (column boundaries) are consistent across table rows.
     * We cluster the gap positions across all lines and require at least one
     * cluster that appears in most lines.
     */
    private boolean hasConsistentGaps(List<List<Float>> lineGaps, float tolerance) {
        if (lineGaps.size() < 3) return false;

        // Collect all gap positions
        List<Float> allGaps = new ArrayList<>();
        for (List<Float> gaps : lineGaps) {
            allGaps.addAll(gaps);
        }

        // Cluster gap positions
        List<Float> sorted = new ArrayList<>(allGaps);
        Collections.sort(sorted);

        List<List<Float>> clusters = new ArrayList<>();
        List<Float> current = new ArrayList<>();

        for (float g : sorted) {
            if (current.isEmpty() || g - current.get(0) <= tolerance) {
                current.add(g);
            } else {
                clusters.add(new ArrayList<>(current));
                current.clear();
                current.add(g);
            }
        }
        if (!current.isEmpty()) clusters.add(new ArrayList<>(current));

        // Each cluster's center is the average
        List<Float> centers = clusters.stream()
                .map(c -> (float) c.stream().mapToDouble(v -> v).average().orElse(0))
                .collect(Collectors.toList());

        // A valid table: at least one gap center appears in ≥70% of lines
        int minLines = Math.max(3, (int) (lineGaps.size() * 0.7));
        for (float center : centers) {
            int matchCount = 0;
            for (List<Float> gaps : lineGaps) {
                boolean hasMatch = gaps.stream().anyMatch(g -> Math.abs(g - center) <= tolerance);
                if (hasMatch) matchCount++;
            }
            if (matchCount >= minLines) return true;
        }

        return false;
    }

    /**
     * Flush pending text lines into a paragraph block.
     */
    private void flushPending(List<ContentBlock> blocks, List<TextLine> pendingLines) {
        if (!pendingLines.isEmpty()) {
            blocks.add(new ContentBlock(BlockType.PARAGRAPH, new ArrayList<>(pendingLines), 0));
            pendingLines.clear();
        }
    }

    // ======================== Convenience Methods ========================

    /**
     * Extract text from a PDF as structured page content.
     * Convenience method for single-call extraction.
     */
    public static List<PageContent> extract(PDDocument document) throws IOException {
        return new PdfContentExtractor().extractContent(document);
    }
}
