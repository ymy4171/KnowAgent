package com.knowagent.knowledge.infrastructure.persistence.config;

import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeModelProviderReferenceMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** Scans persistence owned by the knowledge module. */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = KnowledgeModelProviderReferenceMapper.class, annotationClass = Mapper.class)
public class KnowledgePersistenceConfiguration {
}
