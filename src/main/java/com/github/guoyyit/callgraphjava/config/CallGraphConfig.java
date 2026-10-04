package com.github.guoyyit.callgraphjava.config;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.service.BuildManager;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.service.QueryService;
import com.github.guoyyit.callgraphjava.store.GraphStore;
import com.github.guoyyit.callgraphjava.store.JournalStore;
import com.github.guoyyit.callgraphjava.store.SnapshotPersistence;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(CallGraphProperties.class)
public class CallGraphConfig {

    @Bean
    public GraphStore graphStore() {
        return new GraphStore();
    }

    @Bean
    public SnapshotPersistence snapshotPersistence() {
        return new SnapshotPersistence();
    }

    @Bean
    public JournalStore journalStore(CallGraphProperties properties) {
        return new JournalStore(Path.of(properties.getStatePath()));
    }

    @Bean
    public CallGraphService callGraphService(CallGraphProperties properties, GraphStore graphStore,
                                             SnapshotPersistence persistence, JournalStore journalStore) {
        return new CallGraphService(Path.of(properties.getRepoPath()),
                Path.of(properties.getStatePath()), graphStore, persistence, journalStore);
    }

    @Bean
    public QueryService queryService(GraphStore graphStore) {
        return new QueryService(graphStore);
    }

    @Bean(destroyMethod = "shutdown")
    public BuildManager buildManager(CallGraphService callGraphService) {
        return new BuildManager(callGraphService);
    }

    @Bean
    public ApplicationRunner callGraphStartup(CallGraphService callGraphService,
                                              CallGraphProperties properties) {
        return args -> {
            callGraphService.recover();
            if (properties.isBuildOnStartup()) {
                callGraphService.build(callGraphService.repositoryRoot(),
                        ProgressToken.neverCancelled());
            }
        };
    }
}
