package com.github.guoyyit.callgraphjava.config;

import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.service.BuildManager;
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
        CallGraphService service = new CallGraphService(Path.of(properties.getRepoPath()),
                Path.of(properties.getStatePath()), graphStore, persistence, journalStore);
        // Restore the latest committed state before the web server starts
        // accepting requests. When the snapshot already contains every
        // committed change this only loads the snapshot and never triggers a
        // full parse or build.
        service.recover();
        return service;
    }

    @Bean(destroyMethod = "shutdown")
    public BuildManager buildManager(CallGraphService callGraphService) {
        return new BuildManager(callGraphService);
    }

    @Bean
    public ApplicationRunner initialBuildRunner(CallGraphService service, CallGraphProperties properties) {
        return args -> {
            // A full build on startup only happens when explicitly enabled and
            // there is no recovered committed state to serve.
            if (properties.isBuildOnStartup() && service.snapshot().revision() == 0
                    && service.snapshot().files().isEmpty()) {
                service.build(service.repositoryRoot(), ProgressToken.neverCancelled());
            }
        };
    }

    @Bean
    public QueryService queryService(GraphStore graphStore) {
        return new QueryService(graphStore);
    }
}
