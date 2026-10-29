# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import os
import re
import unittest
import zipfile
from pathlib import Path

from rtx_tools import prism
from rtx_tools.benchmark import runner, util

REPO_ROOT = util.PROJECT_DIR


def gradle_mod_version() -> str:
    for line in (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("gradle.properties has no mod_version")


def gradle_dependency_version(group: str, artifact: str) -> str:
    text = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
    match = re.search(rf'"{re.escape(group)}:{re.escape(artifact)}:([^\"]+)"', text)
    if not match:
        raise AssertionError(f"build.gradle has no {group}:{artifact} dependency")
    return match.group(1)


def powershell_literal(path: Path, variable: str) -> str:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"\${re.escape(variable)}\s*=\s*'([^']+)'", text)
    if not match:
        raise AssertionError(f"{path} has no ${variable} string assignment")
    return match.group(1)


def minecraft_client_jar() -> Path | None:
    """The client jar that Loom caches for the Minecraft version build.gradle pins, or None before a Gradle build has
    fetched it."""
    gradle_home = os.environ.get("GRADLE_USER_HOME")
    home = Path(gradle_home) if gradle_home else Path.home() / ".gradle"
    jar = (
        home / "caches" / "fabric-loom" / gradle_dependency_version("com.mojang", "minecraft") / "minecraft-client.jar"
    )
    return jar if jar.is_file() else None


def java_major_in(path: Path, pattern: str) -> int:
    """The one Java major that `pattern`, which captures it, finds in the file at `path`."""
    return java_major_in_text(path.read_text(encoding="utf-8"), pattern, str(path))


def java_major_in_text(text: str, pattern: str, where: str) -> int:
    """The one Java major that `pattern`, which captures it, finds in `text`; `where` names the text in the failure."""
    found = {int(major) for major in re.findall(pattern, text, re.MULTILINE)}
    if len(found) != 1:
        raise AssertionError(f"{where} should name one Java major through {pattern!r}, but names {sorted(found)}")
    return found.pop()


def workflow_job_text(path: Path, job: str) -> str:
    """The YAML of the job named `job` in the GitHub Actions workflow at `path`: from its key, at the indent of the
    entries under `jobs:`, up to the next job's key."""
    text = path.read_text(encoding="utf-8")
    job_block = rf"^  {re.escape(job)}:[ \t]*\n.*?(?=^  [A-Za-z0-9_-]+:[ \t]*$|\Z)"
    match = re.search(job_block, text, re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"{path} has no job {job!r}")
    return match.group(0)


def java_majors() -> dict[str, int]:
    """The Java major of every place that fixes one: the installer, the developer tools, the Gradle toolchain and
    compiler release, the mod's metadata, the JDK of the CI job that builds the mod, and the mixin compatibility
    level. The workflow's other jobs build the Java 8 legacy interop and the Forge 1.12.2 bootstrap and pin their own
    JDK on purpose."""
    gradle = REPO_ROOT / "build.gradle"
    resources = REPO_ROOT / "src" / "main" / "resources"
    fabric_java = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))["depends"]["java"]
    metadata_major = re.fullmatch(r">=(\d+)", fabric_java)
    if metadata_major is None:
        raise AssertionError(f"fabric.mod.json depends.java is {fabric_java!r}, not '>=<major>'")
    return {
        "installer.ps1 GAME_JAVA_MAJOR": java_major_in(
            REPO_ROOT / "packaging" / "installer.ps1", r"^\$script:GAME_JAVA_MAJOR\s*=\s*(\d+)\s*$"
        ),
        "prism.py GAME_JAVA_MAJOR": prism.GAME_JAVA_MAJOR,
        "build.gradle JavaLanguageVersion": java_major_in(gradle, r"JavaLanguageVersion\.of\((\d+)\)"),
        "build.gradle options.release": java_major_in(gradle, r"options\.release\s*=\s*(\d+)"),
        "fabric.mod.json depends.java": int(metadata_major.group(1)),
        "build.yml build job java-version": java_major_in_text(
            workflow_job_text(REPO_ROOT / ".github" / "workflows" / "build.yml", "build"),
            r'^\s*java-version:\s*"?(\d+)"?\s*$',
            "the build job of build.yml",
        ),
        "mixins.json compatibilityLevel": java_major_in(
            resources / "rtx-remix-renderer.mixins.json", r'"compatibilityLevel":\s*"JAVA_(\d+)"'
        ),
    }


class VersionConsistencyTests(unittest.TestCase):
    def test_java_major_is_the_same_wherever_one_is_fixed(self):
        majors = java_majors()

        self.assertEqual(1, len(set(majors.values())), f"these disagree on the Java major: {majors}")

    def test_packaging_version_defaults_match_gradle(self):
        expected = gradle_mod_version()
        self.assertEqual(
            expected,
            powershell_literal(REPO_ROOT / "packaging" / "installer.ps1", "script:APP_VERSION"),
        )

    def test_runtime_dependency_versions_match_gradle(self):
        expected = {
            "minecraft": gradle_dependency_version("com.mojang", "minecraft"),
            "fabric_loader": gradle_dependency_version("net.fabricmc", "fabric-loader"),
            "fabric_api": gradle_dependency_version("net.fabricmc.fabric-api", "fabric-api"),
        }
        installer = REPO_ROOT / "packaging" / "installer.ps1"

        for path, variables in (
            (
                installer,
                {
                    "minecraft": "script:MC_VERSION",
                    "fabric_loader": "script:FABRIC_LOADER_VER",
                    "fabric_api": "script:FABRIC_API_VERSION",
                },
            ),
        ):
            with self.subTest(path=path):
                for component, variable in variables.items():
                    self.assertEqual(expected[component], powershell_literal(path, variable))

    def test_options_data_version_is_the_one_of_the_pinned_game(self):
        jar = minecraft_client_jar()
        if jar is None:
            self.skipTest("Loom has not cached the pinned Minecraft client jar on this machine")
        with zipfile.ZipFile(jar) as archive:
            world_version = json.loads(archive.read("version.json"))["world_version"]

        self.assertEqual(world_version, runner.OPTIONS_DATA_VERSION)

    def test_resource_pack_major_is_the_one_of_the_pinned_game(self):
        jar = minecraft_client_jar()
        if jar is None:
            self.skipTest("Loom has not cached the pinned Minecraft client jar on this machine")
        with zipfile.ZipFile(jar) as archive:
            pack_major = json.loads(archive.read("version.json"))["pack_version"]["resource_major"]

        # What the tools declare in the staged art pack, read from this jar or taken from the fallback constant.
        self.assertEqual(pack_major, runner.FALLBACK_RESOURCE_PACK_MAJOR)
        self.assertEqual(pack_major, runner.game_resource_pack_major())

    def test_runtime_code_does_not_hardcode_mod_version(self):
        benchmark_controller = (
            REPO_ROOT
            / "src"
            / "main"
            / "java"
            / "com"
            / "nvidia"
            / "rtxremix"
            / "minecraft"
            / "benchmark"
            / "BenchmarkController.java"
        ).read_text(encoding="utf-8")
        mod_entrypoint = (
            REPO_ROOT / "src" / "main" / "java" / "com" / "nvidia" / "rtxremix" / "minecraft" / "RtxRemixMod.java"
        ).read_text(encoding="utf-8")
        self.assertNotRegex(benchmark_controller, r'modVersion",\s*"\d+\.\d+\.\d+"')
        self.assertNotRegex(mod_entrypoint, r"Renderer v\d+\.\d+\.\d+")

    def test_fabric_metadata_matches_repository_contract(self):
        metadata = json.loads(
            (REPO_ROOT / "src" / "main" / "resources" / "fabric.mod.json").read_text(encoding="utf-8")
        )
        minecraft_version = gradle_dependency_version("com.mojang", "minecraft")
        fabric_loader_version = gradle_dependency_version("net.fabricmc", "fabric-loader")
        fabric_api_version = gradle_dependency_version("net.fabricmc.fabric-api", "fabric-api")

        self.assertEqual("Apache-2.0", metadata["license"])
        self.assertEqual(minecraft_version, metadata["depends"]["minecraft"])
        self.assertEqual(f">={fabric_loader_version}", metadata["depends"]["fabricloader"])
        self.assertEqual(f">={fabric_api_version}", metadata["depends"]["fabric-api"])
        self.assertNotIn("fabric-api", metadata.get("suggests", {}))

        icon = metadata.get("icon")
        if icon:
            self.assertTrue((REPO_ROOT / "src" / "main" / "resources" / icon).is_file())

        sources = metadata.get("contact", {}).get("sources", "")
        self.assertNotIn("rtxremix-java", sources)

    def test_readme_license_matches_repository_license(self):
        license_text = (REPO_ROOT / "LICENSE").read_text(encoding="utf-8")
        readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn("Apache License", license_text)
        self.assertIn("Apache-2.0", readme)
        self.assertNotRegex(readme, r"(?m)^MIT\s+[\u2014-]\s+See LICENSE file$")


if __name__ == "__main__":
    unittest.main()
