package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.controller.support.SkillPackageContentTypeResolver;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads directory-form built-in skill packages from classpath resources.
 */
@Component
public class BuiltinSkillPackageLoader {

    static final String BUILTIN_SKILLS_PATTERN = "classpath*:builtin-skills/*/SKILL.md";
    private static final String ROOT_MARKER = "builtin-skills/";
    private static final String SKILL_MD = "SKILL.md";

    private final ResourcePatternResolver resourcePatternResolver;

    public BuiltinSkillPackageLoader() {
        this(new PathMatchingResourcePatternResolver());
    }

    BuiltinSkillPackageLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<BuiltinSkillPackage> loadPackages() throws IOException {
        Set<String> directories = discoverPackageDirectories();
        List<BuiltinSkillPackage> packages = new ArrayList<>();
        for (String directory : directories) {
            List<PackageEntry> packageEntries = loadPackageEntries(directory);
            boolean hasSkillMd = packageEntries.stream().anyMatch(packageEntry -> SKILL_MD.equals(packageEntry.path()));
            if (hasSkillMd) {
                packages.add(new BuiltinSkillPackage(directory, packageEntries));
            }
        }
        return packages;
    }

    private Set<String> discoverPackageDirectories() throws IOException {
        Resource[] skillMdResources = resourcePatternResolver.getResources(BUILTIN_SKILLS_PATTERN);
        Set<String> directories = new TreeSet<>();
        for (Resource resource : skillMdResources) {
            String relativePath = relativeBuiltinPath(resource);
            if (relativePath == null || relativePath.isBlank()) {
                continue;
            }
            int separatorIndex = relativePath.indexOf('/');
            if (separatorIndex > 0 && SKILL_MD.equals(relativePath.substring(separatorIndex + 1))) {
                directories.add(relativePath.substring(0, separatorIndex));
            }
        }
        return directories;
    }

    private List<PackageEntry> loadPackageEntries(String directory) throws IOException {
        Resource[] resources = resourcePatternResolver.getResources("classpath*:builtin-skills/" + directory + "/**");
        List<PackageEntry> entries = new ArrayList<>();
        for (Resource resource : resources) {
            String relativePath = relativeBuiltinPath(resource);
            if (relativePath == null || relativePath.isBlank() || relativePath.endsWith("/")) {
                continue;
            }
            String directoryPrefix = directory + "/";
            if (!relativePath.startsWith(directoryPrefix) || directoryPrefix.length() == relativePath.length()) {
                continue;
            }

            String packagePath = SkillPackagePolicy.normalizeEntryPath(relativePath.substring(directoryPrefix.length()));
            try (InputStream inputStream = resource.getInputStream()) {
                byte[] content = inputStream.readAllBytes();
                entries.add(new PackageEntry(
                        packagePath,
                        content,
                        content.length,
                        SkillPackageContentTypeResolver.determineContentType(packagePath)
                ));
            }
        }
        return entries.stream()
                .sorted(Comparator.comparing(PackageEntry::path))
                .toList();
    }

    private String relativeBuiltinPath(Resource resource) throws IOException {
        String url = resource.getURL().toExternalForm();
        int markerIndex = url.lastIndexOf(ROOT_MARKER);
        if (markerIndex < 0) {
            return null;
        }
        return url.substring(markerIndex + ROOT_MARKER.length());
    }

    public record BuiltinSkillPackage(String directory, List<PackageEntry> entries) {
    }
}
