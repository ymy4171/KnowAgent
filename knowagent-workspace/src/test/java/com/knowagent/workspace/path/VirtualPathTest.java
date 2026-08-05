package com.knowagent.workspace.path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class VirtualPathTest {

    @Test
    void normalizesPortableRelativePaths() {
        assertThat(new VirtualPath("reports\\2026/./summary.md").value())
                .isEqualTo("reports/2026/summary.md");
    }

    @Test
    void rejectsParentTraversalAndAbsolutePaths() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VirtualPath("../secret.txt"));
        assertThatIllegalArgumentException().isThrownBy(() -> new VirtualPath("/etc/passwd"));
        assertThatIllegalArgumentException().isThrownBy(() -> new VirtualPath("C:\\temp\\secret.txt"));
    }
}

