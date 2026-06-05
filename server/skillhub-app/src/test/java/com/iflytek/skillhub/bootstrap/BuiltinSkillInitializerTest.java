package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillFileRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuiltinSkillInitializerTest {

    private static final String PUBLISHER_ID = BuiltinSkillInitializer.BUILTIN_PUBLISHER_ID;

    @Mock private BuiltinSkillPackageLoader packageLoader;
    @Mock private SkillPackageValidator packageValidator;
    @Mock private SkillPublishService skillPublishService;
    @Mock private NamespaceRepository namespaceRepository;
    @Mock private NamespaceMemberRepository namespaceMemberRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillVersionRepository skillVersionRepository;
    @Mock private SkillFileRepository skillFileRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private BuiltinSkillProperties properties;
    private BuiltinSkillInitializer initializer;
    private Namespace globalNamespace;

    @BeforeEach
    void setUp() {
        properties = new BuiltinSkillProperties();
        globalNamespace = new Namespace("global", "Global", "system");
        ReflectionTestUtils.setField(globalNamespace, "id", 1L);
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        lenient().when(packageValidator.validate(any())).thenReturn(ValidationResult.pass());

        initializer = new BuiltinSkillInitializer(
                properties,
                packageLoader,
                new SkillMetadataParser(),
                packageValidator,
                skillPublishService,
                namespaceRepository,
                namespaceMemberRepository,
                userAccountRepository,
                skillRepository,
                skillVersionRepository,
                skillFileRepository,
                transactionManager
        );
    }

    @Test
    void disabledInitializerDoesNotLoadPackages() throws Exception {
        properties.setEnabled(false);

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(packageLoader, never()).loadPackages();
        verify(skillPublishService, never()).publishFromEntries(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void firstStartupCreatesPublisherMembershipAndPublishesPackage() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of());
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.empty());
        when(skillPublishService.publishFromEntries(
                eq("global"),
                eq(skillPackage.entries()),
                eq(PUBLISHER_ID),
                eq(SkillVisibility.PUBLIC),
                eq(Set.of("SUPER_ADMIN")),
                eq(false)
        )).thenReturn(publishResult("1.0.0"));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(userAccountRepository).save(any(UserAccount.class));
        verify(namespaceMemberRepository).save(any(NamespaceMember.class));
        verify(skillPublishService).publishFromEntries(
                "global",
                skillPackage.entries(),
                PUBLISHER_ID,
                SkillVisibility.PUBLIC,
                Set.of("SUPER_ADMIN"),
                false
        );
    }

    @Test
    void existingPublisherAndMembershipAreReusedIdempotently() throws Exception {
        UserAccount existingPublisher = new UserAccount(PUBLISHER_ID, "Old name", "old@example.invalid", null);
        NamespaceMember existingMember = new NamespaceMember(1L, PUBLISHER_ID, NamespaceRole.MEMBER);
        when(namespaceRepository.findBySlug("global")).thenReturn(Optional.of(globalNamespace));
        when(userAccountRepository.findById(PUBLISHER_ID)).thenReturn(Optional.of(existingPublisher));
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(namespaceMemberRepository.findByNamespaceIdAndUserId(1L, PUBLISHER_ID))
                .thenReturn(Optional.of(existingMember));
        when(namespaceMemberRepository.save(any(NamespaceMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packageLoader.loadPackages()).thenReturn(List.of());

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(userAccountRepository).save(existingPublisher);
        verify(namespaceMemberRepository).save(existingMember);
        org.assertj.core.api.Assertions.assertThat(existingMember.getRole()).isEqualTo(NamespaceRole.OWNER);
    }

    @Test
    void validationWarningsSkipPublishWithoutConfirmingWarnings() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(packageValidator.validate(skillPackage.entries()))
                .thenReturn(new ValidationResult(true, List.of(), List.of("warning")));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(skillPublishService, never()).publishFromEntries(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void samePublishedVersionWithSameFingerprintSkipsPublish() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        Skill skill = builtInSkill(11L);
        SkillVersion version = version(11L, 22L, "1.0.0", SkillVersionStatus.PUBLISHED);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of(skill));
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.of(skill));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, "1.0.0")).thenReturn(Optional.of(version));
        when(skillFileRepository.findByVersionId(22L)).thenReturn(filesFor(version.getId(), skillPackage.entries()));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(skillPublishService, never()).publishFromEntries(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void samePublishedVersionWithDifferentFingerprintSkipsPublish() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        Skill skill = builtInSkill(11L);
        SkillVersion version = version(11L, 22L, "1.0.0", SkillVersionStatus.PUBLISHED);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of(skill));
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.of(skill));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, "1.0.0")).thenReturn(Optional.of(version));
        when(skillFileRepository.findByVersionId(22L)).thenReturn(List.of(
                new SkillFile(22L, "SKILL.md", 10L, "text/markdown", "different", "key")
        ));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(skillPublishService, never()).publishFromEntries(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sameNonPublishedVersionSkipsPublish() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        Skill skill = builtInSkill(11L);
        SkillVersion version = version(11L, 22L, "1.0.0", SkillVersionStatus.UPLOADED);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of(skill));
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.of(skill));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, "1.0.0")).thenReturn(Optional.of(version));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(skillFileRepository, never()).findByVersionId(any());
        verify(skillPublishService, never()).publishFromEntries(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void newerBuiltInVersionPublishesWhenOlderVersionExists() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.1", "Hello");
        Skill skill = builtInSkill(11L);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of(skill));
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.of(skill));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, "1.0.1")).thenReturn(Optional.empty());
        when(skillPublishService.publishFromEntries(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(publishResult("1.0.1"));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(skillPublishService).publishFromEntries(
                "global",
                skillPackage.entries(),
                PUBLISHER_ID,
                SkillVisibility.PUBLIC,
                Set.of("SUPER_ADMIN"),
                false
        );
    }

    @Test
    void userOwnedPublishedSlugSkipsBuiltInPublish() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        Skill otherSkill = new Skill(1L, "skillhub-hello", "user-1", SkillVisibility.PUBLIC);
        ReflectionTestUtils.setField(otherSkill, "id", 33L);
        SkillVersion otherPublishedVersion = version(33L, 44L, "1.0.0", SkillVersionStatus.PUBLISHED);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of(otherSkill));
        when(skillVersionRepository.findBySkillIdAndStatus(33L, SkillVersionStatus.PUBLISHED))
                .thenReturn(List.of(otherPublishedVersion));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(skillRepository, never()).findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID);
        verify(skillPublishService, never()).publishFromEntries(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void publishFailureIsContainedAndDoesNotAbortStartup() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of());
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.empty());
        when(skillPublishService.publishFromEntries(any(), any(), any(), any(), any(), eq(false)))
                .thenThrow(new IllegalStateException("storage unavailable"));

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
    }

    @Test
    void concurrentPublishedSameFingerprintSkipsAfterPublishFailure() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        Skill skill = builtInSkill(11L);
        SkillVersion version = version(11L, 22L, "1.0.0", SkillVersionStatus.PUBLISHED);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of());
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.empty(), Optional.of(skill));
        when(skillPublishService.publishFromEntries(any(), any(), any(), any(), any(), eq(false)))
                .thenThrow(new IllegalStateException("version exists"));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, "1.0.0")).thenReturn(Optional.of(version));
        when(skillFileRepository.findByVersionId(22L)).thenReturn(filesFor(version.getId(), skillPackage.entries()));

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
    }

    @Test
    void concurrentPublishedDifferentFingerprintSkipsAfterPublishFailure() throws Exception {
        BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage = packageWithVersion("1.0.0", "Hello");
        Skill skill = builtInSkill(11L);
        SkillVersion version = version(11L, 22L, "1.0.0", SkillVersionStatus.PUBLISHED);
        setupPublisher();
        when(packageLoader.loadPackages()).thenReturn(List.of(skillPackage));
        when(skillRepository.findByNamespaceIdAndSlug(1L, "skillhub-hello")).thenReturn(List.of());
        when(skillRepository.findByNamespaceIdAndSlugAndOwnerId(1L, "skillhub-hello", PUBLISHER_ID))
                .thenReturn(Optional.empty(), Optional.of(skill));
        when(skillPublishService.publishFromEntries(any(), any(), any(), any(), any(), eq(false)))
                .thenThrow(new IllegalStateException("version exists"));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, "1.0.0")).thenReturn(Optional.of(version));
        when(skillFileRepository.findByVersionId(22L)).thenReturn(List.of(
                new SkillFile(22L, "SKILL.md", 10L, "text/markdown", "different", "key")
        ));

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
    }

    private void setupPublisher() {
        when(namespaceRepository.findBySlug("global")).thenReturn(Optional.of(globalNamespace));
        when(userAccountRepository.findById(PUBLISHER_ID)).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(namespaceMemberRepository.findByNamespaceIdAndUserId(1L, PUBLISHER_ID)).thenReturn(Optional.empty());
        when(namespaceMemberRepository.save(any(NamespaceMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BuiltinSkillPackageLoader.BuiltinSkillPackage packageWithVersion(String version, String body) {
        byte[] skillMd = ("""
                ---
                name: skillhub-hello
                description: SkillHub hello
                version: %s
                ---
                # SkillHub Hello
                %s
                """.formatted(version, body)).getBytes(StandardCharsets.UTF_8);
        byte[] readme = "# SkillHub Hello\n".getBytes(StandardCharsets.UTF_8);
        return new BuiltinSkillPackageLoader.BuiltinSkillPackage("skillhub-hello", List.of(
                new PackageEntry("README.md", readme, readme.length, "text/markdown"),
                new PackageEntry("SKILL.md", skillMd, skillMd.length, "text/markdown")
        ));
    }

    private Skill builtInSkill(Long id) {
        Skill skill = new Skill(1L, "skillhub-hello", PUBLISHER_ID, SkillVisibility.PUBLIC);
        ReflectionTestUtils.setField(skill, "id", id);
        return skill;
    }

    private SkillVersion version(Long skillId, Long versionId, String version, SkillVersionStatus status) {
        SkillVersion skillVersion = new SkillVersion(skillId, version, PUBLISHER_ID);
        skillVersion.setStatus(status);
        ReflectionTestUtils.setField(skillVersion, "id", versionId);
        return skillVersion;
    }

    private SkillPublishService.PublishResult publishResult(String version) {
        SkillVersion skillVersion = version(11L, 22L, version, SkillVersionStatus.PUBLISHED);
        return new SkillPublishService.PublishResult(11L, "skillhub-hello", skillVersion);
    }

    private List<SkillFile> filesFor(Long versionId, List<PackageEntry> entries) {
        return entries.stream()
                .map(entry -> new SkillFile(
                        versionId,
                        entry.path(),
                        entry.size(),
                        entry.contentType(),
                        sha256(entry.content()),
                        "skills/11/" + versionId + "/" + entry.path()
                ))
                .toList();
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
