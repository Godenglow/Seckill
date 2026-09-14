package com.shallowan.seckill.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Druid 连接池配置。
 * <p>
 * 为什么要手写这个 Bean：Spring Boot 1.5 的 {@code DataSourceProperties} 只会绑定
 * url / driver-class-name / username / password 等少数字段，像
 * {@code spring.datasource.max-active} 这类 Druid 专有参数不会被绑定到连接池上，
 * 属于"配了但不生效"。这里把 DruidDataSource 直接交给
 * {@code @ConfigurationProperties(prefix = "spring.datasource")} 绑定，
 * 那些调优参数才真正落到连接池。
 * <p>
 * 注意：因为显式声明了 DataSource Bean，Spring Boot 的 DataSourceAutoConfiguration
 * 会自动退让，不会与这里的配置冲突。
 *
 * @author ShallowAn
 */
@Configuration
public class DataSourceConfig {

    @Bean(initMethod = "init", destroyMethod = "close")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DruidDataSource dataSource() {
        return new DruidDataSource();
    }
}
