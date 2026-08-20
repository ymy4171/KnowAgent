package com.knowagent.observability.infrastructure.persistence.config;

import com.knowagent.observability.infrastructure.persistence.mapper.TaskMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Scans the observability module's MyBatis-Plus mappers.
 *
 * <p>This module deliberately does <em>not</em> define its own
 * {@code MybatisPlusInterceptor}: the tenant-line and optimistic-lock interceptors
 * are the single global bean from {@code knowagent-security}, applied to the shared
 * {@code SqlSessionFactory}. Observability's custom SQL bypasses the tenant line
 * with {@code @InterceptorIgnore} because the same statements serve authenticated
 * requests and worker execution (no {@code TenantContext}); every bypassed
 * statement carries an explicit {@code tenant_id}, locked by
 * {@code ObservabilityMapperSqlContractTest}.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = TaskMapper.class, annotationClass = Mapper.class)
public class ObservabilityPersistenceConfiguration {
}
