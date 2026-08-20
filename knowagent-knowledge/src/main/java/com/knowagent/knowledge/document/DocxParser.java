package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX parser backed by Apache POI (XWPF). The package is opened from the bounded temp
 * file, so POI's {@link ZipSecureFile} guards decompressed entry sizes against zip
 * bombs (the static limit is re-applied to the configured value on every parse - it is
 * constant in a running service, so re-writing it is idempotent and safe). Paragraphs
 * whose style is a heading (English {@code Heading n} or Chinese {@code 标题 n}, by
 * style name or id) open numbered sections; tables contribute their text in reading
 * order. The document title is taken from the OOXML core properties when present.
 */
@Service
public class DocxParser implements DocumentParser {

    private static final Set<String> MIME_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final Pattern HEADING_STYLE = Pattern.compile(
            "(?i)(heading|\\u6807\\u9898)\\s*(\\d{1,2})");

    private final ParseProperties properties;

    public DocxParser(ParseProperties properties) {
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
            return parseDocx(spooled.path(), budget);
        }
    }

    private ParsedDocument parseDocx(java.nio.file.Path path, ParseBudget budget) {
        // Bound the uncompressed zip entry size (zip-bomb defense). Constant in a running
        // service, so this static POI setting is re-applied idempotently on every parse.
        ZipSecureFile.setMaxEntrySize(properties.maxUncompressedBytes());
        OPCPackage pkg;
        try {
            // Pre-flight over the central directory classifies oversized entries as
            // DOCUMENT_TOO_LARGE (not "corrupted"); POI's threshold stream remains the
            // backstop for entries whose size is not recorded there.
            guardZipBomb(path);
            pkg = OPCPackage.open(path.toFile());
        } catch (BusinessException failure) {
            throw failure;
        } catch (InvalidFormatException | IOException exception) {
            throw new BusinessException(ErrorCode.CORRUPT_DOCUMENT,
                    "The DOCX document is corrupted or unreadable.");
        }
        try (OPCPackage packageToClose = pkg;
             XWPFDocument document = new XWPFDocument(packageToClose)) {
            budget.checkTime();
            String title = document.getProperties().getCoreProperties().getTitle();
            if (title != null && title.isBlank()) {
                title = null;
            }

            SectionBuilder builder = new SectionBuilder(properties.maxCharacters(), budget);
            for (IBodyElement element : document.getBodyElements()) {
                budget.checkTime();
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(builder, paragraph, document.getStyles());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>(row.getTableCells().size());
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText());
                        }
                        appendLine(builder, String.join("\t", cells), null);
                    }
                }
            }
            if (builder.textLength() == 0) {
                throw new BusinessException(ErrorCode.EMPTY_DOCUMENT, "The document contains no text.");
            }
            budget.checkTime();
            return builder.finish(title, 0);
        } catch (BusinessException failure) {
            throw failure;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.CORRUPT_DOCUMENT,
                    "The DOCX document is corrupted or unreadable.");
        }
    }

    private static void appendParagraph(SectionBuilder builder, XWPFParagraph paragraph,
                                        XWPFStyles styles) {
        Integer level = headingLevelOf(paragraph, styles);
        appendLine(builder, paragraph.getText(), level);
    }

    private static void appendLine(SectionBuilder builder, String line, Integer level) {
        if (line == null || line.isBlank()) {
            return;
        }
        builder.appendLine(line, level, level == null ? null : line.trim());
    }

    private void guardZipBomb(java.nio.file.Path path) throws IOException {
        try (ZipFile zip = new ZipSecureFile(path.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            long totalUncompressedBytes = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                long entrySize = entry.getSize();
                if (entrySize < 0) {
                    continue;
                }
                if (entrySize > properties.maxUncompressedBytes()
                        || totalUncompressedBytes > properties.maxUncompressedBytes() - entrySize) {
                    throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                            "The DOCX document expands beyond the maximum allowed size.");
                }
                totalUncompressedBytes += entrySize;
            }
        }
    }

    private static Integer headingLevelOf(XWPFParagraph paragraph, XWPFStyles styles) {
        String styleId = paragraph.getStyleID();
        Integer fromId = matchHeading(styleId);
        if (fromId != null) {
            return fromId;
        }
        XWPFStyle style = styles == null || styleId == null ? null : styles.getStyle(styleId);
        return style == null ? null : matchHeading(style.getName());
    }

    private static Integer matchHeading(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = HEADING_STYLE.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(2)) : null;
    }
}
