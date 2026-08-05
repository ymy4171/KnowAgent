package com.knowagent.workspace.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record VirtualPath(String value) {

    public VirtualPath {
        Objects.requireNonNull(value, "value must not be null");
        value = normalize(value);
    }

    private static String normalize(String input) {
        String candidate = input.replace('\\', '/').trim();
        if (candidate.isEmpty() || candidate.startsWith("/") || candidate.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("path must be relative");
        }

        List<String> normalized = new ArrayList<>();
        for (String segment : candidate.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("parent traversal is not allowed");
            }
            normalized.add(segment);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("path must contain a file or directory name");
        }
        return String.join("/", normalized);
    }
}

