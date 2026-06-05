package com.iflytek.skillhub.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for publishing bundled example skills at application startup.
 */
@Component
@ConfigurationProperties(prefix = "skillhub.builtin-skills")
public class BuiltinSkillProperties {
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
