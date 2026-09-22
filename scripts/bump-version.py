#!/usr/bin/env python3
import sys
import re
import subprocess
from pathlib import Path

def run_command(cmd, check=True):
    print(f"--> Executing: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(f"❌ Error running command: {' '.join(cmd)}")
        print(f"Stdout:\n{result.stdout}")
        print(f"Stderr:\n{result.stderr}")
        sys.exit(result.returncode)
    return result

def main():
    if len(sys.argv) < 2:
        print("Usage: python scripts/bump-version.py <new_version> [--no-push] [--no-tag]")
        print("Example: python scripts/bump-version.py 0.1.2")
        sys.exit(1)

    raw_version = sys.argv[1].strip()
    target_version = raw_version.lstrip("v")

    no_push = "--no-push" in sys.argv
    no_tag = "--no-tag" in sys.argv

    # Validate version format (e.g. 0.1.2 or 1.0.0-rc1)
    if not re.match(r"^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$", target_version):
        print(f"❌ Invalid version format: '{raw_version}'. Must be semantic versioning (e.g. 0.1.2).")
        sys.exit(1)

    root_dir = Path(__file__).resolve().parent.parent
    changelog_path = root_dir / "CHANGELOG.md"
    gradle_props_path = root_dir / "gradle.properties"
    bluelib_version_kt_path = root_dir / "bluelib-core" / "src" / "main" / "kotlin" / "io" / "github" / "cybersafetyid" / "bluelib" / "BlueLibVersion.kt"
    readme_path = root_dir / "README.md"
    getting_started_path = root_dir / "docs" / "getting-started.md"
    sample_build_path = root_dir / "sample" / "build.gradle.kts"
    sample_test_path = root_dir / "sample" / "src" / "test" / "kotlin" / "io" / "github" / "cybersafetyid" / "bluelib" / "sample" / "SampleUnitTest.kt"

    # Step 1: Validate CHANGELOG.md entry exists for target version
    if not changelog_path.exists():
        print(f"❌ ERROR: {changelog_path} does not exist!")
        sys.exit(1)

    changelog_text = changelog_path.read_text(encoding="utf-8")
    changelog_pattern = re.compile(rf"^##\s*\[{re.escape(target_version)}\]", re.MULTILINE)

    if not changelog_pattern.search(changelog_text):
        print(f"❌ ERROR: CHANGELOG.md has no section for version '{target_version}'!")
        print(f"   Expected section header: '## [{target_version}]'")
        print("   Please update CHANGELOG.md with release notes before bumping the version.")
        sys.exit(1)

    print(f"✅ Verified CHANGELOG.md entry for version {target_version}")

    # Step 2: Extract current version
    if not gradle_props_path.exists():
        print(f"❌ ERROR: {gradle_props_path} does not exist!")
        sys.exit(1)

    gradle_props_text = gradle_props_path.read_text(encoding="utf-8")
    match = re.search(r"^bluelib\.version=(.+)$", gradle_props_text, re.MULTILINE)
    if not match:
        print(f"❌ ERROR: Could not find bluelib.version in {gradle_props_path}")
        sys.exit(1)

    current_version = match.group(1).strip()
    print(f"ℹ️ Current version: {current_version} -> Target version: {target_version}")

    if current_version == target_version:
        print(f"⚠️ Version is already {target_version} in gradle.properties.")

    # Step 3: Check git status for tag existence
    tag_name = f"v{target_version}"
    tag_check = run_command(["git", "tag", "-l", tag_name], check=False)
    if tag_name in tag_check.stdout.splitlines():
        print(f"❌ ERROR: Git tag '{tag_name}' already exists!")
        sys.exit(1)

    # Step 4: Update version across project files
    files_to_update = []

    # Update gradle.properties
    new_gradle_props = re.sub(
        r"^bluelib\.version=.+$",
        f"bluelib.version={target_version}",
        gradle_props_text,
        flags=re.MULTILINE
    )
    if new_gradle_props != gradle_props_text:
        gradle_props_path.write_text(new_gradle_props, encoding="utf-8")
        files_to_update.append(gradle_props_path)

    # Update BlueLibVersion.kt
    if bluelib_version_kt_path.exists():
        kt_text = bluelib_version_kt_path.read_text(encoding="utf-8")
        new_kt_text = re.sub(
            r'public const val VERSION: String = "[^"]+"',
            f'public const val VERSION: String = "{target_version}"',
            kt_text
        )
        if new_kt_text != kt_text:
            bluelib_version_kt_path.write_text(new_kt_text, encoding="utf-8")
            files_to_update.append(bluelib_version_kt_path)

    # Update README.md
    if readme_path.exists():
        readme_text = readme_path.read_text(encoding="utf-8")
        new_readme_text = readme_text.replace(current_version, target_version)
        if new_readme_text != readme_text:
            readme_path.write_text(new_readme_text, encoding="utf-8")
            files_to_update.append(readme_path)

    # Update docs/getting-started.md
    if getting_started_path.exists():
        gs_text = getting_started_path.read_text(encoding="utf-8")
        new_gs_text = gs_text.replace(current_version, target_version)
        if new_gs_text != gs_text:
            getting_started_path.write_text(new_gs_text, encoding="utf-8")
            files_to_update.append(getting_started_path)

    # Update sample/build.gradle.kts
    if sample_build_path.exists():
        sb_text = sample_build_path.read_text(encoding="utf-8")
        new_sb_text = sb_text.replace(current_version, target_version)
        if new_sb_text != sb_text:
            sample_build_path.write_text(new_sb_text, encoding="utf-8")
            files_to_update.append(sample_build_path)

    # Update SampleUnitTest.kt
    if sample_test_path.exists():
        st_text = sample_test_path.read_text(encoding="utf-8")
        new_st_text = st_text.replace(current_version, target_version)
        if new_st_text != st_text:
            sample_test_path.write_text(new_st_text, encoding="utf-8")
            files_to_update.append(sample_test_path)

    print(f"✅ Updated {len(files_to_update)} files to version {target_version}:")
    for f in files_to_update:
        print(f"   - {f.relative_to(root_dir)}")

    # Step 5: Git commit, tag, and push
    rel_paths = [str(f.relative_to(root_dir)) for f in files_to_update]
    run_command(["git", "add"] + rel_paths)

    # Commit if there are changes staged
    status_res = run_command(["git", "status", "--porcelain"], check=False)
    if status_res.stdout.strip():
        run_command(["git", "commit", "-m", f"release: v{target_version}"])
        print(f"✅ Created commit: 'release: v{target_version}'")
    else:
        print("ℹ️ No file changes to commit.")

    # Tagging
    if not no_tag:
        run_command(["git", "tag", "-a", tag_name, "-m", f"BlueLib {target_version}"])
        print(f"✅ Created annotated git tag: '{tag_name}'")

        # Pushing
        if not no_push:
            print(f"🚀 Pushing branch and tag '{tag_name}' to origin...")
            run_command(["git", "push", "origin", "HEAD"])
            run_command(["git", "push", "origin", tag_name])
            print("🎉 Successfully pushed commit and tag to origin!")
        else:
            print("ℹ️ Skipping push (--no-push specified).")
    else:
        print("ℹ️ Skipping tag and push (--no-tag specified).")

if __name__ == "__main__":
    main()
