package com.transformpdf.service;

import com.transformpdf.entity.ConversionTask;

import java.io.IOException;
import java.util.List;

public interface ConversionService {

    /**
     * Convert image to PDF
     */
    String convertImageToPdf(ConversionTask task) throws IOException;

    /**
     * Merge multiple images into a single PDF
     */
    String mergeImagesToPdf(List<ConversionTask> tasks, String outputName) throws IOException;

    /**
     * Convert image to Word document
     */
    String convertImageToWord(ConversionTask task) throws IOException;

    /**
     * Convert PDF to Word document
     */
    String convertPdfToWord(ConversionTask task) throws IOException;

    /**
     * Apply scanning effect to image and save as image
     */
    String scanToImage(ConversionTask task) throws IOException;

    /**
     * Apply scanning effect with user-specified corners and save as image
     */
    String scanToImage(ConversionTask task, int[][] corners) throws IOException;

    /**
     * Apply scanning effect to image and save as PDF
     */
    String scanToPdf(ConversionTask task) throws IOException;

    /**
     * Apply scanning effect with user-specified corners and save as PDF
     */
    String scanToPdf(ConversionTask task, int[][] corners) throws IOException;

    /**
     * Apply scanning effect to image and save as Word
     */
    String scanToWord(ConversionTask task) throws IOException;

    /**
     * Apply scanning effect with user-specified corners and save as Word
     */
    String scanToWord(ConversionTask task, int[][] corners) throws IOException;
}
