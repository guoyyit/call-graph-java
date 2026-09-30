package com.github.guoyyit.callgraphjava.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 服务装配：仓库根目录与数据目录来自配置
 * （{@code callgraph.repo} / {@code callgraph.data}），
 * Bean 创建后自动执行崩溃恢复。
 */
@Configuration
public class ServiceConfig {

    @Value("${callgraph.repo:.}")
    private String repoRoot;

    @Value("${callgraph.data:./.callgraph-data}")
    private String dataDir;

    private DefaultCallGraphService service;

    @Bean
    public CallGraphService callGraphService() {
        service = new DefaultCallGraphService(Path.of(repoRoot).toAbsolutePath().normalize(),
                Path.of(dataDir).toAbsolutePath().normalize());
        service.recover();
        return service;
    }

    @PreDestroy
    public void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }
}
