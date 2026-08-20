package com.knowagent.worker;

import com.knowagent.security.infrastructure.persistence.config.SecurityPersistenceConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = {
        "com.knowagent.worker",
        "com.knowagent.model",
        "com.knowagent.knowledge",
        "com.knowagent.workspace",
        "com.knowagent.observability"
})
@Import(SecurityPersistenceConfiguration.class)
public class KnowAgentWorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(KnowAgentWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
