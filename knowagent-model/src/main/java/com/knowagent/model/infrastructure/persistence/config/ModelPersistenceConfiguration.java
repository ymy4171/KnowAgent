package com.knowagent.model.infrastructure.persistence.config;

import com.knowagent.model.infrastructure.persistence.mapper.ModelProviderMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Scans the model module's MyBatis-Plus mappers.
 *
 * <p>This module deliberately does <em>not</em> define its own
 * {@code MybatisPlusInterceptor}: the tenant-line and optimistic-lock interceptors are
 * the single global bean from {@code knowagent-security}, applied to the shared
 * {@code SqlSessionFactory}. Model-provider statements therefore get tenant isolation
 * and optimistic locking for free, and their custom SQL additionally carries an
 * explicit {@code tenant_id}.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = ModelProviderMapper.class, annotationClass = Mapper.class)
public class ModelPersistenceConfiguration {
}
