package com.knowagent.knowledge.document;

import java.util.Set;

/**
 * A local document parser that turns a controlled {@link ParseSource} into a
 * {@link ParsedDocument}. Each implementation declares the canonical MIME types it
 * handles (lowercase, no parameters); the {@link ParserRegistry} uses those to select
 * the single parser for a document, so selection never reads the content stream.
 *
 * <p>Implementations must be stateless (or keep only immutable configuration), close
 * the source content stream on every path, respect the configured parse limits, and
 * throw {@link com.knowagent.common.error.BusinessException} with a stable
 * {@link com.knowagent.common.error.ErrorCode} - never leaking content, object keys,
 * file paths or third-party stack traces in messages.
 */
public interface DocumentParser {

    /** The canonical MIME types this parser handles (lowercase, no {@code ;} parameters). */
    Set<String> supportedMimeTypes();

    ParsedDocument parse(ParseSource source);
}
