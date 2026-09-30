package com.github.guoyyit.callgraphjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest(properties = {
        "callgraph.repo=target/test-smoke-repo",
        "callgraph.data=target/test-callgraph-data-smoke"
})
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
