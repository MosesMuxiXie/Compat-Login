package cn.compatlogin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthlibInjectorCompatibilityTest {
    @Test
    void detectsTheServerJavaAgentFromTheReportedStartCommand() {
        assertTrue(AuthlibInjectorCompatibility.findAuthlibInjectorArgument(List.of(
            "-Xmx8G",
            "-javaagent:authlib-injector-1.2.7.jar=littleskin.cn"
        )).isPresent());
    }

    @Test
    void ignoresUnrelatedJavaAgents() {
        assertFalse(AuthlibInjectorCompatibility.findAuthlibInjectorArgument(List.of(
            "-Xmx8G",
            "-javaagent:unrelated-monitoring-agent.jar"
        )).isPresent());
    }
}
