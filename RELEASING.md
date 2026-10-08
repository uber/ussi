# Releasing USSI

## Maven coordinates

Published artifacts use **`com.uber.ussi:ussi`**.

## CI and local builds

- **Pull requests and main:** Bazel tests (`.github/workflows/tests.yml`).
- **Releases:** Gradle publishes to Maven Central (`.github/workflows/publish-release.yml`).

Both build systems share the same `src/main/java` and `src/test/java` trees. Bazel remains supported for day-to-day development; Gradle is the supported release path for Uber open-source JVM libraries.

Gradle requires JDK 21 or later. On Uber-managed machines that inject an internal Artifactory `init.gradle`, use a clean `GRADLE_USER_HOME` (for example `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test`) so dependencies resolve from Maven Central only.

## Cut a release

1. Set `VERSION_NAME` in `gradle.properties` to the release version (no `-SNAPSHOT` suffix).
2. Update `maven_coordinates` in `BUILD.bazel` to the same version.
3. Merge to `main`.
4. Create and push an annotated tag `vX.Y.Z` matching `VERSION_NAME`.
5. Confirm the **Publish release to Maven Central** workflow succeeds on GitHub Actions.
6. Confirm the artifact on [Maven Central](https://central.sonatype.com/artifact/com.uber.ussi/ussi).

If tag `vX.Y.Z` already exists on an older commit, move the tag after the publish workflow is on `main`, or cut the next patch version instead.

After the release is published, bump `VERSION_NAME` in `gradle.properties` and `maven_coordinates` in `BUILD.bazel` to the next `-SNAPSHOT` development version.
