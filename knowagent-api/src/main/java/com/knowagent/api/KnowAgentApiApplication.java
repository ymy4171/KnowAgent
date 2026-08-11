package com.knowagent.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.knowagent")
public class KnowAgentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowAgentApiApplication.class, args);
    }
}
