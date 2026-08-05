package com.knowagent.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(scanBasePackages = "com.knowagent")
@MapperScan("com.knowagent")
public class KnowAgentWorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(KnowAgentWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}

