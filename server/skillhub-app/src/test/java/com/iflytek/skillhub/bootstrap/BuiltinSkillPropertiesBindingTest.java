package com.iflytek.skillhub.bootstrap;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSkillPropertiesBindingTest {

    @Test
    void defaultConfigEnablesBuiltinSkills() throws Exception {
        BuiltinSkillProperties properties = bindProperties(Map.of());

        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    void environmentVariableCanDisableBuiltinSkills() throws Exception {
        BuiltinSkillProperties properties = bindProperties(Map.of("SKILLHUB_BUILTIN_SKILLS_ENABLED", "false"));

        assertThat(properties.isEnabled()).isFalse();
    }

    private BuiltinSkillProperties bindProperties(Map<String, Object> envVars) throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env", envVars));

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (org.springframework.core.env.PropertySource<?> propertySource :
                loader.load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(propertySource);
        }
        ConfigurationPropertySources.attach(environment);

        return Binder.get(environment)
                .bind("skillhub.builtin-skills", BuiltinSkillProperties.class)
                .orElseThrow(() -> new IllegalStateException("Failed to bind built-in skill properties"));
    }
}
