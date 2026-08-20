package com.knowagent.api.knowledgebase;

import com.knowagent.api.knowledgebase.dto.KnowledgeRetrievalRequest;
import com.knowagent.api.knowledgebase.dto.KnowledgeCitationResponse;
import com.knowagent.security.domain.role.SecurityPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrievalApiContractTest {

    @Test
    void endpointUsesDedicatedRetrievePermissionGrantedToAdmin() throws Exception {
        Method method = Arrays.stream(KnowledgeRetrievalController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("retrieve"))
                .findFirst()
                .orElseThrow();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('KNOWLEDGE_RETRIEVE')");
        assertThat(SecurityPermissions.ADMIN_ROLE_PERMISSIONS)
                .contains(SecurityPermissions.KNOWLEDGE_RETRIEVE);
    }

    @Test
    void requestHasNoTenantOverrideAndDoesNotPrintQuery() {
        assertThat(Arrays.stream(KnowledgeRetrievalRequest.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("query", "topK", "scoreThreshold", "fileIds")
                .doesNotContain("tenantId");

        String secretQuery = "private query text";
        String printed = new KnowledgeRetrievalRequest(secretQuery, 3, 0.5,
                List.of(UUID.randomUUID())).toString();
        assertThat(printed).doesNotContain(secretQuery);

        String privateContent = "private chunk content";
        String citationPrinted = new KnowledgeCitationResponse(UUID.randomUUID(), UUID.randomUUID(),
                "guide.pdf", privateContent, 2, List.of("1"), 0.9, 1).toString();
        assertThat(citationPrinted).doesNotContain(privateContent);
    }
}
