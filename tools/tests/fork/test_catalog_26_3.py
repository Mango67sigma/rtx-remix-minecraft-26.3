# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""The fork's catalog fields (builtVersions, rendererCore, minecraft.resourcePackFormat, builtinResourcePackListed)
as the tools read, validate and report them."""

import json
from pathlib import Path

import pytest

from rtx_tools import loader_registry, target_status, version_catalog
from rtx_tools.loader_registry import ManifestError

from .target import FORK_TARGET, REPO_ROOT, fork_registry

UPSTREAM_TARGET = "1.21.1"


def real_manifest() -> dict:
    return json.loads((REPO_ROOT / loader_registry.MANIFEST_NAME).read_text(encoding="utf-8-sig"))


def write_manifest(directory: Path, value: dict) -> Path:
    (directory / loader_registry.MANIFEST_NAME).write_text(json.dumps(value), encoding="utf-8")
    return directory


def test_the_fork_target_is_the_default_and_the_only_built_version(monkeypatch):
    monkeypatch.delenv("RTXREMIX_MINECRAFT_VERSION")

    registry = loader_registry.load_registry()

    assert registry.default_version == FORK_TARGET
    assert registry.minecraft_version == FORK_TARGET
    assert registry.built_versions == (FORK_TARGET,)
    assert registry.built is True


def test_the_fork_target_pins_26_3_on_fabric():
    registry = fork_registry()
    fabric = registry.get("fabric")

    assert registry.ids == ("fabric",)
    assert registry.default_id == "fabric"
    assert registry.java_target == 25
    assert registry.minecraft_data_version == 5023
    assert registry.resource_pack_format == 97
    assert registry.renderer_core == "versions/26.3/renderer-core"
    assert registry.mod_version == "0.1.4"
    assert fabric.version == "0.20.1"
    assert fabric.api_version == "0.165.0+26.3"
    assert fabric.gradle_plugin_id == "net.fabricmc.fabric-loom"
    assert fabric.package_source_task == "jar"
    assert fabric.builtin_resource_pack == "fabric"
    assert fabric.builtin_resource_pack_listed is False
    assert fabric.installed_jar == "rtx-remix-renderer-fabric.jar"


def test_a_version_carried_from_upstream_still_loads_and_says_it_is_not_built_here():
    registry = loader_registry.load_registry(minecraft_version=UPSTREAM_TARGET)

    assert registry.built is False
    assert registry.built_versions == (FORK_TARGET,)
    assert registry.renderer_core == loader_registry.DEFAULT_RENDERER_CORE
    assert registry.resource_pack_format is None
    assert all(loader.builtin_resource_pack_listed for loader in registry.loaders)


def test_every_versions_renderer_core_exists():
    for version in fork_registry().available_versions:
        registry = loader_registry.load_registry(minecraft_version=version)
        assert (REPO_ROOT / registry.renderer_core / "src").is_dir(), registry.renderer_core


def test_an_absent_built_versions_builds_every_declared_version(tmp_path):
    value = real_manifest()
    del value["builtVersions"]
    registry = loader_registry.load_registry(write_manifest(tmp_path, value), minecraft_version=UPSTREAM_TARGET)

    assert set(registry.built_versions) == set(value["versions"])
    assert registry.built is True


@pytest.mark.parametrize(
    ("built", "message"),
    [
        ([], "at least one version"),
        ("26.3", "array of strings"),
        ([26.3], "non-empty string"),
        (["26.3", "26.3"], "must not repeat"),
        (["26.3", "1.20.1"], "undeclared versions: 1.20.1"),
        (["1.21.1"], "must be listed in builtVersions"),
    ],
)
def test_built_versions_are_validated(tmp_path, built, message):
    value = real_manifest()
    value["builtVersions"] = built

    with pytest.raises(ManifestError, match=message):
        loader_registry.load_registry(write_manifest(tmp_path, value), minecraft_version=FORK_TARGET)


@pytest.mark.parametrize(
    ("edit", "message"),
    [
        (lambda version: version.__setitem__("rendererCore", "../renderer-core"), "repository-relative"),
        (lambda version: version.__setitem__("rendererCore", ""), "non-empty string"),
        (lambda version: version["minecraft"].__setitem__("resourcePackFormat", 0), "positive integer"),
        (lambda version: version["minecraft"].__setitem__("resourcePackFormat", True), "positive integer"),
        (lambda version: version["minecraft"].__setitem__("resourcePackFormat", "97"), "positive integer"),
        (
            lambda version: version["loaders"]["fabric"].__setitem__("builtinResourcePackListed", "false"),
            "true or false",
        ),
    ],
)
def test_the_fork_fields_are_validated(tmp_path, edit, message):
    value = real_manifest()
    edit(value["versions"][FORK_TARGET])

    # Every declared version is validated on any load, whichever is selected.
    with pytest.raises(ManifestError, match=message):
        loader_registry.load_registry(write_manifest(tmp_path, value), minecraft_version=UPSTREAM_TARGET)


def test_upstreams_entry_needs_none_of_the_fork_fields():
    upstream = real_manifest()["versions"][UPSTREAM_TARGET]

    assert "rendererCore" not in upstream
    assert "resourcePackFormat" not in upstream["minecraft"]
    assert all("builtinResourcePackListed" not in loader for loader in upstream["loaders"].values())


def test_versions_reports_which_versions_this_checkout_builds(capsys):
    report = version_catalog.report(REPO_ROOT)
    entries = {entry["version"]: entry for entry in report["versions"]}

    assert report["defaultMinecraftVersion"] == FORK_TARGET
    assert report["builtVersions"] == [FORK_TARGET]
    assert entries[FORK_TARGET]["resourcePackFormat"] == 97
    assert entries[FORK_TARGET]["rendererCore"] == "versions/26.3/renderer-core"
    assert "resourcePackFormat" not in entries[UPSTREAM_TARGET]
    assert "rendererCore" not in entries[UPSTREAM_TARGET]

    version_catalog.print_report(report)
    lines = capsys.readouterr().out.splitlines()
    assert any(line.startswith("  Minecraft 26.3 (default): primary; built here;") for line in lines), lines
    assert any(line.startswith("  Minecraft 1.21.1: primary; carried as source, not built here;") for line in lines), (
        lines
    )


def unbuilt_checkout(tmp_path: Path) -> Path:
    value = real_manifest()
    return write_manifest(tmp_path, value)


def test_target_status_of_a_version_not_built_here_runs_no_build(tmp_path, monkeypatch):
    project = unbuilt_checkout(tmp_path)
    registry = loader_registry.load_registry(project, minecraft_version=UPSTREAM_TARGET)
    runs = []
    monkeypatch.setattr(target_status, "_run", lambda command, project_dir: runs.append(command))

    validation = target_status.run_validation(project, registry, "build")

    assert runs == []
    assert {loader: result["state"] for loader, result in validation["build"].items()} == {
        "fabric": "not-built-here",
        "forge": "not-built-here",
        "neoforge": "not-built-here",
    }
    assert all(result["required"] is False for result in validation["build"].values())


def test_target_status_reports_whether_the_target_is_built_here(tmp_path, capsys):
    project = unbuilt_checkout(tmp_path)

    carried = target_status.report(project, minecraft_version=UPSTREAM_TARGET)
    built = target_status.report(project, minecraft_version=FORK_TARGET)

    assert carried["target"]["built"] is False
    assert {loader["build"]["state"] for loader in carried["loaders"]} == {"not-built-here"}
    assert {loader["live"]["state"] for loader in carried["loaders"]} == {"not-built-here"}
    assert built["target"]["built"] is True
    assert [loader["build"]["state"] for loader in built["loaders"]] == ["not-run"]
    assert [loader["live"]["state"] for loader in built["loaders"]] == ["required"]

    target_status.print_report(carried)
    assert "built:    no;" in capsys.readouterr().out
