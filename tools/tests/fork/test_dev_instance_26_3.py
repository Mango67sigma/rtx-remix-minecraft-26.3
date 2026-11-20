# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""The development Prism instance for the 26.3 Fabric target: its name and place, its pins, its JDK 25, its heap, the
Gradle tasks that prepare it, and the development install into it."""

import json
import re
import subprocess
from pathlib import Path
from unittest.mock import Mock

import pytest
from conftest import write_jdk

from rtx_tools import cli, diagnostics, install, prism, runtime_compat
from rtx_tools._diag_win32 import WindowInfo
from rtx_tools.benchmark import runner, util
from rtx_tools.client import LaunchBlocked
from rtx_tools.prism_launcher import PrismLauncher

from .target import fork_registry

REGISTRY = fork_registry()
FABRIC = REGISTRY.get("fabric")
ALEX = prism.PrismAccount(kind="MSA", name="Alex", owns_minecraft=True, active=True)


def test_the_portable_instance_is_the_checkouts_existing_rtx_remix_dev(tmp_path):
    # A portable Prism keeps run-prism/instances/rtx-remix-dev, which holds the player's mods and settings from every
    # earlier fork release; the instance name does not depend on the Minecraft version or loader.
    exe = tmp_path / "prism" / "prismlauncher.exe"
    exe.parent.mkdir()
    exe.write_bytes(b"")
    (exe.parent / "portable.txt").write_text("")
    checkout_data = tmp_path / "checkout" / "run-prism"

    layout = prism.launcher_layout(exe, checkout_data, tmp_path / "checkout")

    assert layout.instance_id == "rtx-remix-dev"
    assert layout.instance_name == "RTX Remix Dev"
    assert layout.game_dir == checkout_data / "instances" / "rtx-remix-dev" / ".minecraft"
    assert util.RUN_DIR.parent.name == "rtx-remix-dev"


def test_the_instance_pins_26_3_on_fabric_loader_from_the_catalog(tmp_path):
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=[])

    pack = json.loads((tmp_path / "instances" / "rtx-remix-dev" / "mmc-pack.json").read_text(encoding="utf-8"))
    assert pack["components"] == [
        {"cachedName": "Minecraft", "important": True, "uid": "net.minecraft", "version": "26.3"},
        {"cachedName": "Fabric Loader", "uid": "net.fabricmc.fabric-loader", "version": "0.20.1"},
    ]


def test_the_last_heap_of_the_launch_arguments_is_prisms_memory_setting(tmp_path):
    recommended = prism.recommended_jvm_args()
    configured = ["-Xmx16G", "-Drtxremix.vanillaVisibilityWalk=true"]

    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=[*recommended, *configured])

    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8").splitlines()
    assert "MaxMemAlloc=16384" in cfg
    jvm_args = next(line.removeprefix("JvmArgs=") for line in cfg if line.startswith("JvmArgs="))
    # Prism refuses -Xmx in JvmArgs, so every heap option becomes its memory setting instead.
    assert "-Xmx" not in jvm_args
    assert "-Drtxremix.vanillaVisibilityWalk=true" in jvm_args


@pytest.fixture
def jdk_places(tmp_path, monkeypatch) -> dict[str, Path]:
    gradle_home = tmp_path / "gradle-home"
    program_files = tmp_path / "Program Files"
    monkeypatch.setenv("GRADLE_USER_HOME", str(gradle_home))
    monkeypatch.setenv("ProgramFiles", str(program_files))
    return {
        "gradle": gradle_home / "jdks" / "eclipse_adoptium-25-amd64-windows.2",
        "adoptium": program_files / "Eclipse Adoptium" / "jdk-25.0.1.8-hotspot",
        "microsoft": program_files / "Microsoft" / "jdk-25.0.1.8-hotspot",
        "oracle": program_files / "Java" / "jdk-25",
    }


def test_find_game_jdk_uses_java_home_when_it_is_a_jdk_25_for_the_arch(tmp_path, jdk_places):
    java_home = write_jdk(tmp_path / "home", version="25.0.1")
    write_jdk(jdk_places["gradle"], version="25.0.1")
    assert prism.find_game_jdk("win64", java_home) == java_home


@pytest.mark.parametrize(
    ("version", "machine"),
    [
        # Prism refuses any major but 25 for Minecraft 26.3, older and newer ones alike.
        ("21.0.5", 0x8664),
        ("26.0.1", 0x8664),
        # The bridge and runtime load only into a JVM of their own architecture.
        ("25.0.1", 0xAA64),
    ],
)
def test_find_game_jdk_looks_past_a_java_home_prism_cannot_use(tmp_path, jdk_places, version, machine):
    java_home = write_jdk(tmp_path / "home", version=version, machine=machine)
    found = write_jdk(jdk_places["adoptium"], version="25.0.1")
    assert prism.find_game_jdk("win64", java_home) == found


def test_find_game_jdk_reads_only_jdk_25_folders_under_program_files(jdk_places):
    write_jdk(jdk_places["microsoft"].parent / "jdk-21.0.5.11-hotspot", version="21.0.5")
    assert prism.find_game_jdk("win64", None) is None
    newest = write_jdk(jdk_places["microsoft"].parent / "jdk-25.0.10.7-hotspot", version="25.0.10")
    write_jdk(jdk_places["microsoft"].parent / "jdk-25.0.2.13-hotspot", version="25.0.2")
    assert prism.find_game_jdk("win64", None) == newest


@pytest.fixture
def launcher(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> PrismLauncher:
    data_dir = tmp_path / "run-prism"
    monkeypatch.setattr(util, "PRISM_DATA_DIR", data_dir)
    monkeypatch.setattr(util, "RUN_DIR", data_dir / "instances" / prism.INSTANCE_ID / ".minecraft")
    exe = tmp_path / "prism" / "prismlauncher.exe"
    exe.parent.mkdir()
    exe.write_bytes(b"")
    (exe.parent / "portable.txt").write_text("")
    monkeypatch.setattr(prism, "find_executable", lambda config: exe)
    monkeypatch.setattr(prism, "supports_launch_flags", lambda exe: True)
    monkeypatch.setattr(prism, "running_prism_pids", lambda data_dir, **kwargs: [])
    monkeypatch.setattr(prism, "read_accounts", lambda data_dir: [ALEX])
    monkeypatch.setattr(runner, "host_arch", lambda override=None: "win64")
    monkeypatch.setenv("GRADLE_USER_HOME", str(tmp_path / "no-gradle"))
    monkeypatch.setenv("ProgramFiles", str(tmp_path / "no-program-files"))
    return PrismLauncher({}, sleep=lambda seconds: None)


@pytest.mark.parametrize("java_home", [None, "21.0.5"])
def test_check_needs_a_jdk_25(launcher, monkeypatch, tmp_path, java_home):
    home = None if java_home is None else write_jdk(tmp_path / "jdk-21", version=java_home)
    monkeypatch.setattr(runner, "detect_java_home", lambda: home)

    with pytest.raises(LaunchBlocked, match=re.escape("Minecraft 26.3 in Prism needs a 64-bit JDK 25 for win64")):
        launcher.check()


def test_prepare_builds_the_26_3_fabric_module_and_installs_its_fabric_api(launcher, tmp_path, monkeypatch):
    project = tmp_path / "project"
    (project / "packaging").mkdir(parents=True)
    (project / "packaging" / "installer.ps1").write_bytes(
        (util.PROJECT_DIR / "packaging" / "installer.ps1").read_bytes()
    )
    fabric_api = project / "versions" / "26.3" / "fabric" / "build" / "dev" / "fabric-api.jar"
    fabric_api.parent.mkdir(parents=True)
    fabric_api.write_bytes(b"pinned fabric api")
    mod_jar = project / "versions" / "26.3" / "fabric" / "build" / "libs" / "rtx-remix-renderer-0.1.3.jar"
    mod_jar.parent.mkdir(parents=True)
    mod_jar.write_bytes(b"fabric mod")
    stale_api = launcher.game_dir / "mods" / "fabric-api-0.116.10+1.21.1.jar"
    stale_api.parent.mkdir(parents=True)
    stale_api.write_bytes(b"stale")
    monkeypatch.setattr(util, "PROJECT_DIR", project)
    commands: list[list[str]] = []
    monkeypatch.setattr(
        util,
        "run",
        lambda args, **kwargs: commands.append([str(a) for a in args]) or subprocess.CompletedProcess(args, 0),
    )
    installs = []
    monkeypatch.setattr(
        install, "install_development_build", lambda project_dir, **kwargs: installs.append(kwargs) or 0
    )
    monkeypatch.setattr(runtime_compat, "warn_if_incompatible", lambda project, arch: None)
    monkeypatch.setattr(launcher, "_java", lambda arch: Path("C:/jdk-25/bin/javaw.exe"))

    launcher.prepare("win64", build=True)

    assert commands == [
        [
            str(project / "gradlew.bat"),
            "setupRuntime",
            ":versions:26.3:fabric:stageFabricApi",
            ":versions:26.3:fabric:build",
            "-x",
            ":versions:26.3:fabric:test",
            "-PmcVersion=26.3",
            "-Ploader=fabric",
            "-Parch=win64",
            "--console=plain",
            "--quiet",
        ]
    ]
    assert [install["mod_jar"] for install in installs] == [mod_jar]
    assert not stale_api.exists()
    assert (launcher.game_dir / "mods" / "fabric-api-0.165.0+26.3.jar").read_bytes() == b"pinned fabric api"
    pack = json.loads((launcher.game_dir.parent / "mmc-pack.json").read_text(encoding="utf-8"))
    assert [component["version"] for component in pack["components"]] == ["26.3", "0.20.1"]


@pytest.fixture
def installation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Path, Path, Mock]:
    project = tmp_path / "checkout"
    game = tmp_path / "game"
    (game / "mods").mkdir(parents=True)
    monkeypatch.setattr(util, "PROJECT_DIR", project)
    monkeypatch.setattr(util, "load_env_file", lambda: None)
    for name in ("REMIX_ARCH", "REMIX_BUILD", "REMIX_FORCE_SDK_COPY", "RTXREMIX_MOD_LOADER"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("PROCESSOR_ARCHITECTURE", "AMD64")
    monkeypatch.setenv("APPDATA", str(tmp_path / "appdata"))
    files = {
        f"{FABRIC.development_jar_dir}/rtx-remix-renderer-0.1.3.jar": b"newest mod",
        "packaging/rtx.conf": b"runtime defaults",
        "packaging/rtx-remix/mods/minecraft-logic/weather/rain.conf": b"shipped rain",
        "remix-runtime/textures/overcast_thunderstorm_bc6u.dds": b"overcast texture",
        "remix-runtime/win64/d3d9.dll": b"runtime",
        "build/native/win64/remixbridge.dll": b"bridge",
    }
    for name, content in files.items():
        (project / name).parent.mkdir(parents=True, exist_ok=True)
        (project / name).write_bytes(content)
    migration = Mock(return_value=subprocess.CompletedProcess(args=[], returncode=0))
    monkeypatch.setattr(util, "run", migration)
    return project, game, migration


def test_install_replaces_every_earlier_fork_jar_with_the_catalogs_installed_name(installation, isolated_tools_config):
    project, game, _ = installation
    # 0.1.0 to 0.1.3 of the fork installed rtx-remix-renderer.jar (tools) or a versioned name (installer).
    for earlier in ("rtx-remix-renderer.jar", "rtx-remix-renderer-0.1.2-mc26.3.jar"):
        (game / "mods" / earlier).write_bytes(b"earlier")
    (game / "mods" / "fabric-api-0.165.0+26.3.jar").write_bytes(b"fabric api")

    assert cli.main(["install", "--game-dir", str(game)]) == 0

    assert sorted(path.name for path in (game / "mods").iterdir()) == [
        "fabric-api-0.165.0+26.3.jar",
        "rtx-remix-renderer-fabric.jar",
    ]
    assert (game / "mods" / "rtx-remix-renderer-fabric.jar").read_bytes() == b"newest mod"


def test_install_migrates_packs_for_the_26_3_target(installation, isolated_tools_config, capsys):
    project, game, migration = installation

    assert cli.main(["install", "--game-dir", str(game)]) == 0

    args = migration.call_args.args[0]
    assert args[args.index("-MinecraftVersion") + 1] == "26.3"
    assert args[-2:] == ["-ModLoader", "Fabric"]
    assert "fabric-api >=0.165.0+26.3 (https://modrinth.com/mod/fabric-api)" in capsys.readouterr().out


def test_the_pack_migration_lists_no_fabric_pack_for_26_3(tmp_path):
    game = tmp_path / "game"
    game.mkdir()
    (game / "options.txt").write_text('resourcePacks:["file/user-pack","fabric","vanilla"]\n', encoding="utf-8")

    subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(util.PROJECT_DIR / "packaging" / "migrate_resource_packs.ps1"),
            "-McDir",
            str(game),
            "-LegacyPackName",
            "rtx-remix-labpbr",
            "-MinecraftVersion",
            "26.3",
            "-ModLoader",
            "Fabric",
        ],
        check=True,
    )

    assert (game / "options.txt").read_text(encoding="utf-8") == 'resourcePacks:["vanilla","file/user-pack"]\n'


def test_diagnostics_find_the_26_3_game_window():
    native = Mock()
    native.visible_windows.return_value = [
        WindowInfo(1, 10, "Minecraft 26.3"),
        WindowInfo(2, 20, "Minecraft* 26.3"),
        WindowInfo(3, 30, "minecraft 26.3"),
        WindowInfo(4, 40, "Minecraft Launcher"),
    ]

    assert diagnostics.find_game_window(native) == WindowInfo(2, 20, "Minecraft* 26.3")
