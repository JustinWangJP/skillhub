package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class BuiltinSkillFingerprints {

    private BuiltinSkillFingerprints() {
    }

    static String fromEntries(List<PackageEntry> entries) {
        StringBuilder canonical = new StringBuilder();
        entries.stream()
                .sorted(Comparator.comparing(PackageEntry::path))
                .map(entry -> entry.path() + "\0" + sha256(entry.content()))
                .forEach(line -> canonical.append(line).append('\n'));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String fromFiles(List<SkillFile> files) {
        StringBuilder canonical = new StringBuilder();
        files.stream()
                .sorted(Comparator.comparing(SkillFile::getFilePath))
                .map(file -> file.getFilePath() + "\0" + file.getSha256())
                .forEach(line -> canonical.append(line).append('\n'));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to calculate SHA-256 fingerprint", exception);
        }
    }
}
