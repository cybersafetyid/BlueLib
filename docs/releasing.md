# Releasing

BlueLib publishes to Maven Central through the [Central Portal](https://central.sonatype.com). Releases are
cut from tags, never from a branch: the tag is the only thing that can be reproduced later, and Central
never forgets an artifact.

## One-time setup

1. **Namespace.** `io.github.cybersafetyid` is verified on the Central Portal by proving ownership of the
   `cybersafetyid` GitHub account (GitHub namespaces are verified automatically for the account owner).
2. **PGP key.** Generate a key and publish the public half to a keyserver:

   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
   ```

3. **Repository secrets** in GitHub:

   | Secret | Contents |
   | --- | --- |
   | `SIGNING_IN_MEMORY_KEY` | the ASCII-armored private key (`private-key.asc`) |
   | `SIGNING_IN_MEMORY_KEY_PASSWORD` | the key passphrase |
   | `CENTRAL_PORTAL_TOKEN` | a Central Portal user token (username + password pair, Base64 as documented by Sonatype) |

## Cutting a release

1. **Bump the version in two places** (CI enforces that they agree):

   * `gradle.properties` → `bluelib.version`
   * `bluelib-core/src/main/kotlin/io/github/cybersafetyid/bluelib/BlueLibVersion.kt` → `VERSION`

2. **Update `CHANGELOG.md`** with the release notes.
3. **Verify locally:**

   ```bash
   ./gradlew check
   ./gradlew verifyVersionConsistency -Pbluelib.version=0.2.0
   ./gradlew generateCompatibilityMatrix && git diff --exit-code docs/compatibility-matrix.md
   ```

4. **Tag and push:**

   ```bash
   git tag -s v0.2.0 -m "BlueLib 0.2.0"
   git push origin v0.2.0
   ```

5. **The release workflow** (`.github/workflows/release.yml`) then:

   * asserts `BlueLibVersion.VERSION` matches the tag,
   * runs `check`,
   * publishes signed artifacts to `build/staging-deploy`,
   * zips that directory into `central-bundle.zip`,
   * uploads the bundle to the Central Portal with `publishingType=AUTOMATIC`,
   * attaches the bundle to the GitHub release.

The Portal validates the bundle (POM completeness, signatures, checksums) and then releases it. A failure
there is reported on the Portal's deployment list, and the fix is a new tag — Central artifacts are
immutable.

## What gets published

| Artifact | Contents |
| --- | --- |
| `bluelib-core` | JAR, `-sources.jar`, POM, Gradle module metadata |
| `bluelib-android` | AAR (with consumer rules), `-sources.jar`, POM, module metadata |
| `bluelib` | AAR (with consumer rules), `-sources.jar`, POM, module metadata |
| `bluelib-testing` | JAR, `-sources.jar`, POM, module metadata |

## Local dry run

```bash
./gradlew publishAllPublicationsToStagingRepository
find . -path '*/staging-deploy/*' -name '*.pom' -o -name '*.aar' -o -name '*.jar' | sort
```

With a signing key configured (`-PsigningInMemoryKey=… -PsigningInMemoryKeyPassword=…`) the `.asc`
signatures are produced as well; without one, the staging artifacts are simply unsigned, which is fine for
a local verification and would be rejected by Central.

## Documentation site

The MkDocs site is built by `.github/workflows/ci.yml` (`mkdocs build --strict`) on every push, so a broken
link fails CI. To publish it, enable GitHub Pages with "GitHub Actions" as the source and add a deploy job
that uploads `site/` — the documentation is intentionally not published from the release workflow, so docs
can be fixed without cutting a release.

## Versioning policy

* **0.x** — API may change between minor versions; breaking changes are listed in the changelog with a
  migration note.
* **1.0 and later** — semantic versioning. Removing or changing a public declaration requires a major
  version. `@BlueLibInternal` declarations are exempt: they are public only because Gradle modules cannot
  share `internal` visibility, and they are documented as unstable.
