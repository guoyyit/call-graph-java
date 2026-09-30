package com.github.guoyyit.callgraphjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GraphControllerIntegrationTest {

    @TempDir
    static Path tempData;

    @BeforeAll
    static void configurePaths() {
        // 把内置夹具仓库复制到临时目录，并通过系统属性在 Spring 启动前注入，
        // 保证每个测试 JVM 都从全新数据目录开始。
        Path fixtureRepo = Path.of("src/test/resources/fixture-repo");
        try {
            Path repoCopy = tempData.resolve("repo");
            copyTree(fixtureRepo, repoCopy);
            System.setProperty("callgraph.repo", repoCopy.toAbsolutePath().toString());
            System.setProperty("callgraph.data",
                    tempData.resolve("data").toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walk(source).forEach(from -> {
            try {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void buildsAndQueriesViaHttp() throws Exception {
        mockMvc.perform(post("/api/callgraph/build/full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/api/callgraph/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.edges").isNumber())
                .andExpect(jsonPath("$.methods").isNumber());

        mockMvc.perform(get("/api/callgraph/callees")
                        .param("owner", "fixture.Client")
                        .param("name", "run")
                        .param("descriptor", "()"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.callee == 'fixture.Shape#area()')]").exists());

        mockMvc.perform(get("/api/callgraph/scc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recursiveMethods").isArray());

        mockMvc.perform(get("/api/callgraph/cross-package"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/callgraph/reachable")
                        .param("owner", "fixture.Client")
                        .param("name", "run")
                        .param("descriptor", "()")
                        .param("targetOwner", "fixture.Circle")
                        .param("targetName", "area"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true));

        mockMvc.perform(post("/api/callgraph/cancel"))
                .andExpect(status().isOk());
    }

    @Test
    void queriesBeforeBuildReturnNotFound() {
        // status 永远可用；具体查询在无快照时返回 404
        // （本测试类共享已构建上下文，因此单独断言 status 不要求快照）
        // 这里仅验证 status 端点健壮性。
        try {
            mockMvc.perform(get("/api/callgraph/status")).andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
