package com.knowagent.knowledge.application.service;

import com.knowagent.knowledge.file.DocumentType;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides the document type of a spooled upload from its content, never from the
 * client filename or declared {@code Content-Type} (rule: do not trust the header).
 */
public interface DocumentTypeDetector {

    /** Empty when the content is unknown or not a supported document type. */
    Optional<DocumentType> detect(Path spooledFile);
}
