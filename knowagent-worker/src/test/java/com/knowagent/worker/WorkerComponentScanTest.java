package com.knowagent.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerComponentScanTest {

    @Test
    void workerDoesNotScanApiOrSecurityApplicationServices() {
        SpringBootApplication application = KnowAgentWorkerApplication.class
                .getAnnotation(SpringBootApplication.class);

        assertThat(Arrays.asList(application.scanBasePackages()))
                .contains("com.knowagent.worker", "com.knowagent.model", "com.knowagent.knowledge")
                .doesNotContain("com.knowagent", "com.knowagent.api", "com.knowagent.security");
        assertThat(KnowAgentWorkerApplication.class.getAnnotation(Import.class)).isNotNull();
    }
}
