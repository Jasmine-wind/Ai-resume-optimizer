package com.winter.airesumeoptimizer.module.resume.service.impl;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.storage.FileStorageException;
import com.winter.airesumeoptimizer.infra.storage.FileStorageService;
import com.winter.airesumeoptimizer.module.resume.service.ResumeTextExtractionService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ResumeTextExtractionServiceImpl implements ResumeTextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeTextExtractionServiceImpl.class);

    private final FileStorageService fileStorageService;
    private final ResumePdfTextCandidateSelector pdfCandidateSelector;

    public ResumeTextExtractionServiceImpl(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.pdfCandidateSelector = new ResumePdfTextCandidateSelector();
    }

    @Override
    public String extractText(String objectKey, String fileType) {
        String normalizedFileType = normalizeFileType(fileType);

        try (InputStream inputStream = fileStorageService.loadAsStream(objectKey)) {
            return switch (normalizedFileType) {
                case "PDF" -> extractPdfText(inputStream);
                case "DOC" -> extractDocText(inputStream);
                case "DOCX" -> extractDocxText(inputStream);
                default -> throw new BusinessException(400, "不支持的简历文件类型");
            };
        } catch (FileStorageException exception) {
            throw new BusinessException(500, "简历文件读取失败");
        } catch (IOException exception) {
            throw new BusinessException(500, "简历文本提取失败");
        }
    }

    private String extractPdfText(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String legacyText = null;
            String positionSortedText = null;
            IOException legacyFailure = null;
            IOException positionFailure = null;
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(false);
                legacyText = normalizeExtractedText(stripper.getText(document));
            } catch (IOException exception) {
                legacyFailure = exception;
            }
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                positionSortedText = normalizeExtractedText(stripper.getText(document));
            } catch (IOException exception) {
                positionFailure = exception;
            }
            if (legacyText == null && positionSortedText == null) {
                throw legacyFailure != null ? legacyFailure : positionFailure;
            }

            ResumePdfTextCandidateSelector.Selection selection = pdfCandidateSelector.select(
                    legacyText, positionSortedText);
            log.info(
                    "PDF extraction candidate selected: legacyScore={}, positionScore={}, selected={}, "
                            + "legacyLineCount={}, positionLineCount={}, legacyLength={}, positionLength={}",
                    selection.legacyScore(),
                    selection.positionScore(),
                    selection.candidateType(),
                    lineCount(legacyText),
                    lineCount(positionSortedText),
                    textLength(legacyText),
                    textLength(positionSortedText));
            return selection.text();
        }
    }

    private int lineCount(String text) {
        return text == null || text.isBlank() ? 0 : (int) text.lines().count();
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
    }

    private String extractDocText(InputStream inputStream) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream);
                WordExtractor extractor = new WordExtractor(document)) {
            return normalizeExtractedText(extractor.getText());
        }
    }

    private String extractDocxText(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream);
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            List<ExtractedTextBlock> textBlocks = collectDocxTextBlocks(document);
            if (textBlocks.isEmpty()) {
                return normalizeExtractedText(extractor.getText());
            }
            return normalizeExtractedText(textBlocks);
        }
    }

    List<ExtractedTextBlock> collectDocxTextBlocks(XWPFDocument document) {
        List<ExtractedTextBlock> blocks = new ArrayList<>();
        for (IBodyElement bodyElement : document.getBodyElements()) {
            collectBodyElementTextBlocks(bodyElement, blocks);
        }
        for (XWPFHeader header : document.getHeaderList()) {
            collectHeaderFooterTextBlocks(header, "header", blocks);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            collectHeaderFooterTextBlocks(footer, "footer", blocks);
        }
        extractDocxTextBoxText(document).forEach(text -> addTextBlock(blocks, "textbox", text));
        return blocks;
    }

    private void collectBodyElementTextBlocks(IBodyElement bodyElement, List<ExtractedTextBlock> blocks) {
        if (bodyElement instanceof XWPFParagraph paragraph) {
            addTextBlock(blocks, "paragraph", paragraph.getText());
        } else if (bodyElement instanceof XWPFTable table) {
            collectTableTextBlocks(table, blocks);
        }
    }

    private void collectTableTextBlocks(XWPFTable table, List<ExtractedTextBlock> blocks) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (IBodyElement bodyElement : cell.getBodyElements()) {
                    if (bodyElement instanceof XWPFParagraph paragraph) {
                        addTextBlock(blocks, "table", paragraph.getText());
                    } else if (bodyElement instanceof XWPFTable nestedTable) {
                        collectTableTextBlocks(nestedTable, blocks);
                    }
                }
            }
        }
    }

    private void collectHeaderFooterTextBlocks(XWPFHeaderFooter headerFooter, String sourceType, List<ExtractedTextBlock> blocks) {
        for (XWPFParagraph paragraph : headerFooter.getParagraphs()) {
            addTextBlock(blocks, sourceType, paragraph.getText());
        }
        for (XWPFTable table : headerFooter.getTables()) {
            collectTableTextBlocks(table, blocks);
        }
    }

    private void addTextBlock(List<ExtractedTextBlock> blocks, String sourceType, String text) {
        if (text != null && !text.isBlank()) {
            blocks.add(new ExtractedTextBlock(sourceType, text.strip()));
        }
    }

    List<String> extractDocxTextBoxText(XWPFDocument document) {
        XmlObject[] textObjects = document.getDocument().selectPath("""
                declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
                .//w:txbxContent//w:t
                """);
        List<String> result = new ArrayList<>();
        for (XmlObject textObject : textObjects) {
            try (XmlCursor cursor = textObject.newCursor()) {
                String text = cursor.getTextValue();
                if (text != null && !text.isBlank()) {
                    result.add(text.strip());
                }
            }
        }
        return result;
    }

    private String normalizeFileType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            throw new BusinessException(400, "简历文件类型不能为空");
        }
        return fileType.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 只合并相邻的完全重复行；不能按全文去重，因为同一句经历可能合法地出现在不同章节。
     * DOCX 文本框中的重复抽取通常相邻，足以处理该噪声，同时保留跨章节事实。
     */
    private String normalizeExtractedText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        String previousKey = null;
        for (String rawLine : text.lines().toList()) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                continue;
            }
            String key = line.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!key.equals(previousKey)) {
                lines.add(line);
            }
            previousKey = key;
        }
        return String.join("\n", lines).strip();
    }

    private String normalizeExtractedText(List<ExtractedTextBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        Set<String> explicitTextBoxLines = new LinkedHashSet<>();
        for (ExtractedTextBlock block : blocks) {
            if (block != null && "textbox".equals(block.sourceType()) && block.text() != null) {
                block.text().lines()
                        .map(String::strip)
                        .filter(line -> !line.isBlank())
                        .map(line -> line.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                        .forEach(explicitTextBoxLines::add);
            }
        }
        String previousKey = null;
        String previousSourceType = null;
        for (ExtractedTextBlock block : blocks) {
            if (block == null || block.text() == null || block.text().isBlank()) {
                continue;
            }
            for (String rawLine : block.text().lines().toList()) {
                String line = rawLine.strip();
                if (line.isBlank()) {
                    continue;
                }
                String key = line.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                // XWPF 可能同时把文本框作为段落和 textbox 返回；显式 textbox 是更准确的来源。
                if (!"textbox".equals(block.sourceType()) && explicitTextBoxLines.contains(key)) {
                    continue;
                }
                // 仅去除同一来源类型的相邻重复（尤其是重复文本框）；段落/表格内容不做全局去重。
                if (!key.equals(previousKey) || !java.util.Objects.equals(block.sourceType(), previousSourceType)) {
                    lines.add(line);
                }
                previousKey = key;
                previousSourceType = block.sourceType();
            }
        }
        return String.join("\n", lines).strip();
    }

    record ExtractedTextBlock(String sourceType, String text) {
    }
}
