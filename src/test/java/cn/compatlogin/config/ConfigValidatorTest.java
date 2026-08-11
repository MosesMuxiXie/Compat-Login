package cn.compatlogin.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {
    @Test
    void defaultConfigurationIsValid() {
        assertTrue(ConfigValidator.validate(CompatLoginConfig.defaults()).isEmpty());
    }

    @Test
    void reportsMultipleIssuesWithFieldPaths() {
        CompatLoginConfig config = CompatLoginConfig.defaults();
        config.schemaVersion = 7;
        config.authentication.requestTimeoutSeconds = 0;
        CompatLoginConfig.Service service = config.authentication.services.get(1);
        service.name = " ";
        service.enabled = null;
        service.hasJoinedUrl = "http://localhost/not-has-joined?fixed=true";

        List<String> issues = ConfigValidator.validate(config);

        assertTrue(issues.stream().anyMatch(issue -> issue.startsWith("schemaVersion:")));
        assertTrue(issues.stream().anyMatch(issue -> issue.startsWith("authentication.requestTimeoutSeconds:")));
        assertTrue(issues.stream().anyMatch(issue -> issue.startsWith("authentication.services[1].name:")));
        assertTrue(issues.stream().anyMatch(issue -> issue.startsWith("authentication.services[1].enabled:")));
        assertTrue(issues.stream().anyMatch(issue -> issue.startsWith("authentication.services[1].hasJoinedUrl: http is disabled")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("query strings and fragments are not allowed")));
    }

    @Test
    void acceptsAuthlibInjectorApiRootsAndDetectsTheirResolvedDuplicates() {
        CompatLoginConfig config = CompatLoginConfig.defaults();
        config.authentication.services.clear();
        config.authentication.services.add(new CompatLoginConfig.Service(
            "LittleSkin API root",
            true,
            "https://littleskin.cn/api/yggdrasil"
        ));

        assertTrue(ConfigValidator.validate(config).isEmpty());

        config.authentication.services.add(new CompatLoginConfig.Service(
            "LittleSkin full endpoint",
            true,
            "https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/hasJoined"
        ));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(issue -> issue.contains("duplicate hasJoined endpoint")));
    }

    @Test
    void permitsHttpOnlyWhenExplicitlyEnabled() {
        CompatLoginConfig config = CompatLoginConfig.defaults();
        config.authentication.services.clear();
        config.authentication.services.add(new CompatLoginConfig.Service(
            "Local",
            true,
            "http://127.0.0.1:25580/session/minecraft/hasJoined"
        ));

        assertTrue(ConfigValidator.validate(config).stream().anyMatch(issue -> issue.contains("http is disabled")));

        config.authentication.allowInsecureHttp = true;
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

}
