# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""What packaging/installer.ps1 and packaging/migrate_resource_packs.ps1 do for the fork's Minecraft 26.3 release,
through the checkout's own project-state.json, run under Windows PowerShell 5.1 as packaging/install.bat runs them.

A 26.3 release bundle stages the whole catalog, which also declares NVIDIA's 1.21.1 targets, beside
mods/fabric/rtx-remix-renderer-fabric-mc26.3-<build>.jar; the installer offers 26.3 alone. The target's Java, data
version, loader pins and installed names all come from the catalog. 26.3 lists no Fabric pack in options.txt (Fabric
API keeps mod resources in a hidden pack above vanilla), so the catalog marks the pack unlisted, and the Materials pack
goes just above vanilla. NVIDIA's own installer tests cover the 1.21.1 behaviour of the same code."""

import json
import os
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from rtx_tools import loader_registry
from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

PACKAGING = util.PROJECT_DIR / "packaging"
INSTALLER = PACKAGING / "installer.ps1"
MIGRATE = PACKAGING / "migrate_resource_packs.ps1"
TARGET = loader_registry.load_registry(minecraft_version="26.3")
FABRIC = TARGET.get("fabric")
BUILD_VERSION = f"{TARGET.mod_version}-202611201200-abc1234"
PACKAGED_JAR = f"{TARGET.mod_id}-fabric-mc{TARGET.minecraft_version}-{BUILD_VERSION}.jar"
# What earlier fork releases installed: the packaged name, versioned, with no loader or Minecraft version in it.
EARLIER_FORK_JARS = ["rtx-remix-renderer-0.1.3-202611011200-1234abc.jar", "rtx-remix-renderer-0.1.2-win64.jar"]
PACK = "RTX-Remix-Materials-0.61.0.zip"

# Loads the installer's constants and functions without its entry point and runs one step. -Dist is the folder the
# bundle was extracted to; a step that reads a bundle points the installer at it as a release does. A menu fails the
# step, and a thrown step prints "Thrown: <message>".
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Action, [string]$Dist, [string]$McDir)
$ProgressPreference = 'SilentlyContinue'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
function Invoke-Menu { param([string]$Title) throw "Asked: $Title" }
function Confirm-YesNo { param([string]$Prompt) throw "Asked: $Prompt" }
function Start-Sleep { }
function Use-Bundle {
    $script:SCRIPT_DIR = $Dist
    $env:RTXREMIX_PROJECT_STATE = Join-Path $Dist 'project-state.json'
    Initialize-LoaderCatalog
}
try {
    switch ($Action) {
        'Catalog' {
            Set-MinecraftVersion -VersionId '26.3'
            $fabric = Get-LoaderProvider -ModLoader Fabric
            ConvertTo-Json -Compress -InputObject ([ordered]@{
                appVersion = $script:APP_VERSION
                minecraft = $script:MC_VERSION
                javaTarget = $script:JAVA_TARGET
                dataVersion = $script:MC_DATA_VERSION
                jdkFolder = Split-Path -Leaf $script:MANAGED_JDK_DIR
                loaders = @($script:LOADER_CATALOG.Keys)
                versionId = $fabric.VersionId
                profileKey = $fabric.MicrosoftProfileKey
                installedJar = $fabric.InstalledJar
                builtInPack = $fabric.BuiltInResourcePack
                builtInPackListed = $fabric.BuiltInResourcePackListed
                loaderVersion = $fabric.LoaderVersion
                apiVersion = $fabric.Companion.Version
                prismUid = $fabric.PrismUid
            })
        }
        'Bundle' {
            Use-Bundle
            ConvertTo-Json -Compress -InputObject ([ordered]@{
                selected = $script:MC_VERSION
                available = @($script:AVAILABLE_MINECRAFT_VERSIONS)
                jar = Split-Path -Leaf (Get-DistModJar -ModLoader Fabric)
            })
        }
        'LoaderChoice' {
            Set-MinecraftVersion -VersionId '26.3'
            Write-Host "Loader: $(Select-ModLoader -Mode Install -Launcher Prism)"
        }
        'ArtPack' {
            Use-Bundle
            $pack = (Get-LoaderProvider -ModLoader Fabric).BuiltInResourcePack
            Write-Host "Returned: $(Install-ArtPack -McDir $McDir -BuiltInResourcePack $pack)"
        }
        'InstallMod' {
            Use-Bundle
            Install-ModFiles -McDir $McDir -ModLoader Fabric
        }
        'Final' {
            Set-MinecraftVersion -VersionId '26.3'
            Write-FinalInstructions -Launcher Prism -PrismDataDir 'C:\Prism' -ModLoader Fabric -Unattended -World
        }
        default { throw "Unknown action: $Action" }
    }
} catch {
    Write-Host "Thrown: $($_.Exception.Message)"
}
"""

Run = Callable[..., str]


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    """A 26.3 release bundle as stageReleaseArchive lays it out: the whole catalog, the installer's scripts, the one
    packaged Fabric JAR under mods/fabric/, and the Materials pack."""
    path = tmp_path / "dist"
    (path / "mods" / "fabric").mkdir(parents=True)
    (path / "mods" / "fabric" / PACKAGED_JAR).write_bytes(b"26.3 jar")
    (path / "resourcepacks").mkdir()
    (path / "resourcepacks" / PACK).write_bytes(b"PK\x05\x06" + bytes(18))
    shutil.copy2(util.PROJECT_DIR / "project-state.json", path / "project-state.json")
    shutil.copy2(MIGRATE, path / MIGRATE.name)
    return path


@pytest.fixture
def mc_dir(tmp_path: Path) -> Path:
    path = tmp_path / ".minecraft"
    (path / "mods").mkdir(parents=True)
    return path


@pytest.fixture
def run(tmp_path: Path, dist: Path, mc_dir: Path) -> Run:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    def invoke(action: str) -> str:
        command = [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Action",
            action,
            "-Dist",
            str(dist),
            "-McDir",
            str(mc_dir),
        ]
        environment = {key: value for key, value in os.environ.items() if key.upper() != "PSMODULEPATH"}
        result = subprocess.run(
            command, capture_output=True, encoding="utf-8", errors="replace", check=False, env=environment
        )
        assert result.returncode == 0, result.stdout + result.stderr
        assert not result.stderr, result.stderr
        assert "Thrown:" not in result.stdout, result.stdout
        return result.stdout

    return invoke


def last_json(output: str) -> Any:
    return json.loads(output.strip().splitlines()[-1])


def resource_packs(*names: str) -> str:
    return "resourcePacks:" + json.dumps(list(names), separators=(",", ":"))


def test_the_26_3_target_comes_from_the_catalog(run: Run):
    catalog = last_json(run("Catalog"))

    assert catalog == {
        "appVersion": TARGET.mod_version,
        "minecraft": "26.3",
        "javaTarget": 25,
        "dataVersion": TARGET.minecraft_data_version,
        "jdkFolder": "jdk-25",
        "loaders": ["fabric"],
        "versionId": f"fabric-loader-{FABRIC.version}-26.3",
        "profileKey": "fabric-loader-26.3",
        "installedJar": "rtx-remix-renderer-fabric.jar",
        "builtInPack": "fabric",
        "builtInPackListed": False,
        "loaderVersion": FABRIC.version,
        "apiVersion": FABRIC.api_version,
        "prismUid": "net.fabricmc.fabric-loader",
    }
    assert TARGET.minecraft_data_version == 5023
    assert TARGET.mod_version == "0.1.3"


def test_a_26_3_bundle_offers_26_3_alone_although_the_catalog_declares_1_21_1(run: Run):
    assert "1.21.1" in loader_registry.load_registry(minecraft_version="26.3").available_versions

    bundle = last_json(run("Bundle"))

    assert bundle == {"selected": "26.3", "available": ["26.3"], "jar": PACKAGED_JAR}


def test_a_target_with_one_loader_does_not_ask_for_it(run: Run):
    assert "Loader: Fabric" in run("LoaderChoice")


def test_the_mod_replaces_what_earlier_fork_releases_installed(run: Run, mc_dir: Path):
    mods = mc_dir / "mods"
    for name in EARLIER_FORK_JARS:
        (mods / name).write_bytes(b"old")
    (mods / "fabric-api-0.165.0+26.3.jar").write_bytes(b"api")
    (mods / "another-mod-1.0.jar").write_bytes(b"mine")
    (mc_dir / "options.txt").write_bytes(
        (
            "ao:true\r\n" + resource_packs("vanilla", "fabric", "file/RtxRemix-LabPBR", "file/Faithful.zip") + "\r\n"
        ).encode()
    )

    output = run("InstallMod")

    assert sorted(path.name for path in mods.iterdir()) == sorted(
        ["rtx-remix-renderer-fabric.jar", "fabric-api-0.165.0+26.3.jar", "another-mod-1.0.jar"]
    )
    assert (mods / "rtx-remix-renderer-fabric.jar").read_bytes() == b"26.3 jar"
    for name in EARLIER_FORK_JARS:
        assert f"Replacing old mod JAR: {name}" in output
    # 26.3 lists no Fabric pack: the migration drops the 1.21.1 entry and the retired baseline's, and lists nothing
    # between vanilla and the player's packs.
    assert (mc_dir / "options.txt").read_bytes().decode() == (
        "ao:true\r\n" + resource_packs("vanilla", "file/Faithful.zip") + "\r\n"
    )


def test_a_new_26_3_instance_gets_the_pack_just_above_vanilla_at_the_data_version(run: Run, mc_dir: Path):
    output = run("ArtPack")

    assert "Returned: Installed" in output
    assert (mc_dir / "options.txt").read_bytes().decode() == (
        f"version:{TARGET.minecraft_data_version}\r\n{resource_packs('vanilla', f'file/{PACK}')}\r\n"
    )


def test_the_pack_goes_below_the_players_packs_and_a_stale_fabric_entry_goes(run: Run, mc_dir: Path):
    (mc_dir / "options.txt").write_bytes(
        ("version:5023\nao:true\n" + resource_packs("vanilla", "fabric", "file/Faithful.zip") + "\n").encode()
    )

    assert "Returned: Installed" in run("ArtPack")

    assert (mc_dir / "options.txt").read_bytes().decode() == (
        "version:5023\nao:true\n" + resource_packs("vanilla", f"file/{PACK}", "file/Faithful.zip") + "\n"
    )


def test_an_upgraded_pack_keeps_its_place_and_drops_a_stale_fabric_entry(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / "RTX-Remix-Materials-0.58.0.zip").write_bytes(b"old")
    (mc_dir / "options.txt").write_bytes(
        (
            resource_packs("vanilla", "fabric", "file/RTX-Remix-Materials-0.58.0.zip", "file/Faithful.zip") + "\r\n"
        ).encode()
    )

    assert "Returned: Updated" in run("ArtPack")

    assert (mc_dir / "options.txt").read_bytes().decode() == (
        resource_packs("vanilla", f"file/{PACK}", "file/Faithful.zip") + "\r\n"
    )


def test_the_closing_steps_say_26_3_upgrades_the_1_21_1_world(run: Run):
    output = run("Final")

    assert "Open Singleplayer and choose NVIDIA RTX Demo." in output
    assert "The first time, Minecraft upgrades it from 1.21.1: answer its backup question, then join." in output


@pytest.mark.parametrize(
    ("version", "expected"),
    [("26.3", ["vanilla", "file/Faithful.zip"]), ("1.21.1", ["vanilla", "fabric", "file/Faithful.zip"])],
)
def test_the_migration_lists_the_loader_pack_only_where_the_target_does(
    tmp_path: Path, version: str, expected: list[str]
):
    game = tmp_path / "game"
    game.mkdir()
    (game / "options.txt").write_bytes((resource_packs("file/Faithful.zip", "fabric", "vanilla") + "\n").encode())

    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(MIGRATE),
            "-McDir",
            str(game),
            "-LegacyPackName",
            "RtxRemix-LabPBR",
            "-MinecraftVersion",
            version,
            "-ModLoader",
            "Fabric",
        ],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert (game / "options.txt").read_bytes().decode() == resource_packs(*expected) + "\n"
