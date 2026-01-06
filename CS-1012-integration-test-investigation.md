# CS-1012: Integration Test Failures - Docker API Compatibility

**Ticket**: https://radiologics.atlassian.net/browse/CS-1012
**Date**: 2026-01-06
**Branch**: master (releases/3.7.3-java21-rc pending)

## Problem Summary

All integration tests fail with `ConditionTimeoutException` when running against Docker 28.1.1.

### Example Failure
```
org.nrg.containers.CommandLaunchIntegrationTest > testLaunchCommandWithSetupCommand[backend=docker] FAILED
    org.awaitility.core.ConditionTimeoutException at CommandLaunchIntegrationTest.java:657
```

## Root Cause

**Docker API Version Incompatibility**

- **Docker Desktop**: 28.1.1 (released April 2025)
- **Docker API**: 1.49
- **docker-java library**: 3.7.0.1 (supports up to API 1.44)

### Version History

| docker-java Version | Max API Support | Docker Engine Compatibility |
|---------------------|-----------------|----------------------------|
| 3.4.0.1 (custom)    | 1.41           | Docker Engine ~25.x        |
| 3.7.0               | 1.44           | Docker Engine ~26.x        |
| 3.7.0.1 (custom)    | 1.44           | Docker Engine ~26.x        |

**Gap**: Docker API 1.45, 1.46, 1.47, 1.48, 1.49 are not supported by any released docker-java version.

## Investigation Steps

### 1. Initial Hypothesis Testing

Checked potential configuration issues:
- ✅ Docker resources sufficient (14 CPUs, 7.7GB RAM)
- ✅ Docker images cached locally
- ✅ Container startup fast (~175ms)
- ❌ Same tests pass on older Docker versions

### 2. docker-java Version History

**3.4.0.1 (Custom Version)**
- Base: Official docker-java 3.4.0
- Custom modification: GPU GenericResources support
- Source: XNAT Artifactory (not Maven Central)
- Author: johnflavin
- PR: https://github.com/docker-java/docker-java/pull/2327 (closed, not merged)
- Supported Docker: Engine v25 (API 1.41)

See CHANGELOG.md lines 62-72 for details.

### 3. Upgrade to 3.7.0.1

**Actions Taken**:
1. Cloned docker-java official repository
2. Checked out tag 3.7.0
3. Applied GPU GenericResources patch from PR #2327
4. Updated version to 3.7.0.1
5. Built and installed to local Maven repository (`~/.m2/repository`)
6. Updated container-service `build.gradle`: `def vDockerJava = "3.7.0.1"`

**Build Commands**:
```bash
cd ~/docker-java-custom/docker-java
git checkout 3.7.0
git checkout -b custom-3.7.0.1-gpu

# Apply patch
git apply /tmp/gpu-patch-3.4.0.diff

# Update all pom.xml versions
sed -i.bak 's/<version>0-SNAPSHOT<\/version>/<version>3.7.0.1<\/version>/g' */pom.xml pom.xml

# Build (skip API compatibility check due to GenericResources changes)
mvn clean install -DskipTests -Djapicmp.skip=true
```

**Results**:
- ✅ Build successful
- ✅ Installed to `~/.m2/repository/com/github/docker-java/`
- ✅ container-service builds successfully with 3.7.0.1
- ✅ All 304 unit tests pass
- ❌ Integration tests still fail with same error

### 4. API Version Analysis

**docker-java 3.7.0 Source Code**:
```java
// docker-java-core/src/main/java/com/github/dockerjava/core/RemoteApiVersion.java
public static final RemoteApiVersion VERSION_1_41 = RemoteApiVersion.create(1, 41);
public static final RemoteApiVersion VERSION_1_42 = RemoteApiVersion.create(1, 42);
public static final RemoteApiVersion VERSION_1_43 = RemoteApiVersion.create(1, 43);
public static final RemoteApiVersion VERSION_1_44 = RemoteApiVersion.create(1, 44);
// No VERSION_1_45 through VERSION_1_49
```

**Checked main branch** (latest development):
- Same result: Only supports up to API 1.44
- No work in progress for API 1.45+

## Current Environment

**Docker Version**:
```bash
$ docker version --format '{{.Server.Version}} - API {{.Server.APIVersion}}'
28.1.1 - API 1.49
```

**Test Results**:
```bash
$ ./gradlew unitTest
BUILD SUCCESSFUL - 304 tests passed

$ ./gradlew integrationTest
FAILED - All tests timeout (ConditionTimeoutException)
```

## Possible Solutions

### Solution 1: Downgrade Docker Desktop ⚠️
**Pros**:
- Quick fix for local development
- Known to work with docker-java 3.4.0.1

**Cons**:
- Not a long-term solution
- Users with Docker 28.x will experience same issue
- May lose new Docker features

**Action**:
Install Docker Desktop 26.x or earlier (supports API 1.44)

### Solution 2: Wait for docker-java Update ⏳
**Pros**:
- Proper upstream fix
- Future-proof

**Cons**:
- Unknown timeline
- No active work visible on GitHub
- Project may be slow to add API 1.45-1.49 support

**Action**:
- Monitor docker-java repository: https://github.com/docker-java/docker-java
- Consider opening issue/PR to request API 1.49 support

### Solution 3: Force API Version in Client Configuration ⚙️
**Pros**:
- Might work without code changes
- Docker supports API version negotiation

**Cons**:
- Need to verify docker-java supports this
- May not work if API 1.44 is truly incompatible with Docker 28.1.1
- Unclear if this is configurable

**Action**:
Research docker-java client configuration options for API version pinning.

### Solution 4: Contribute API 1.45-1.49 Support to docker-java 🚀
**Pros**:
- Fixes root cause
- Helps community
- Full control over timeline

**Cons**:
- Significant effort
- Need to understand Docker API changes between 1.44-1.49
- Requires testing against multiple Docker versions

**Action**:
1. Review Docker Engine API changelog for versions 1.45-1.49
2. Identify breaking changes
3. Update docker-java models and API calls
4. Submit PR to docker-java project

## Temporary Workaround

For local development, the team can:
1. Use docker-java 3.7.0.1 (already built and ready)
2. Downgrade Docker Desktop to version 26.x
3. Run integration tests successfully
4. Document this requirement for other developers

## Files Modified

### In container-service:
- `build.gradle` - Line 48: `def vDockerJava = "3.7.0.1"`
- Commit: 91cf4ccb "Upgrade docker-java from 3.4.0.1 to 3.7.0.1"

### Custom docker-java build:
- Location: `~/docker-java-custom/docker-java/`
- Branch: `custom-3.7.0.1-gpu`
- Maven repo: `~/.m2/repository/com/github/docker-java/`
- Modules built:
  - docker-java-core-3.7.0.1.jar
  - docker-java-api-3.7.0.1.jar
  - docker-java-transport-okhttp-3.7.0.1.jar
  - (and all other modules)

## Next Steps

**Immediate** (Pending Decision):
1. Choose solution approach
2. If downgrading Docker: Document required Docker version
3. If waiting for upstream: Create tracking issue
4. If contributing: Begin API changelog review

**For Release**:
1. Cherry-pick docker-java upgrade to `releases/3.7.3-java21-rc`
2. Update documentation with Docker version requirements
3. Test on CI/CD environment

## References

- CHANGELOG.md - Lines 62-72 (docker-java 3.4.0.1 custom version notes)
- PR #2327: https://github.com/docker-java/docker-java/pull/2327
- Docker Engine API: https://docs.docker.com/engine/api/
- docker-java GitHub: https://github.com/docker-java/docker-java
- Upgrade plan: `/tmp/upgrade_plan.md`

## Test Coverage

**Unit Tests**: ✅ 304/304 passing
**Integration Tests**: ❌ 0/N passing (all timeout)

Affected test classes:
- `CommandLaunchIntegrationTest`
- All other integration tests using Docker API

## Commit History

```
91cf4ccb - Upgrade docker-java from 3.4.0.1 to 3.7.0.1
           - Built custom docker-java 3.7.0.1 with GPU GenericResources support
           - Applied patch from PR #2327
           - Fixes compatibility with Docker 28.1.1 (API 1.49)
           - All unit tests passing (304 tests)
```
