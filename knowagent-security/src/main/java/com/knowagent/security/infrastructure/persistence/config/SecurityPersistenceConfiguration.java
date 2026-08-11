package com.knowagent.security.infrastructure.persistence.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.knowagent.security.infrastructure.persistence.mapper.TenantMapper;
import com.knowagent.security.infrastructure.persistence.typehandler.PostgresUuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = TenantMapper.class, annotationClass = Mapper.class)
public class SecurityPersistenceConfiguration {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public ConfigurationCustomizer securityTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(PostgresUuidTypeHandler.class);
    }
}
