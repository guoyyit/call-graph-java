package com.github.guoyyit.callgraphjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "callgraph")
public class CallGraphProperties {
    private String repoPath = ".";
    private String statePath = ".callgraph-state";
    private boolean buildOnStartup = false;

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }

    public String getStatePath() {
        return statePath;
    }

    public void setStatePath(String statePath) {
        this.statePath = statePath;
    }

    public boolean isBuildOnStartup() {
        return buildOnStartup;
    }

    public void setBuildOnStartup(boolean buildOnStartup) {
        this.buildOnStartup = buildOnStartup;
    }
}
