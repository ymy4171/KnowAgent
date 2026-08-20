package com.knowagent.knowledge.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small in-memory fixtures for the parser tests: real PDFs and DOCX files are produced
 * with the same libraries the parsers consume (PDFBox, POI), so the parser under test
 * reads genuine files rather than hand-built stubs. The PDF font constant must be
 * {@code Standard14Fonts.FontName.HELVETICA} - PDFBox 3.x removed the legacy
 * {@code PDType1Font.HELVETICA} field.
 */
final class TestDocuments {

    private TestDocuments() {
    }

    /** One page per entry, each page drawn as its text lines in reading order. */
    static byte[] pdf(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    for (String line : pageText.split("\\R")) {
                        stream.showText(line);
                        stream.newLineAtOffset(0, -16);
                    }
                    stream.endText();
                }
            }
            return save(document);
        }
    }

    /** A PDF with the given core title and one page of content. */
    static byte[] pdfWithTitle(String title, String pageText) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle(title);
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(pageText);
                stream.endText();
            }
            return save(document);
        }
    }

    /** A one-page PDF with no text operators - a scanned/blank document. */
    static byte[] blankPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            return save(document);
        }
    }

    /** Builds a DOCX by mutating the fresh document with the given body. */
    static byte[] docx(Consumer<XWPFDocument> body) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            body.accept(document);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                document.write(out);
                return out.toByteArray();
            }
        }
    }

    /** A heading paragraph carrying the numbered {@code Heading n} style. */
    static void heading(XWPFDocument document, int level, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        paragraph.createRun().setText(text);
    }

    /** A heading whose style id is opaque and whose localized display name carries the level. */
    static void localizedHeading(XWPFDocument document, int level, String text) {
        String styleId = "local-h" + level;
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            styles = document.createStyles();
        }
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId(styleId);
        style.addNewName().setVal("\u6807\u9898 " + level);
        styles.addStyle(new XWPFStyle(style));

        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(styleId);
        paragraph.createRun().setText(text);
    }

    /** A zip fixture with known central-directory sizes for cumulative expansion tests. */
    static byte[] zipEntries(int count, int bytesPerEntry) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] content = new byte[bytesPerEntry];
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i + ".bin"));
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        }
    }

    static void paragraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.createRun().setText(text);
    }

    /** A table whose rows and cells appear in reading order in the document text. */
    static void table(XWPFDocument document, String[][] rows) {
        XWPFTable table = document.createTable();
        for (int r = 0; r < rows.length; r++) {
            XWPFTableRow row = r == 0 ? table.getRow(0) : table.createRow();
            for (int c = 0; c < rows[r].length; c++) {
                XWPFTableCell cell = (r == 0 && c == 0 && row.getCell(0) != null)
                        ? row.getCell(0)
                        : row.createCell();
                cell.setText(rows[r][c]);
            }
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.save(out);
            return out.toByteArray();
        }
    }
}
