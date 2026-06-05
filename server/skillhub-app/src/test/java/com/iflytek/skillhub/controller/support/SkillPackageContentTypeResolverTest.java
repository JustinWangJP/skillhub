package com.iflytek.skillhub.controller.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageContentTypeResolverTest {

    @Test
    void determinesKnownContentTypes() {
        assertThat(SkillPackageContentTypeResolver.determineContentType("SKILL.md")).isEqualTo("text/markdown");
        assertThat(SkillPackageContentTypeResolver.determineContentType("script.py")).isEqualTo("text/x-python");
        assertThat(SkillPackageContentTypeResolver.determineContentType("config.yaml")).isEqualTo("application/x-yaml");
        assertThat(SkillPackageContentTypeResolver.determineContentType("image.png")).isEqualTo("image/png");
        assertThat(SkillPackageContentTypeResolver.determineContentType("archive.bin")).isEqualTo("application/octet-stream");
    }
}
