# Releasing USSI

## Maven coordinates

Published artifacts use **`com.uber.ussi:ussi`**.

## CI and local builds

- **Pull requests and main:** Bazel tests (`.github/workflows/tests.yml`).
- **Releases:** [Release to Maven Central](.github/workflows/release.yml) on version tags.

Both build systems share the same `src/main/java` and `src/test/java` trees. Bazel remains supported for day-to-day development; Gradle is the supported release path for Uber open-source JVM libraries.

Gradle requires JDK 21 or later. On Uber-managed machines that inject an internal Artifactory `init.gradle`, use a clean `GRADLE_USER_HOME` (for example `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test`) so dependencies resolve from Maven Central only.

## Release process

Creating and pushing a release tag to `uber/ussi` automatically triggers the
[Release to Maven Central workflow](.github/workflows/release.yml). Merging a PR
or pushing a commit to `main` does not trigger a release.

The workflow signs, uploads, and releases `com.uber.ussi:ussi` to Maven Central.
The Gradle Maven Publish plugin's `publishToMavenCentral` task handles both upload
and release because `mavenCentralAutomaticPublishing=true` is set in
`gradle.properties`. After publishing, the workflow creates a GitHub Release for
the tag using the matching `CHANGELOG.md` section as its release notes. The tagged
commit should already have passed CI on `main`.

## Prepare and publish a release

1. Set `VERSION_NAME` in `gradle.properties` to the release version without
   `-SNAPSHOT`, for example `0.1.0`.
2. Set `maven_coordinates` on `//:src_main` in `BUILD.bazel` to the same version.
3. Add a matching `# 0.1.0` section with release notes to `CHANGELOG.md`.
4. Commit with `Prepare for release 0.1.0.` and merge the PR into `main`.
5. Tag the release commit and push the tag:

   ```sh
   git switch main
   git pull --ff-only origin main
   git tag -a v0.1.0 -m "Version 0.1.0"
   git push origin v0.1.0
   ```

6. Follow **Release to Maven Central** in GitHub Actions. The workflow validates
   the tag, version, Bazel coordinates, and changelog before publishing, then
   creates the GitHub Release. Maven Central may take additional time to make a
   released version available.
7. Prepare the next development version, such as `0.2.0-SNAPSHOT`, in
   `gradle.properties` and `BUILD.bazel`, and submit it in a separate PR.

Release tags use the form `vX.Y.Z`. The tag version (without the `v` prefix) must
exactly match `VERSION_NAME`, and snapshot versions are rejected. The workflow
does not change versions, create commits, or push tags.

## Retry a release

Open the failed tag-push run of **Release to Maven Central** in GitHub Actions
and select **Re-run failed jobs**. Versions already released to Maven Central
cannot be overwritten; use a new version for changed artifacts.

## Repository secrets

These GitHub Actions secrets are configured at the Uber organization level for
`uber/ussi`:

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USER_TOKEN_USERNAME` | Central Portal user token username for `com.uber.ussi`. |
| `MAVEN_CENTRAL_USER_TOKEN_PASSWORD` | Central Portal user token password. |
| `PGP_SIGNING_KEY_PRIVATE_KEY` | ASCII-armored private signing key. |
| `PGP_PRIVATE_KEY_ID` | Signing subkey ID, if required. Optional. |
| `PGP_SIGNING_KEY_PASSWORD` | Private key passphrase. Optional for an unencrypted key. |
