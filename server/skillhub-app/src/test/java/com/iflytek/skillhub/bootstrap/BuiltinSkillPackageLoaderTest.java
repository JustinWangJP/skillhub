package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSkillPackageLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSkillhubHelloFromClasspath() throws Exception {
        BuiltinSkillPackageLoader loader =
                new BuiltinSkillPackageLoader(new PathMatchingResourcePatternResolver());

        var packages = loader.loadPackages();

        var skillhubHello = packages.stream()
                .filter(skillPackage -> skillPackage.directory().equals("skillhub-hello"))
                .findFirst()
                .orElseThrow();
        assertThat(skillhubHello.entries())
                .extracting(entry -> entry.path())
                .containsExactly("README.md", "SKILL.md");
        assertThat(skillhubHello.entries())
                .allSatisfy(entry -> assertThat(entry.contentType()).isEqualTo("text/markdown"));
    }

    @Test
    void productionSkillhubHelloResourcePassesPackageValidation() throws Exception {
        BuiltinSkillPackageLoader loader =
                new BuiltinSkillPackageLoader(new PathMatchingResourcePatternResolver());
        SkillPackageValidator validator = new SkillPackageValidator(new SkillMetadataParser());

        var skillhubHello = loader.loadPackages().stream()
                .filter(skillPackage -> skillPackage.directory().equals("skillhub-hello"))
                .findFirst()
                .orElseThrow();

        var result = validator.validate(skillhubHello.entries());
        assertThat(result.passed()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void loadsBuiltInPackageFromJarClasspath() throws Exception {
        Path jarPath = tempDir.resolve("builtin-skills.jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeJarDirectory(jarOutputStream, "builtin-skills/");
            writeJarDirectory(jarOutputStream, "builtin-skills/skillhub-hello/");
            writeJarEntry(jarOutputStream, "builtin-skills/skillhub-hello/SKILL.md", """
                    ---
                    name: skillhub-hello
                    description: SkillHub hello
                    version: 1.0.0
                    ---
                    # SkillHub Hello
                    """);
            writeJarEntry(jarOutputStream, "builtin-skills/skillhub-hello/README.md", "# SkillHub Hello\n");
        }

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{jarPath.toUri().toURL()},
                null
        )) {
            BuiltinSkillPackageLoader loader =
                    new BuiltinSkillPackageLoader(new PathMatchingResourcePatternResolver(classLoader));

            var packages = loader.loadPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().directory()).isEqualTo("skillhub-hello");
            assertThat(packages.getFirst().entries())
                    .extracting(entry -> entry.path())
                    .containsExactly("README.md", "SKILL.md");
        }
    }

    @Test
    void fingerprintsAreStableAcrossEntryOrdering() {
        byte[] skillMd = """
                ---
                name: demo
                description: Demo
                version: 1.0.0
                ---
                # Demo
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] readme = "# Demo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var first = List.of(
                new com.iflytek.skillhub.domain.skill.validation.PackageEntry(
                        "SKILL.md", skillMd, skillMd.length, "text/markdown"),
                new com.iflytek.skillhub.domain.skill.validation.PackageEntry(
                        "README.md", readme, readme.length, "text/markdown")
        );
        var second = List.of(first.get(1), first.get(0));

        assertThat(BuiltinSkillFingerprints.fromEntries(first))
                .isEqualTo(BuiltinSkillFingerprints.fromEntries(second));
    }

    private void writeJarDirectory(JarOutputStream jarOutputStream, String name) throws Exception {
        jarOutputStream.putNextEntry(new JarEntry(name));
        jarOutputStream.closeEntry();
    }

    private void writeJarEntry(JarOutputStream jarOutputStream, String name, String content) throws Exception {
        jarOutputStream.putNextEntry(new JarEntry(name));
        jarOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        jarOutputStream.closeEntry();
    }
}
