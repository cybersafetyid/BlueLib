# Releasing

BlueLib publishes to Maven Central through the [Central Portal](https://central.sonatype.com). Releases are
cut from tags, never from a branch: the tag is the only thing that can be reproduced later, and Central
never forgets an artifact.

The whole release runs in `.github/workflows/release.yml`. Everything below the first section is setup you
do once, or once per release.

## One-time setup

### 1. Claim the namespace

On the Central Portal, add the namespace `io.github.cybersafetyid`. GitHub namespaces are verified by
proving ownership of the account, so signing in with GitHub as `cybersafetyid` is enough. Nothing can be
published under a namespace that is not verified, and the verification carries over to every release.

### 2. Create the signing key

Central rejects unsigned artifacts, so a release needs a PGP key. It is a *release* key: it signs
artifacts and nothing else.

```bash
gpg --full-generate-key
```

Take the defaults (RSA and RSA, 4096 bits, no expiry) with a real name and an e-mail you are happy to
publish, then set a long passphrase. Leaving the passphrase empty works too — the build treats an unset
password secret as the empty string, and that is exactly what a key without a passphrase needs — but a
passphrase still protects the exported file at rest.

Publish the public half and export the private half:

```bash
KEY_ID=$(gpg --list-secret-keys --with-colons --keyid-format long <your-email> \
  | awk -F: '/^sec:/{print $5; exit}')

gpg --keyserver keys.openpgp.org --send-keys "$KEY_ID"      # e-mailed confirmation required
gpg --keyserver keyserver.ubuntu.com --send-keys "$KEY_ID"  # reachable immediately

gpg --armor --export-secret-keys "$KEY_ID" > private-key.asc
```

Central verifies the bundle's signatures against the public key it can look up, so the key has to be on a
keyserver before the first release. Both keyservers are sent to on purpose: `keys.openpgp.org` is the one
Sonatype's documentation mentions and it will not serve the key until you confirm the e-mail address,
while the Ubuntu keyserver serves it straight away.

`private-key.asc` is a secret. `.gitignore` already excludes `*.asc` and `/.release-key/` so it cannot be
committed by accident, and it should be deleted once the secrets below are set:

```bash
rm -f private-key.asc
```

Whatever remains — passphrase, exported key, revocation certificate — belongs in a password manager or
similar offline backup **before** the first release. A lost passphrase means the key can never be used
again, and a lost revocation certificate means a compromised key cannot be retired properly. On the
machine that cut v0.1.0 this material lives in `.release-key/`, which `gradle clean` does not touch.

### 3. Put the secrets in GitHub

```bash
gh secret set SIGNING_IN_MEMORY_KEY < private-key.asc   # the complete armored block
gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD            # prompts for the passphrase
gh secret set CENTRAL_PORTAL_TOKEN                      # prompts for the token from step 4
gh secret list
```

`SIGNING_IN_MEMORY_KEY` is the entire block, `-----BEGIN PGP PRIVATE KEY BLOCK-----` through
`-----END PGP PRIVATE KEY BLOCK-----`. Setting it from a *file* rather than pasting it is the difference
between a working release and the truncated-key failure in the troubleshooting section.

If the key has no passphrase, leave `SIGNING_IN_MEMORY_KEY_PASSWORD` unset.

### 4. Create the Portal token

In the Central Portal, open **Account** and press **Generate User Token**. The pair that appears is a
username and password *for the API*, not your login. The API authenticates with the base64 of them joined
by a colon:

```bash
printf 'the-username:the-password' | base64
```

That value is the whole `CENTRAL_PORTAL_TOKEN`: the workflow sends it as
`Authorization: Bearer <value>` to `central.sonatype.com/api/v1/publisher/upload`.

## Rehearse the signing locally

Before a tag exists, do a release-shaped build and check the signatures. This is also the fastest way to
find out that a secret is wrong.

```bash
./gradlew publishAllPublicationsToStagingRepository \
  -Pbluelib.version=0.1.0 \
  -PsigningInMemoryKey="$(cat private-key.asc)" \
  -PsigningInMemoryKeyPassword='the-passphrase'

find build/staging-deploy -name '*.asc' | wc -l     # 18: four modules x (binaries, sources, POM, module) + 2 javadoc jars

gpg --verify \
  build/staging-deploy/io/github/cybersafetyid/bluelib-core/0.1.0/bluelib-core-0.1.0.pom.asc \
  build/staging-deploy/io/github/cybersafetyid/bluelib-core/0.1.0/bluelib-core-0.1.0.pom
```

`Good signature` is what a release needs; everything else is worth fixing before the tag, because a
published version can never be replaced. Without `-PsigningInMemoryKey` the staging artifacts are simply
unsigned — fine for a local check, rejected by Central.

## Cutting a release

1. **Bump the version in two places** (CI enforces that they agree):

   * `gradle.properties` → `bluelib.version`
   * `bluelib-core/src/main/kotlin/io/github/cybersafetyid/bluelib/BlueLibVersion.kt` → `VERSION`

2. **Update `CHANGELOG.md`** with the release notes.
3. **Verify locally:**

   ```bash
   ./gradlew check
   ./gradlew verifyVersionConsistency -Pbluelib.version=0.1.0
   ./gradlew generateCompatibilityMatrix && git diff --exit-code docs/compatibility-matrix.md
   ```

4. **Tag and push:**

   ```bash
   git tag -a v0.1.0 -m "BlueLib 0.1.0"     # -s if git has a signing key configured
   git push origin v0.1.0
   ```

5. **The release workflow** then:

   * fails immediately if a repository secret is missing, naming it,
   * asserts `BlueLibVersion.VERSION` matches the tag,
   * runs `check`,
   * publishes signed artifacts to `build/staging-deploy`,
   * zips that directory into `central-bundle.zip`,
   * uploads the bundle to the Central Portal with `publishingType=USER_MANAGED`,
   * polls the deployment and stops when it is `VALIDATED`, or fails with the validator's own errors,
   * attaches the bundle to the GitHub release.

`USER_MANAGED` is deliberate for a first release: the bundle is validated and then *waits*. The run is
annotated with a link to the Portal's deployment list, where publishing v0.1.0 is one click and where the
validation report is readable before anything becomes public.

The status poll matters either way — `publishingType` only decides whether a bundle that *passed*
validation is published without a human, so without the poll a rejected bundle would leave a green run and
nothing on Maven Central. Once the release metadata is known to pass validation, switch `publishingType`
to `AUTOMATIC` for hands-off releases; the poll then ends on `PUBLISHED` instead of `VALIDATED`.

After the deployment is `PUBLISHED`, the artifacts appear on Maven Central under
`io.github.cybersafetyid`; Central's own sync to all mirrors takes a little while longer.

## What gets published

| Artifact | Contents |
| --- | --- |
| `bluelib-core` | JAR, `-sources.jar`, `-javadoc.jar` (Dokka), POM, Gradle module metadata |
| `bluelib-testing` | JAR, `-sources.jar`, `-javadoc.jar` (Dokka), POM, Gradle module metadata |
| `bluelib-android` | AAR (with consumer rules), `-sources.jar`, POM, module metadata |
| `bluelib` | AAR (with consumer rules), `-sources.jar`, POM, module metadata |

The javadoc jars exist because the Central validator rejects JVM modules without one — the first v0.1.0
deployment failed with "Javadocs must be provided but not found in entries" for `bluelib-core` and
`bluelib-testing`. Android AARs are exempt, so those two modules ship none. Because the JVM modules are
100% Kotlin, the jars are built from Dokka's rendered HTML rather than the (empty) `javadoc` task.

## Troubleshooting the release

**The workflow stops before anything is built, naming a secret.** That is the intended behaviour: an unset
GitHub secret arrives as an empty string, and "SIGNING_IN_MEMORY_KEY is not set" is a better error than
anything the signing plugin can produce.

**`Cannot perform signing task ':bluelib-core:signReleasePublication' because it has no configured
signatory`.** Gradle 9.5 silently ignores an in-memory key when the password is `null` — no warning, no
exception, and the resulting error names the task rather than the password. The build passes an empty
string when the password secret is unset, so this now points at the key instead: check that
`SIGNING_IN_MEMORY_KEY` exists and is not empty. It is verified behaviour of Gradle 9.5.0, not a
misconfiguration of yours.

**`Could not read PGP secret key`.** Either the passphrase is wrong, or the armored block was truncated
(a pasted secret is the usual culprit). Set both secrets from files:

```bash
gh secret set SIGNING_IN_MEMORY_KEY < private-key.asc
```

**`Javadocs must be provided but not found in entries`** for a JVM module. Central requires a
`-javadoc.jar` from every JVM module (Android AARs are exempt); the convention plugin builds it from
Dokka for exactly this reason, so this error means the jar was not attached — check that the module
really goes through `bluelib.publish` and that `artifact(tasks.named("javadocJar"))` still runs for JVM
modules.

**A GitHub Actions job fails in about fifteen seconds with `Failed to find package 'tools'`.** That is
`android-actions/setup-android@v3`, which installs the `tools` package that the SDK repository no longer
serves. Both workflows use `@v4`, which installs `platform-tools` only. Related naming detail: API 37 is a
minor-versioned platform, so its package is `platforms;android-37.0` and there is no `build-tools;37.0.0`
— the build uses `build-tools;36.1.0` and lets AGP download anything else it needs.

## Documentation site

The MkDocs site is published to **<https://cybersafetyid.github.io/BlueLib/>** by
`.github/workflows/docs.yml`, which builds and deploys on every push to `main` that touches `docs/**`,
`mkdocs.yml`, or the workflow itself — documentation can therefore be fixed without cutting a release.
Run it by hand with:

```bash
gh workflow run docs.yml
```

GitHub Pages is enabled for this repository with **GitHub Actions** as the source (`build_type:
workflow`), which is all the deploy job needs. A fork has to enable Pages the same way before the job can
deploy.

The build is `mkdocs build --strict`, and `.github/workflows/ci.yml` runs the same strict build on every
push, so a broken link or a missing nav entry fails in the pull request rather than on the site. Two
things fail that build often enough to be worth knowing: a nav label containing a colon has to be quoted
(`- "Android 5 to 17: what changed for Bluetooth": research/android-17-bluetooth.md`), and every file
listed under `nav:` has to exist.

To preview locally:

```bash
python3 -m venv .venv
.venv/bin/pip install -r docs/requirements.txt    # Scripts/pip on Windows
.venv/bin/mkdocs serve
```

## Versioning policy

* **0.x** — API may change between minor versions; breaking changes are listed in the changelog with a
  migration note.
* **1.0 and later** — semantic versioning. Removing or changing a public declaration requires a major
  version. `@BlueLibInternal` declarations are exempt: they are public only because Gradle modules cannot
  share `internal` visibility, and they are documented as unstable.
