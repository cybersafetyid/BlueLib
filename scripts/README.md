# BlueLib Release & Version Bump Scripts

Cross-platform scripts to automate version bumping, git commit, tag creation, and remote push.

## Pre-requisite & Safety Rule

Before bumping to a new version (e.g. `0.1.2`), **`CHANGELOG.md` must contain an entry for that version** (e.g. `## [0.1.2]`).

If `CHANGELOG.md` does not have an entry matching the target version, the script will **abort immediately** with an error and will NOT modify files, tag, or push to Git.

---

## Usage

### macOS / Linux / Git Bash
```bash
chmod +x scripts/bump-version.sh
./scripts/bump-version.sh 0.1.2
```

### Windows (PowerShell)
```powershell
.\scripts\bump-version.ps1 0.1.2
```

### Direct Python Execution (All Platforms)
```bash
python scripts/bump-version.py 0.1.2
```

---

## Options

* `--no-push`: Bump files, commit, and tag locally without pushing to `origin`.
  ```bash
  python scripts/bump-version.py 0.1.2 --no-push
  ```
* `--no-tag`: Bump files and commit locally without creating a git tag or pushing.
  ```bash
  python scripts/bump-version.py 0.1.2 --no-tag
  ```

---

## What the Script Updates

When `CHANGELOG.md` validation passes for target version `X.Y.Z`:

1. `gradle.properties` (`bluelib.version`)
2. `bluelib-core/.../BlueLibVersion.kt` (`VERSION`)
3. `README.md` (version references)
4. `docs/getting-started.md` (version references)
5. `sample/build.gradle.kts` (dependency version)
6. `sample/.../SampleUnitTest.kt` (test assertion version string)
7. Creates Git commit: `release: vX.Y.Z`
8. Creates Git annotated tag: `vX.Y.Z`
9. Pushes branch and tag `vX.Y.Z` to `origin`
