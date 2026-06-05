package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFileRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.domain.namespace.SlugValidator;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes bundled example skills into the global namespace during startup.
 */
@Component
public class BuiltinSkillInitializer implements ApplicationRunner {

    static final String BUILTIN_PUBLISHER_ID = "builtin-skill-publisher";
    static final String GLOBAL_NAMESPACE = "global";
    private static final String BUILTIN_PUBLISHER_NAME = "SkillHub Built-in Publisher";
    private static final String BUILTIN_PUBLISHER_EMAIL = "builtin-skill-publisher@example.invalid";
    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillInitializer.class);

    private final BuiltinSkillProperties properties;
    private final BuiltinSkillPackageLoader packageLoader;
    private final SkillMetadataParser metadataParser;
    private final SkillPackageValidator packageValidator;
    private final SkillPublishService skillPublishService;
    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private final UserAccountRepository userAccountRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillFileRepository skillFileRepository;
    private final TransactionTemplate transactionTemplate;

    public BuiltinSkillInitializer(BuiltinSkillProperties properties,
                                   BuiltinSkillPackageLoader packageLoader,
                                   SkillMetadataParser metadataParser,
                                   SkillPackageValidator packageValidator,
                                   SkillPublishService skillPublishService,
                                   NamespaceRepository namespaceRepository,
                                   NamespaceMemberRepository namespaceMemberRepository,
                                   UserAccountRepository userAccountRepository,
                                   SkillRepository skillRepository,
                                   SkillVersionRepository skillVersionRepository,
                                   SkillFileRepository skillFileRepository,
                                   PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.packageLoader = packageLoader;
        this.metadataParser = metadataParser;
        this.packageValidator = packageValidator;
        this.skillPublishService = skillPublishService;
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.userAccountRepository = userAccountRepository;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillFileRepository = skillFileRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("Built-in skill initialization is disabled");
            return;
        }

        Namespace globalNamespace;
        try {
            globalNamespace = ensurePublisher();
        } catch (RuntimeException exception) {
            log.error("Failed to prepare built-in skill publisher, skipping built-in skill initialization",
                    exception);
            return;
        }
        if (globalNamespace == null) {
            return;
        }

        List<BuiltinSkillPackageLoader.BuiltinSkillPackage> packages;
        try {
            packages = packageLoader.loadPackages();
        } catch (Exception exception) {
            log.error("Failed to load built-in skill packages", exception);
            return;
        }

        for (BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage : packages) {
            try {
                initializePackage(globalNamespace, skillPackage);
            } catch (Exception exception) {
                log.error("Failed to initialize built-in skill package [directory={}]",
                        skillPackage.directory(), exception);
            }
        }
    }

    private Namespace ensurePublisher() {
        return transactionTemplate.execute(status -> {
            Namespace globalNamespace = namespaceRepository.findBySlug(GLOBAL_NAMESPACE)
                    .orElse(null);
            if (globalNamespace == null) {
                log.error("Missing built-in global namespace, skipping built-in skill initialization");
                return null;
            }

            UserAccount publisher = userAccountRepository.findById(BUILTIN_PUBLISHER_ID)
                    .orElseGet(() -> new UserAccount(
                            BUILTIN_PUBLISHER_ID,
                            BUILTIN_PUBLISHER_NAME,
                            BUILTIN_PUBLISHER_EMAIL,
                            null
                    ));
            publisher.setDisplayName(BUILTIN_PUBLISHER_NAME);
            publisher.setEmail(BUILTIN_PUBLISHER_EMAIL);
            publisher.setStatus(UserStatus.ACTIVE);
            userAccountRepository.save(publisher);

            NamespaceMember member = namespaceMemberRepository
                    .findByNamespaceIdAndUserId(globalNamespace.getId(), BUILTIN_PUBLISHER_ID)
                    .orElseGet(() -> new NamespaceMember(globalNamespace.getId(), BUILTIN_PUBLISHER_ID, NamespaceRole.OWNER));
            if (member.getRole() != NamespaceRole.OWNER) {
                member.setRole(NamespaceRole.OWNER);
            }
            namespaceMemberRepository.save(member);
            return globalNamespace;
        });
    }

    private void initializePackage(Namespace globalNamespace,
                                   BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage) {
        List<PackageEntry> entries = skillPackage.entries();
        SkillMetadata metadata = parseMetadata(skillPackage.directory(), entries);
        if (metadata.version() == null || metadata.version().isBlank()) {
            log.error("Built-in skill package is missing an explicit version [directory={}]",
                    skillPackage.directory());
            return;
        }
        String skillSlug = SlugValidator.slugify(metadata.name());

        ValidationResult validation = packageValidator.validate(entries);
        if (!validation.passed() || validation.hasWarnings()) {
            log.error("Built-in skill package failed validation [directory={}, slug={}, version={}, errors={}, warnings={}]",
                    skillPackage.directory(), skillSlug, metadata.version(), validation.errors(), validation.warnings());
            return;
        }

        if (hasPublishedOtherOwnerConflict(globalNamespace.getId(), skillSlug)) {
            log.warn("Skipping built-in skill because another owner already published the slug [directory={}, namespace={}, slug={}]",
                    skillPackage.directory(), GLOBAL_NAMESPACE, skillSlug);
            return;
        }

        Optional<Skill> existingBuiltInSkill =
                skillRepository.findByNamespaceIdAndSlugAndOwnerId(globalNamespace.getId(), skillSlug, BUILTIN_PUBLISHER_ID);
        if (existingBuiltInSkill.isPresent()
                && shouldSkipExistingVersion(skillPackage, existingBuiltInSkill.get(), metadata.version())) {
            return;
        }

        publishPackage(skillPackage, skillSlug, metadata.version());
    }

    private SkillMetadata parseMetadata(String directory, List<PackageEntry> entries) {
        PackageEntry skillMd = entries.stream()
                .filter(entry -> "SKILL.md".equals(entry.path()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Built-in package missing SKILL.md: " + directory));
        return metadataParser.parse(new String(skillMd.content(), StandardCharsets.UTF_8));
    }

    private boolean hasPublishedOtherOwnerConflict(Long namespaceId, String skillSlug) {
        return skillRepository.findByNamespaceIdAndSlug(namespaceId, skillSlug).stream()
                .filter(skill -> !BUILTIN_PUBLISHER_ID.equals(skill.getOwnerId()))
                .anyMatch(skill -> !skillVersionRepository
                        .findBySkillIdAndStatus(skill.getId(), SkillVersionStatus.PUBLISHED)
                        .isEmpty());
    }

    private boolean shouldSkipExistingVersion(BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage,
                                              Skill skill,
                                              String version) {
        Optional<SkillVersion> existingVersion = skillVersionRepository.findBySkillIdAndVersion(skill.getId(), version);
        if (existingVersion.isEmpty()) {
            return false;
        }

        SkillVersion skillVersion = existingVersion.get();
        if (skillVersion.getStatus() != SkillVersionStatus.PUBLISHED) {
            log.warn("Skipping built-in skill because the same version is not published [directory={}, skillId={}, version={}, status={}]",
                    skillPackage.directory(), skill.getId(), version, skillVersion.getStatus());
            return true;
        }

        String currentFingerprint = BuiltinSkillFingerprints.fromEntries(skillPackage.entries());
        String existingFingerprint = BuiltinSkillFingerprints.fromFiles(skillFileRepository.findByVersionId(skillVersion.getId()));
        if (currentFingerprint.equals(existingFingerprint)) {
            log.info("Built-in skill version already exists, skipping [directory={}, skillId={}, version={}]",
                    skillPackage.directory(), skill.getId(), version);
        } else {
            log.warn("Skipping built-in skill because same published version has different content [directory={}, skillId={}, version={}]",
                    skillPackage.directory(), skill.getId(), version);
        }
        return true;
    }

    private void publishPackage(BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage,
                                String skillSlug,
                                String version) {
        try {
            SkillPublishService.PublishResult result = skillPublishService.publishFromEntries(
                    GLOBAL_NAMESPACE,
                    skillPackage.entries(),
                    BUILTIN_PUBLISHER_ID,
                    SkillVisibility.PUBLIC,
                    Set.of("SUPER_ADMIN"),
                    false
            );
            log.info("Published built-in skill [directory={}, namespace={}, slug={}, version={}, status={}]",
                    skillPackage.directory(), GLOBAL_NAMESPACE, result.slug(),
                    result.version().getVersion(), result.version().getStatus());
        } catch (RuntimeException exception) {
            ConcurrentVersionState concurrentVersionState =
                    findConcurrentPublishedBuiltInVersionState(skillPackage, skillSlug, version);
            if (concurrentVersionState == ConcurrentVersionState.MATCHING) {
                log.warn("Built-in skill was already published concurrently, skipping [directory={}, namespace={}, slug={}, version={}]",
                        skillPackage.directory(), GLOBAL_NAMESPACE, skillSlug, version);
                return;
            }
            if (concurrentVersionState == ConcurrentVersionState.DIFFERENT) {
                log.warn("Skipping built-in skill because concurrently published same version has different content [directory={}, namespace={}, slug={}, version={}]",
                        skillPackage.directory(), GLOBAL_NAMESPACE, skillSlug, version);
                return;
            }
            throw exception;
        }
    }

    private ConcurrentVersionState findConcurrentPublishedBuiltInVersionState(
            BuiltinSkillPackageLoader.BuiltinSkillPackage skillPackage,
            String skillSlug,
            String version) {
        return namespaceRepository.findBySlug(GLOBAL_NAMESPACE)
                .flatMap(namespace -> skillRepository.findByNamespaceIdAndSlugAndOwnerId(
                        namespace.getId(),
                        skillSlug,
                        BUILTIN_PUBLISHER_ID
                ))
                .flatMap(skill -> skillVersionRepository.findBySkillIdAndVersion(skill.getId(), version))
                .filter(skillVersion -> skillVersion.getStatus() == SkillVersionStatus.PUBLISHED)
                .map(skillVersion -> {
                    String currentFingerprint = BuiltinSkillFingerprints.fromEntries(skillPackage.entries());
                    String existingFingerprint = BuiltinSkillFingerprints.fromFiles(
                            skillFileRepository.findByVersionId(skillVersion.getId()));
                    if (currentFingerprint.equals(existingFingerprint)) {
                        return ConcurrentVersionState.MATCHING;
                    }
                    return ConcurrentVersionState.DIFFERENT;
                })
                .orElse(ConcurrentVersionState.MISSING);
    }

    private enum ConcurrentVersionState {
        MISSING,
        MATCHING,
        DIFFERENT
    }
}
