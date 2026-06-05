package com.iflytek.skillhub.controller.support;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class SkillPackageArchiveExtractor {

    public record ExtractionResult(List<PackageEntry> entries, List<String> warnings) {}

    private final long maxTotalPackageSize;
    private final long maxSingleFileSize;
    private final int maxFileCount;

    public SkillPackageArchiveExtractor(SkillPublishProperties properties) {
        this.maxTotalPackageSize = properties.getMaxPackageSize();
        this.maxSingleFileSize = properties.getMaxSingleFileSize();
        this.maxFileCount = properties.getMaxFileCount();
    }

    public List<PackageEntry> extract(MultipartFile file) throws IOException {
        if (file.getSize() > maxTotalPackageSize) {
            throw new IllegalArgumentException(
                    "Package too large: " + file.getSize() + " bytes (max: "
                            + maxTotalPackageSize + ")"
            );
        }

        List<PackageEntry> entries = new ArrayList<>();
        long totalSize = 0;

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                if (isOsMetadataEntry(zipEntry.getName())) {
                    zis.closeEntry();
                    continue;
                }

                if (entries.size() >= maxFileCount) {
                    throw new IllegalArgumentException(
                            "Too many files: more than " + maxFileCount
                    );
                }

                String normalizedPath = SkillPackagePolicy.normalizeEntryPath(zipEntry.getName());
                byte[] content = readEntry(zis, normalizedPath);
                totalSize += content.length;
                if (totalSize > maxTotalPackageSize) {
                    throw new IllegalArgumentException(
                            "Package too large: " + totalSize + " bytes (max: "
                                    + maxTotalPackageSize + ")"
                    );
                }

                entries.add(new PackageEntry(
                        normalizedPath,
                        content,
                        content.length,
                        SkillPackageContentTypeResolver.determineContentType(normalizedPath)
                ));
                zis.closeEntry();
            }
        }

        return stripSingleRootDirectory(entries);
    }

    public ExtractionResult extractWithWarnings(MultipartFile file) throws IOException {
        List<PackageEntry> entries = extract(file);
        return promoteSingleSkillMdDirectory(entries);
    }

    /**
     * If all file paths share a single root directory prefix (e.g., "my-skill/xxx"),
     * strip that prefix. Otherwise return entries unchanged.
     */
    static List<PackageEntry> stripSingleRootDirectory(List<PackageEntry> entries) {
        if (entries.isEmpty()) return entries;

        Set<String> rootSegments = new HashSet<>();
        for (PackageEntry entry : entries) {
            int slashIndex = entry.path().indexOf('/');
            if (slashIndex < 0) {
                // File at root level, no stripping
                return entries;
            }
            rootSegments.add(entry.path().substring(0, slashIndex));
        }

        if (rootSegments.size() != 1) {
            return entries;
        }

        String prefix = rootSegments.iterator().next() + "/";
        return entries.stream()
                .map(e -> new PackageEntry(
                        e.path().substring(prefix.length()),
                        e.content(),
                        e.size(),
                        e.contentType()))
                .toList();
    }

    static ExtractionResult promoteSingleSkillMdDirectory(List<PackageEntry> entries) {
        boolean hasRootSkillMd = entries.stream()
                .anyMatch(e -> SkillPackagePolicy.SKILL_MD_PATH.equals(e.path()));
        if (hasRootSkillMd) {
            return new ExtractionResult(entries, List.of());
        }

        Set<String> skillMdDirs = new HashSet<>();
        for (PackageEntry entry : entries) {
            int slashIndex = entry.path().indexOf('/');
            if (slashIndex > 0) {
                String relativePath = entry.path().substring(slashIndex + 1);
                if (SkillPackagePolicy.SKILL_MD_PATH.equals(relativePath)) {
                    skillMdDirs.add(entry.path().substring(0, slashIndex));
                }
            }
        }

        if (skillMdDirs.isEmpty()) {
            return new ExtractionResult(entries, List.of());
        }
        if (skillMdDirs.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous package: SKILL.md found in multiple directories: " + skillMdDirs);
        }

        String prefix = skillMdDirs.iterator().next() + "/";
        List<PackageEntry> promoted = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (PackageEntry entry : entries) {
            if (entry.path().startsWith(prefix)) {
                promoted.add(new PackageEntry(
                        entry.path().substring(prefix.length()),
                        entry.content(),
                        entry.size(),
                        entry.contentType()));
            } else {
                warnings.add("Ignored file outside skill directory: " + entry.path());
            }
        }

        return new ExtractionResult(promoted, warnings);
    }

    private static boolean isOsMetadataEntry(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("__MACOSX/") || normalized.equals("__MACOSX")) return true;
        String fileName = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return fileName.equals(".DS_Store") || fileName.startsWith("._");
    }

    private byte[] readEntry(ZipInputStream zis, String path) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int read;
        while ((read = zis.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > maxSingleFileSize) {
                throw new IllegalArgumentException(
                        "File too large: " + path + " (" + totalRead + " bytes, max: "
                                + maxSingleFileSize + ")"
                );
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

}
