# Built-in Skills Initialization Design

## Context

New SkillHub deployments currently start without a guaranteed installable skill in the registry.
Users must first understand publishing or find an external package before they can verify search,
detail, download, and CLI installation flows.

This design adds a small, built-in example skill that is bundled with the Java service and published
automatically to `@global` during application startup.

The MVP example skill is `skillhub-hello`. It is intentionally generic and does not encode an
AgentGuard-specific product decision. Future official skills can reuse the same initialization
mechanism.

## Goals

- Bundle one or more directory-form skills in the Java service resources.
- Enable built-in skill initialization by default.
- Publish built-in skills to the fixed `global` namespace.
- Publish as `PUBLIC` and `PUBLISHED` so the skill is immediately searchable and installable.
- Use a fixed system publisher, `builtin-skill-publisher`, for owner and audit traceability.
- Reuse the existing `SkillPublishService.publishFromEntries(...)` pipeline.
- Keep initialization idempotent across repeated container deployments.
- Treat published versions as immutable: same version with changed content is skipped with a warning.
- Avoid new seed state tables and distributed locks in the MVP.
- Keep initialization failures non-fatal to application startup.
- Document `skillhub-hello` as an out-of-the-box verification skill.

## Non-Goals

- No new database table for seed state.
- No Redis or database distributed lock.
- No zip-based built-in skill packages.
- No configurable target namespace; built-in skills always publish to `global`.
- No label creation or binding for `skillhub-hello`.
- No landing page or frontend recommendation slot.
- No direct SQL/JPA insertion into `skill`, `skill_version`, or `skill_file`.
- No changes to ordinary publish, review, promotion, or lifecycle behavior.

## Key Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Source location | Java service classpath resources | The runtime artifact always contains the built-in package |
| Directory | `server/skillhub-app/src/main/resources/builtin-skills/` | Spring Boot resource packaging is predictable |
| First built-in skill | `skillhub-hello` | Generic verification skill, not product-specific |
| Startup default | Enabled | Supports out-of-the-box discovery and installation |
| Target namespace | Fixed `global` | Built-in examples are platform-level public skills |
| Publication state | `PUBLIC + PUBLISHED` | Immediately searchable and installable |
| Publisher | `builtin-skill-publisher` | Stable owner and audit source |
| Version mutability | Same version is never overwritten | Published versions remain reproducible |
| Seed state table | None | Existing skill/version/file records are enough for MVP idempotency |
| Distributed lock | None | Conflict-tolerant startup is sufficient for a small built-in set |
| Labels | None | MVP validates the built-in publish mechanism only |
| Failure behavior | Log and continue | A sample skill must not make the service unavailable |
| Package format | Directory only | Easier review and classpath loading |

## Resource Layout

Built-in skills live under the `skillhub-app` resource tree:

```text
server/skillhub-app/src/main/resources/builtin-skills/
  skillhub-hello/
    SKILL.md
    README.md
```

Each direct child directory under `builtin-skills/` is treated as one skill package.

Rules:

- The directory must contain root-level `SKILL.md`.
- File paths are relative to the skill directory.
- Files are converted into `PackageEntry` values.
- Files outside the skill directory are ignored.
- Zip packages are not supported in the MVP.

Suggested `skillhub-hello/SKILL.md`:

```markdown
---
name: skillhub-hello
description: A built-in example skill that verifies SkillHub discovery and installation.
version: 1.0.0
---
# SkillHub Hello

This skill is bundled with SkillHub as a minimal example for validating discovery and installation.
```

Published coordinate:

```text
@global/skillhub-hello
```

ClawHub canonical slug:

```text
skillhub-hello
```

## Configuration

Add one configuration property:

```yaml
skillhub:
  builtin-skills:
    enabled: true
```

Environment override:

```bash
SKILLHUB_BUILTIN_SKILLS_ENABLED=false
```

The MVP does not expose a namespace or locations property. The implementation uses the fixed
classpath location:

```text
classpath*:builtin-skills/*/SKILL.md
```

## Backend Design

### Components

Add the following app-layer bootstrap components:

- `BuiltinSkillProperties`
- `BuiltinSkillPackageLoader`
- `BuiltinSkillInitializer`

Responsibilities:

| Component | Responsibility |
|-----------|----------------|
| `BuiltinSkillProperties` | Bind `skillhub.builtin-skills.enabled` |
| `BuiltinSkillPackageLoader` | Read classpath skill directories and construct `PackageEntry` values |
| `BuiltinSkillInitializer` | Ensure publisher, evaluate idempotency, and call the publish pipeline |

These classes belong in:

```text
server/skillhub-app/src/main/java/com/iflytek/skillhub/bootstrap/
```

### Publish Pipeline

The initializer must call the existing domain publish service:

```java
skillPublishService.publishFromEntries(
        "global",
        entries,
        "builtin-skill-publisher",
        SkillVisibility.PUBLIC,
        Set.of("SUPER_ADMIN"),
        false
);
```

This preserves existing behavior for:

- package policy validation
- `SKILL.md` parsing
- slug generation
- `Skill` creation or reuse
- `SkillVersion` creation
- `PUBLISHED` state assignment
- `latestVersionId` updates
- `SkillFile` records
- object storage writes
- bundle zip creation
- `SkillPublishedEvent`
- after-commit search index rebuild

The initializer must not create skill/version/file rows directly.
Passing `false` for warning confirmation means built-in packages with validation warnings are treated
as package quality failures and skipped instead of being silently accepted.

### System Publisher

The initializer ensures this system user exists:

```text
userId: builtin-skill-publisher
displayName: SkillHub Built-in Publisher
email: builtin-skill-publisher@example.invalid
```

Requirements:

- Create `UserAccount` if missing.
- Ensure the user is an `OWNER` member of `@global`.
- Do not create a local login credential.
- Do not require a persisted platform role binding.
- Pass `Set.of("SUPER_ADMIN")` only for the publish call to reuse auto-publish behavior.

## Idempotency And Version Policy

### Content Fingerprint

The initializer computes a package fingerprint from current classpath resources:

1. Sort entries by normalized path.
2. Hash each file content with SHA-256.
3. Build a canonical stream of `path + fileSha256`.
4. Hash that stream to produce the package fingerprint.

For an existing published version, the initializer recomputes the same fingerprint from `skill_file`:

1. Query the target `SkillVersion`.
2. Query its `SkillFile` rows.
3. Sort by `filePath`.
4. Build the canonical stream from `filePath + sha256`.
5. Hash that stream.

No new persistence field is added.

### Startup Rules

For each built-in skill:

| Existing state | Action |
|----------------|--------|
| Skill does not exist | Publish |
| Skill exists, same version does not exist | Publish new version |
| Same version is `PUBLISHED` and fingerprint matches | Skip |
| Same version is `PUBLISHED` and fingerprint differs | Warn and skip |
| Same version exists but is not `PUBLISHED` | Warn and skip |

Same-version content changes must bump the version in `SKILL.md`. The initializer must never
overwrite a published version.

### Concurrent Startup

The MVP does not use a distributed lock.

If multiple application instances start at the same time:

- each instance performs the idempotency check;
- one instance may publish first;
- later instances may hit an existing-version conflict;
- conflict handling should re-read the existing version and skip when a valid published version is present;
- conflicts must be logged but must not fail application startup.

## Failure Behavior

Built-in skill initialization is best-effort.

Rules:

- Failure in one built-in skill does not prevent other built-in skills from being processed.
- Any initialization failure is logged at error level.
- Same-version fingerprint drift is logged at warning level.
- Same-version matching content is logged at info level.
- Successful publishing is logged at info level.
- Exceptions are contained inside the initializer and do not abort Spring Boot startup.

Log context should include:

- skill directory
- resolved slug
- resolved version
- namespace `global`
- action
- error message when applicable

## Object Storage And Search

Classpath resources are only the source for initialization. Published files still go through the
configured object storage backend:

- LocalFile
- MinIO
- S3

The initializer does not write object storage directly.

Search index updates also remain event-driven. The publish service emits `SkillPublishedEvent`, and
the existing search listener rebuilds the search document after transaction commit.

## User Experience

The MVP does not add frontend UI.

After startup, users can discover and install the built-in skill through existing flows:

- search for `skillhub-hello`;
- open the skill detail page;
- copy the existing install command;
- install with ClawHub/OpenClaw.

Expected command:

```bash
npx clawhub install skillhub-hello --registry <your-skillhub-url>
```

Because labels and recommendation slots are out of scope, this design does not guarantee permanent
homepage prominence for `skillhub-hello`.

## Documentation

Update the user-facing docs to mention the built-in verification skill:

- `README.md`
- `docs/openclaw-integration.md`
- `docs/openclaw-integration-en.md`

Recommended example:

```bash
npx clawhub search skillhub-hello --registry <your-skillhub-url>
npx clawhub install skillhub-hello --registry <your-skillhub-url>
```

The docs should explain:

- `skillhub-hello` is bundled with SkillHub;
- it validates registry search and installation;
- operators can disable initialization with `SKILLHUB_BUILTIN_SKILLS_ENABLED=false`.

## Testing Strategy

### Unit Tests

Add backend tests for:

- `enabled=false` skips all publishing.
- first startup publishes `skillhub-hello`;
- same version and same fingerprint skips;
- same version and different fingerprint warns and skips;
- same version in a non-`PUBLISHED` state warns and skips;
- publish exceptions are swallowed after logging;
- missing system publisher is created;
- missing `@global` membership is created;
- loader reads classpath directories into stable `PackageEntry` order;
- loader reports a directory missing `SKILL.md`.

### Local Validation

Run:

```bash
make test-backend-app
```

If implementation touches runtime packaging or staging startup behavior, also run:

```bash
make staging
```

### Manual Validation

After local startup:

```bash
curl "http://localhost:8080/api/web/skills?q=skillhub-hello"
npx clawhub search skillhub-hello --registry http://localhost:8080
npx clawhub install skillhub-hello --registry http://localhost:8080
```

## Future Extensions

Potential follow-up work:

- external filesystem source locations;
- zip package support;
- seed state table;
- distributed lock;
- official label binding;
- landing page official/recommended slot;
- AgentGuard or other official skills built on the same mechanism.

These are outside the MVP.
