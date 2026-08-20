package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PDF parser backed by Apache PDFBox. Each page becomes one section carrying its
 * 1-based page number and exact character offsets; text is extracted in reading order
 * ({@code setSortByPosition}). Limits are enforced up front (page count) and during
 * extraction (character budget, cooperative timeout). A PDF that loads but has no
 * extractable text is reported as {@link ErrorCode#OCR_REQUIRED} - a scanned document
 * that needs the external OCR service - never as fabricated text.
 */
@Service
public class PdfParser implements DocumentParser {

    private static final Set<String> MIME_TYPES = Set.of("application/pdf");

    private final ParseProperties properties;

    public PdfParser(ParseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Set<String> supportedMimeTypes() {
        return MIME_TYPES;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        ParseBudget budget = new ParseBudget(properties);
        try (SourceSpool.SpooledFile spooled = SourceSpool.spool(source, properties.maxBytes())) {
            budget.checkTime();
            return parsePdf(spooled.path(), budget);
        }
    }

    private ParsedDocument parsePdf(java.nio.file.Path path, ParseBudget budget) {
        PDDocument document;
        try {
            document = Loader.loadPDF(path.toFile());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.CORRUPT_DOCUMENT,
                    "The PDF document is corrupted or unreadable.");
        }
        try {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new BusinessException(ErrorCode.EMPTY_DOCUMENT, "The PDF contains no pages.");
            }
            if (pageCount > properties.maxPages()) {
                throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                        "The PDF has more pages than the maximum allowed.");
            }
            String title = document.getDocumentInformation().getTitle();
            if (title != null && title.isBlank()) {
                title = null;
            }

            List<ParsedSection> sections = new ArrayList<>(pageCount);
            StringBuilder text = new StringBuilder();
            long offset = 0;
            for (int page = 1; page <= pageCount; page++) {
                budget.checkTime();
                String pageText = extractPageText(document, page);
                if ((long) text.length() + pageText.length() > properties.maxCharacters()) {
                    throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                            "The document text exceeds the maximum allowed size.");
                }
                text.append(pageText);
                sections.add(new ParsedSection(null, null, pageText, page, offset,
                        offset + pageText.length(), Map.of()));
                offset += pageText.length();
            }
            if (text.toString().isBlank()) {
                throw new BusinessException(ErrorCode.OCR_REQUIRED,
                        "The PDF has no extractable text; an OCR service is required to read it.");
            }
            return new ParsedDocument(title, text.toString(), pageCount, sections);
        } catch (BusinessException failure) {
            throw failure;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.CORRUPT_DOCUMENT,
                    "The PDF document is corrupted or unreadable.");
        } finally {
            try {
                document.close();
            } catch (IOException ignored) {
                // best effort close
            }
        }
    }

    private static String extractPageText(PDDocument document, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }
}
