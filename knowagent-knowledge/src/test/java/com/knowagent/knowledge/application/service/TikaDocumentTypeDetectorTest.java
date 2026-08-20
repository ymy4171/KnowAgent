package com.knowagent.knowledge.application.service;

import com.knowagent.knowledge.file.DocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the content-based (not filename/header) document type decision: plain text,
 * markdown, PDF and a real OOXML DOCX container are detected by their bytes, while
 * opaque binary content is rejected.
 */
class TikaDocumentTypeDetectorTest {

    private final DocumentTypeDetector detector = new TikaDocumentTypeDetector();

    @TempDir
    Path tempDir;

    @Test
    void detectsPlainText() throws IOException {
        assertThat(detect("notes.txt", "just some plain text\nsecond line\n"))
                .contains(DocumentType.TEXT_PLAIN);
    }

    @Test
    void detectsMarkdownEvenWithATxtExtension() throws IOException {
        // The content (heading) decides, not the .txt filename.
        assertThat(detect("readme.txt", "# Project\n\nBody paragraph\n"))
                .contains(DocumentType.TEXT_MARKDOWN);
    }

    @Test
    void detectsPdfByMagicBytes() throws IOException {
        byte[] pdf = "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(detect("renamed.txt", pdf)).contains(DocumentType.PDF);
    }

    @Test
    void detectsDocxContainer() throws IOException {
        Path docx = writeDocx();
        assertThat(detector.detect(docx)).contains(DocumentType.DOCX);
    }

    @Test
    void rejectsOpaqueBinaryContent() throws IOException {
        byte[] random = new byte[512];
        java.security.SecureRandom randomSource = new java.security.SecureRandom();
        randomSource.nextBytes(random);
        assertThat(detect("data.bin", random)).isEmpty();
    }

    @Test
    void fromCanonicalMimeNormalizesCaseAndParameters() {
        assertThat(DocumentType.fromCanonicalMime("application/pdf")).contains(DocumentType.PDF);
        assertThat(DocumentType.fromCanonicalMime("APPLICATION/PDF")).contains(DocumentType.PDF);
        assertThat(DocumentType.fromCanonicalMime("text/plain; charset=utf-8")).contains(DocumentType.TEXT_PLAIN);
        assertThat(DocumentType.fromCanonicalMime("application/octet-stream")).isEmpty();
        assertThat(DocumentType.fromCanonicalMime("text/html")).isEmpty();
        assertThat(DocumentType.fromCanonicalMime(null)).isEmpty();
    }

    private java.util.Optional<DocumentType> detect(String name, String content) throws IOException {
        return detect(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private java.util.Optional<DocumentType> detect(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return detector.detect(file);
    }

    private Path writeDocx() throws IOException {
        Path docx = tempDir.resolve("sample.docx");
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Override PartName="/word/document.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        try (OutputStream out = Files.newOutputStream(docx);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(contentTypes.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(document.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return docx;
    }
}
