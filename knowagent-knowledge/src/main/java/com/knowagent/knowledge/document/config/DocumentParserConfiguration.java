package com.knowagent.knowledge.document.config;

import com.knowagent.knowledge.document.ParseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the bounded-parse configuration for the local document parsers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ParseProperties.class)
public class DocumentParserConfiguration {
}
