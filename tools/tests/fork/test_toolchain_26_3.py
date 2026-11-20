# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""The 26.3 target's Java major, data version and resource pack format agree with what fixes them: the catalog, the
mod's metadata and mixin configs, the shared loader convention, and the pinned game's own client jar."""

import json
import re

import pytest

from rtx_tools import prism

from .target import REPO_ROOT, cached_client_version_json, fork_registry

REGISTRY = fork_registry()
FABRIC = REGISTRY.get("fabric")
MINECRAFT_RESOURCES = REPO_ROOT / REGISTRY.minecraft_module / "main" / "resources"
FABRIC_METADATA = REPO_ROOT / FABRIC.module / "src" / "main" / "resources" / FABRIC.metadata_file


def test_the_catalog_targets_java_25():
    # Minecraft 26.3 runs on Java 25 and Prism refuses any other major for it (compatibleJavaMajors).
    assert REGISTRY.java_target == 25


def test_every_mixin_config_has_the_targets_compatibility_level():
    configs = sorted(MINECRAFT_RESOURCES.glob("*.mixins.json"))
    assert [path.name for path in configs] == [
        "rtx-remix-renderer.distanthorizons.mixins.json",
        "rtx-remix-renderer.entityculling.mixins.json",
        "rtx-remix-renderer.mixins.json",
    ]
    for path in configs:
        level = json.loads(path.read_text(encoding="utf-8"))["compatibilityLevel"]
        assert level == f"JAVA_{REGISTRY.java_target}", path.name


def test_fabric_metadata_takes_its_pins_from_the_catalog():
    metadata = json.loads(FABRIC_METADATA.read_text(encoding="utf-8"))

    assert metadata["id"] == REGISTRY.mod_id
    assert metadata["depends"]["minecraft"] == "${minecraftVersion}"
    assert metadata["depends"]["fabricloader"] == "${loaderVersionRange}"
    assert metadata["depends"]["fabric-api"] == "${apiVersionRange}"
    assert metadata["depends"]["java"] in ("${javaTarget}", ">=${javaTarget}", f">={REGISTRY.java_target}")
    for mixin_config in metadata["mixins"]:
        assert (MINECRAFT_RESOURCES / mixin_config).is_file(), mixin_config


def test_the_loader_convention_compiles_for_the_catalogs_java_target():
    convention = (REPO_ROOT / "gradle" / "loader-module.gradle").read_text(encoding="utf-8")

    assert re.search(r"JavaLanguageVersion\.of\(javaTarget\)", convention)
    assert re.search(r"options\.release\s*=\s*javaTarget", convention)


def test_options_data_version_is_the_one_of_the_pinned_game():
    version = cached_client_version_json(REGISTRY.minecraft_version)
    if version is None:
        pytest.skip("Loom has not cached the pinned Minecraft client jar on this machine")

    # The tools stamp options.txt with the catalog's dataVersion, as the installer does.
    assert version["world_version"] == REGISTRY.minecraft_data_version


def test_resource_pack_format_is_the_one_of_the_pinned_game():
    version = cached_client_version_json(REGISTRY.minecraft_version)
    if version is None:
        pytest.skip("Loom has not cached the pinned Minecraft client jar on this machine")

    # The format the tools declare in the staged art pack and the build declares in the Materials zip.
    assert version["pack_version"]["resource_major"] == REGISTRY.resource_pack_format


def test_the_materials_zip_declares_the_targets_resource_pack_format():
    # The release's Materials zip and the art pack the tools stage are the same pack, so both declare the catalog's
    # format; build.gradle reads it from the selected target and leaves a target without one as the pack's authors set.
    build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")

    assert "minecraft.resourcePackFormat" in build
    for statement in (
        "meta.pack.remove('pack_format')",
        "meta.pack.remove('supported_formats')",
        "meta.pack.min_format = materialsPackFormat",
        "meta.pack.max_format = materialsPackFormat",
    ):
        assert statement in build, statement

    # The format is a task input, so a catalog bump rewrites the pack.mcmeta the Materials zip carries.
    meta = re.search(r"(?ms)^def materialsPackMetaTask = tasks\.register\('materialsPackMeta'\) \{(.+?)^\}", build)
    assert meta is not None, "build.gradle registers no materialsPackMeta task"
    assert "inputs.property('resourcePackFormat', materialsPackFormat)" in meta.group(1)
    assert "declareMaterialsPackFormat(meta)" in meta.group(1)


def test_the_dev_client_runs_with_the_installers_recommended_jvm_arguments():
    # AGENTS.md: the unconditional vmArgs of the 26.3 Fabric run and RECOMMENDED_JVM_ARGS stay identical.
    build = (REPO_ROOT / FABRIC.module / "build.gradle").read_text(encoding="utf-8")
    statement = re.search(r'^\s*vmArgs\s+((?:"[^"]*"\s*,\s*)*"[^"]*")', build, re.MULTILINE)
    assert statement is not None, "versions/26.3/fabric/build.gradle has no vmArgs statement"

    assert re.findall(r'"([^"]*)"', statement.group(1)) == prism.recommended_jvm_args()
