# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

""".github/scripts/validate-project-state.ps1 on the fork's catalog, run under Windows PowerShell 5.1 as the build,
check and release workflows run it.

The catalog's builtVersions names what this checkout builds, tests and releases: 26.3. NVIDIA's 1.21.1 targets stay
declared and validated, but the CI matrix and the release target come only from built versions."""

import copy
import json
import os
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the validator")

VALIDATOR = util.PROJECT_DIR / ".github" / "scripts" / "validate-project-state.ps1"
CATALOG = json.loads((util.PROJECT_DIR / "project-state.json").read_text(encoding="utf-8"))


def validate(
    validator: Path, tmp_path: Path, *arguments: str
) -> tuple[subprocess.CompletedProcess[str], dict[str, str]]:
    output = tmp_path / "github-output.txt"
    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(validator),
            "-GitHubOutput",
            str(output),
            *arguments,
        ],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    values = {}
    if output.exists():
        for line in output.read_text(encoding="utf-8-sig").splitlines():
            key, _, value = line.partition("=")
            values[key] = value
    return result, values


def test_the_ci_matrix_is_the_26_3_fabric_target_alone(tmp_path: Path):
    result, outputs = validate(VALIDATOR, tmp_path)

    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(outputs["matrix"]) == {
        "include": [
            {
                "minecraftVersion": "26.3",
                "loader": "fabric",
                "javaTarget": 25,
                "supportTier": "primary",
                "loaderSupportTier": "active",
                "buildTask": ":versions:26.3:fabric:build",
                "testTask": ":versions:26.3:fabric:test",
                "packageTask": ":versions:26.3:fabric:package",
            }
        ]
    }
    # The release workflow's empty minecraft_version input selects the default, and its Java comes from the target.
    assert outputs["minecraftVersion"] == "26.3"
    assert outputs["defaultMinecraftVersion"] == "26.3"
    assert outputs["javaTarget"] == "25"
    assert outputs["modVersion"] == CATALOG["mod"]["version"]
    # The carried 1.21.1 targets are still validated.
    assert outputs["versionMatrix"] == "1.21.1:fabric,forge,neoforge;26.3:fabric"


def test_a_carried_version_is_never_a_release_target(tmp_path: Path):
    result, _ = validate(VALIDATOR, tmp_path, "-MinecraftVersion", "1.21.1")

    assert result.returncode != 0
    assert "Minecraft 1.21.1 is carried from upstream and not built in this checkout" in result.stderr


Checkout = Callable[[dict[str, Any]], Path]


@pytest.fixture
def checkout(tmp_path: Path) -> Checkout:
    """A checkout holding the validator, `state` as its project-state.json, and the directories the state names."""

    def make(state: dict[str, Any]) -> Path:
        root = tmp_path / "checkout"
        scripts = root / ".github" / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy2(VALIDATOR, scripts / VALIDATOR.name)
        (root / "project-state.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
        for version in state["versions"].values():
            (root / version["minecraft"]["module"]).mkdir(parents=True, exist_ok=True)
            core = root / version.get("rendererCore", "renderer-core")
            core.mkdir(parents=True, exist_ok=True)
            for loader in version["loaders"].values():
                module = root / loader["module"]
                module.mkdir(parents=True, exist_ok=True)
                (module / "build.gradle").write_text("", encoding="utf-8")
        return scripts / VALIDATOR.name

    return make


def test_without_built_versions_every_declared_target_is_in_the_matrix(checkout: Checkout, tmp_path: Path):
    state = copy.deepcopy(CATALOG)
    del state["builtVersions"]

    result, outputs = validate(checkout(state), tmp_path)

    assert result.returncode == 0, result.stdout + result.stderr
    rows = [(row["minecraftVersion"], row["loader"]) for row in json.loads(outputs["matrix"])["include"]]
    assert rows == [("1.21.1", "fabric"), ("1.21.1", "forge"), ("1.21.1", "neoforge"), ("26.3", "fabric")]


def broken(change: Callable[[dict[str, Any]], None]) -> dict[str, Any]:
    state = copy.deepcopy(CATALOG)
    change(state)
    return state


def unset_default(state: dict[str, Any]) -> None:
    state["builtVersions"] = ["1.21.1"]


def undeclared(state: dict[str, Any]) -> None:
    state["builtVersions"] = ["26.3", "9.9"]


def listed_twice(state: dict[str, Any]) -> None:
    state["builtVersions"] = ["26.3", "26.3"]


def not_a_flag(state: dict[str, Any]) -> None:
    state["versions"]["26.3"]["loaders"]["fabric"]["builtinResourcePackListed"] = "no"


def bad_pack_format(state: dict[str, Any]) -> None:
    state["versions"]["26.3"]["minecraft"]["resourcePackFormat"] = 0


def escaping_core(state: dict[str, Any]) -> None:
    state["versions"]["26.3"]["rendererCore"] = "../renderer-core"


@pytest.mark.parametrize(
    ("change", "message"),
    [
        (unset_default, "defaultMinecraftVersion '26.3' must be listed in builtVersions"),
        (undeclared, "builtVersions entry '9.9' is not a declared version"),
        (listed_twice, "builtVersions lists '26.3' twice"),
        (not_a_flag, "builtinResourcePackListed must be a boolean"),
        (bad_pack_format, "resourcePackFormat must be a positive integer"),
        (escaping_core, "rendererCore must be a repository-relative path"),
    ],
    ids=["default-not-built", "undeclared", "twice", "listed-flag", "pack-format", "renderer-core"],
)
def test_the_fork_fields_are_validated(
    checkout: Checkout, tmp_path: Path, change: Callable[[dict[str, Any]], None], message: str
):
    result, _ = validate(checkout(broken(change)), tmp_path)

    assert result.returncode != 0
    assert message in result.stderr


def test_a_missing_renderer_core_is_refused(checkout: Checkout, tmp_path: Path):
    validator = checkout(CATALOG)
    shutil.rmtree(validator.parents[2] / CATALOG["versions"]["26.3"]["rendererCore"])

    result, _ = validate(validator, tmp_path)

    assert result.returncode != 0
    assert "Renderer core 'versions/26.3/renderer-core' of Minecraft 26.3 does not exist" in result.stderr
