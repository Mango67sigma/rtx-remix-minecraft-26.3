# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""What packaging/installer.ps1 does when the install it finds is an earlier release's, and when the host needs another
JDK, run under Windows PowerShell 5.1 as packaging/install.bat runs it.

The first release made a Prism instance for Minecraft 1.21.1 and Fabric Loader 0.16.5 on Java 21, and on ARM64 pinned it
to the managed jdk-21. An upgrade replaces the mod JAR and Fabric API under that instance, so the instance's
components, its Java version, and the pin have to move with them, or the 26.3 mod starts under the old game. Eclipse
Temurin publishes no Windows ARM64 JDK 25, so an ARM64 host gets Microsoft Build of OpenJDK instead, checked against
the SHA-256 Microsoft publishes beside it."""

import hashlib
import json
import os
import subprocess
import zipfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import write_jdk

from rtx_tools import prism
from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
INSTANCE_NAME = "NVIDIA RTX Remix for Minecraft"
MC_VERSION = prism.installer_constant("MC_VERSION")
LOADER_VERSION = prism.installer_constant("FABRIC_LOADER_VER")
FABRIC_API_VERSION = prism.installer_constant("FABRIC_API_VERSION")
JAVA_MAJOR = prism.GAME_JAVA_MAJOR
# What the first release pinned.
OLD_MC_VERSION = "1.21.1"
OLD_LOADER_VERSION = "0.16.5"
OLD_JAVA_MAJOR = 21
MINECRAFT, FABRIC_LOADER = "net.minecraft", "net.fabricmc.fabric-loader"
# The PE header machine types Get-PeMachine reads.
X64, ARM64 = 0x8664, 0xAA64

ARM64_JDK_URL = f"https://aka.ms/download-jdk/microsoft-jdk-{JAVA_MAJOR}-windows-aarch64.zip"
ARM64_JDK_SIDECAR_URL = f"{ARM64_JDK_URL}.sha256sum.txt"
X64_JDK_URL = f"https://api.adoptium.net/v3/binary/latest/{JAVA_MAJOR}/ga/windows/x64/jdk/hotspot/normal/eclipse"
X64_JDK_ASSETS_URL = (
    f"https://api.adoptium.net/v3/assets/latest/{JAVA_MAJOR}/hotspot?architecture=x64&image_type=jdk&os=windows"
    "&vendor=eclipse"
)

# Loads the installer's constants and functions without its entry point and runs one step, with the host's architecture
# the test's to choose (-Arm64), no sleeping between retries, and the network replaced by what the step needs:
# InitializeInstance, JavaPin, and InitializePrism run the Prism instance steps Initialize-Prism runs; ManagedJdkSource
# prints where the host gets its JDK; InstallManagedJdk runs Install-ManagedJdk with Invoke-WebRequest serving -Fixture
# for the JDK zip and -Sha256 as its published checksum (-Sidecar unreachable fails the checksum request) and
# Invoke-RestMethod answering Adoptium's asset list; InstallFabricApi and InitializeMicrosoft run those steps with the
# downloads replaced by empty files, and -JavaExe standing in for the Java that runs the Fabric installer. A step that
# throws prints "Thrown: <message>", and every request the network stand-ins saw prints "Requested: <url>".
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Action, [string]$DataDir, [string]$InstanceDir,
      [string]$ManagedJdk, [string]$McDir, [string]$Launcher, [string]$JavaExe, [string]$Fixture, [string]$Sha256,
      [string]$Sidecar, [string]$Answer, [switch]$Arm64, [switch]$Unattended)
$ProgressPreference = 'SilentlyContinue'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
if ($entryPoint -isnot [System.Management.Automation.Language.TryStatementAst]) {
    throw 'installer.ps1 no longer ends with its entry point'
}
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
$script:Unattended = $Unattended.IsPresent
$script:SessionTempDir = Join-Path (Split-Path -Parent $Loaded) 'session'
New-Item -ItemType Directory -Path $script:SessionTempDir -Force | Out-Null
$script:Requests = [System.Collections.Generic.List[string]]::new()
function Test-HostIsArm64 { return $Arm64.IsPresent }
function Confirm-YesNo {
    param([string]$Prompt, [switch]$DefaultNo)
    Write-Host "Confirm-YesNo: $Prompt"
    return $Answer -eq 'yes'
}
function Start-Sleep { }
function Get-JavaMajor {
    param([string]$JavaExe)
    $release = Join-Path (Split-Path -Parent (Split-Path -Parent $JavaExe)) 'release'
    if ((Test-Path -LiteralPath $release) -and ([IO.File]::ReadAllText($release) -match '(?m)^JAVA_VERSION="(\d+)')) {
        return [int]$Matches[1]
    }
    return 0
}
function Invoke-WebRequest {
    [CmdletBinding()]
    param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing, $Headers)
    $script:Requests.Add($Uri)
    if ($Uri.EndsWith('.sha256sum.txt')) {
        if ($Sidecar -eq 'unreachable') { throw 'The remote name could not be resolved' }
        return [pscustomobject]@{ Content = "$Sha256  microsoft-jdk-25.0.4.1-windows-aarch64.zip" }
    }
    Copy-Item -LiteralPath $Fixture -Destination $OutFile
}
function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri, [switch]$UseBasicParsing, $Headers)
    $script:Requests.Add($Uri)
    $package = [pscustomobject]@{ checksum = $Sha256 }
    return @([pscustomobject]@{
        binary = [pscustomobject]@{ image_type = 'jdk'; os = 'windows'; architecture = 'x64'; package = $package }
    })
}
if ($Action -in 'InstallFabricApi', 'InitializeMicrosoft') {
    function Invoke-VerifiedDownload {
        param($Url, $Destination, $ExpectedSha256, $ChecksumUrl)
        $script:Requests.Add($Url)
        [IO.File]::WriteAllBytes($Destination, [byte[]]::new(4096))
    }
}
try {
    switch ($Action) {
        'Constants' {
            ConvertTo-Json -Compress -InputObject ([ordered]@{
                profileKey = $script:FABRIC_PROFILE_KEY
                versionId  = $script:FABRIC_VERSION_ID
                jdkFolder  = Split-Path -Leaf $script:MANAGED_JDK_DIR
            })
        }
        'ManagedJdkSource' { ConvertTo-Json -Compress -InputObject (Get-ManagedJdkSource) }
        'InstallManagedJdk' {
            $script:MANAGED_JDK_DIR = $ManagedJdk
            Install-ManagedJdk
        }
        'InitializeInstance' { Initialize-PrismInstance -InstanceDir $InstanceDir }
        'JavaPin' {
            $script:MANAGED_JDK_DIR = $ManagedJdk
            Set-PrismJavaPin -InstanceCfg (Join-Path $InstanceDir 'instance.cfg')
        }
        'InitializePrism' {
            $script:MANAGED_JDK_DIR = $ManagedJdk
            $state = [pscustomobject]@{ PrismDataDir = $DataDir }
            Write-Host "Instance: $(Initialize-Prism -State $state -Unattended:$Unattended)"
        }
        'InstallFabricApi' { Install-FabricApi -McDir $McDir -Launcher $Launcher }
        'InitializeMicrosoft' { Initialize-MicrosoftLauncher -JavaExe $JavaExe -Unattended:$Unattended }
        default { throw "Unknown action: $Action" }
    }
} catch {
    Write-Host "Thrown: $($_.Exception.Message)"
}
foreach ($request in $script:Requests) { Write-Host "Requested: $request" }
"""

Harness = Callable[..., str]


@pytest.fixture
def harness(tmp_path: Path) -> Harness:
    """Runs one HARNESS step. Keyword options are the harness's parameters, a True one a switch; `env` adds to the
    environment. Windows PowerShell gets a module path of its own, as the PowerShell 7 one that a shell started from
    PowerShell 7 passes down hides the Get-FileHash it needs."""
    work = tmp_path / "harness"
    work.mkdir()
    script = work / "harness.ps1"
    script.write_text(HARNESS, encoding="ascii")

    def run(action: str, *, env: dict[str, str] | None = None, **options: Path | str | bool) -> str:
        arguments = ["-Action", action]
        for name, value in options.items():
            if value is True:
                arguments.append(f"-{name}")
            elif value is not False:
                arguments += [f"-{name}", str(value)]
        command = [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(script),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(work / "installer-functions.ps1"),
            *arguments,
        ]
        environment = {key: value for key, value in os.environ.items() if key.upper() != "PSMODULEPATH"}
        environment.update(env or {})
        result = subprocess.run(
            command, capture_output=True, encoding="utf-8", errors="replace", check=False, env=environment
        )
        assert result.returncode == 0, result.stdout + result.stderr
        assert not result.stderr, result.stderr
        return result.stdout

    return run


def lines_after(output: str, prefix: str) -> list[str]:
    """What each output line that starts with `prefix` says after it."""
    return [line.removeprefix(prefix) for line in output.splitlines() if line.startswith(prefix)]


def snapshot(folder: Path) -> dict[str, bytes]:
    """Every file under `folder` with its bytes."""
    return {
        path.relative_to(folder).as_posix(): path.read_bytes() for path in sorted(folder.rglob("*")) if path.is_file()
    }


def cfg_values(instance: Path) -> dict[str, str]:
    """The instance.cfg settings the installer wrote, which has no section headers."""
    text = (instance / "instance.cfg").read_text(encoding="utf-8")
    return {key: value for key, _, value in (line.partition("=") for line in text.splitlines()) if key}


def read_pack(instance: Path) -> dict[str, Any]:
    return json.loads((instance / "mmc-pack.json").read_text(encoding="utf-8"))


def components_by_uid(pack: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {component["uid"]: component for component in pack["components"]}


def make_instance(prism_data: Path, *, cfg: str, pack: dict[str, Any] | str | None) -> Path:
    """The Prism instance folder the installer looks for under a Prism data directory."""
    instance = prism_data / "instances" / INSTANCE_NAME
    instance.mkdir(parents=True)
    (instance / "instance.cfg").write_bytes(cfg.encode("utf-8"))
    if pack is not None:
        text = pack if isinstance(pack, str) else json.dumps(pack, indent=4) + "\n"
        (instance / "mmc-pack.json").write_text(text, encoding="utf-8")
    return instance


def first_release_cfg(pinned_java: Path | None = None) -> str:
    """instance.cfg as the first release's Initialize-PrismInstance wrote it, which has no final line ending, and as its
    ARM64 Set-PrismJavaPin extended it."""
    lines = ["InstanceType=OneSix", f"name={INSTANCE_NAME}", f"JavaVersion={OLD_JAVA_MAJOR}"]
    if pinned_java is not None:
        lines += ["OverrideJavaLocation=true", f"JavaPath={pinned_java.as_posix()}"]
    return "\r\n".join(lines)


def installer_written_pack(minecraft: str, loader: str) -> dict[str, Any]:
    """mmc-pack.json as Initialize-PrismInstance writes it, before Prism has loaded the instance."""
    return {
        "components": [
            {"cachedName": "Minecraft", "important": True, "uid": MINECRAFT, "version": minecraft},
            {"cachedName": "Fabric Loader", "uid": FABRIC_LOADER, "version": loader},
        ],
        "formatVersion": 1,
    }


def prism_saved_pack(
    minecraft: str, loader: str, lwjgl: str, extra: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    """mmc-pack.json as Prism writes it back after loading the instance: the components it resolved, with what it cached
    about each, and the two it adds as dependencies marked volatile, whose versions follow Minecraft's. `extra` are
    components the user added, which go before Fabric Loader."""
    return {
        "components": [
            {
                "cachedName": "LWJGL 3",
                "cachedVersion": lwjgl,
                "cachedVolatile": True,
                "dependencyOnly": True,
                "uid": "org.lwjgl3",
                "version": lwjgl,
            },
            {
                "cachedName": "Minecraft",
                "cachedRequires": [{"suggests": lwjgl, "uid": "org.lwjgl3"}],
                "cachedVersion": minecraft,
                "important": True,
                "uid": MINECRAFT,
                "version": minecraft,
            },
            {
                "cachedName": "Intermediary Mappings",
                "cachedRequires": [{"equals": minecraft, "uid": MINECRAFT}],
                "cachedVersion": minecraft,
                "cachedVolatile": True,
                "dependencyOnly": True,
                "uid": "net.fabricmc.intermediary",
                "version": minecraft,
            },
            *(extra or []),
            {
                "cachedName": "Fabric Loader",
                "cachedRequires": [{"uid": "net.fabricmc.intermediary"}],
                "cachedVersion": loader,
                "uid": FABRIC_LOADER,
                "version": loader,
            },
        ],
        "formatVersion": 1,
    }


USER_COMPONENT = {"cachedName": "Example Library", "uid": "com.example.library", "version": "2.0"}
FIRST_RELEASE_PACKS = {
    "as-the-installer-wrote-it": installer_written_pack(OLD_MC_VERSION, OLD_LOADER_VERSION),
    "as-prism-saved-it": prism_saved_pack(OLD_MC_VERSION, OLD_LOADER_VERSION, "3.3.3"),
    "with-a-component-the-user-added": prism_saved_pack(
        OLD_MC_VERSION, OLD_LOADER_VERSION, "3.3.3", extra=[USER_COMPONENT]
    ),
}


def is_volatile_dependency(component: dict[str, Any]) -> bool:
    """Whether Prism added the component as a dependency and marks its version as following another's."""
    return component.get("dependencyOnly") is True and component.get("cachedVolatile") is True


@pytest.mark.parametrize("original", list(FIRST_RELEASE_PACKS.values()), ids=list(FIRST_RELEASE_PACKS))
def test_an_instance_the_first_release_made_moves_to_the_pinned_game(
    harness: Harness, tmp_path: Path, original: dict[str, Any]
):
    instance = make_instance(tmp_path / "PrismLauncher", cfg=first_release_cfg(), pack=original)

    output = harness("InitializeInstance", InstanceDir=instance)

    pack = read_pack(instance)
    components = components_by_uid(pack)
    before = components_by_uid(original)
    assert components[MINECRAFT]["version"] == MC_VERSION
    assert components[FABRIC_LOADER]["version"] == LOADER_VERSION
    # Every other component, the order, and the format stay, except the dependencies Prism added for the old
    # Minecraft: it adds those again for the new one, but fails an instance whose Intermediary Mappings require the old.
    # The two that moved keep what Prism cached about them except the version and requirements it cached for the old
    # game.
    removed = [uid for uid, component in before.items() if is_volatile_dependency(component)]
    assert [component["uid"] for component in pack["components"]] == [uid for uid in before if uid not in removed]
    assert pack["formatVersion"] == original["formatVersion"]
    for uid, component in before.items():
        if uid in removed:
            continue
        if uid in (MINECRAFT, FABRIC_LOADER):
            kept = {
                key: value
                for key, value in component.items()
                if key not in ("version", "cachedVersion", "cachedRequires")
            }
            assert {key: value for key, value in components[uid].items() if key != "version"} == kept
        else:
            assert components[uid] == component
    # The previous file is kept, and instance.cfg changes in JavaVersion alone.
    assert json.loads((instance / "mmc-pack.json.bak").read_text(encoding="utf-8")) == original
    assert cfg_values(instance)["JavaVersion"] == str(JAVA_MAJOR)
    expected = [
        f"JavaVersion={JAVA_MAJOR}" if line.startswith("JavaVersion=") else line
        for line in first_release_cfg().split("\r\n")
    ]
    assert (instance / "instance.cfg").read_text(encoding="utf-8").splitlines() == expected
    # Each change is logged.
    assert f"Updated the Prism instance's Minecraft from {OLD_MC_VERSION} to {MC_VERSION}." in output
    assert f"Updated the Prism instance's Fabric Loader from {OLD_LOADER_VERSION} to {LOADER_VERSION}." in output
    assert f"Updated the Prism instance's JavaVersion from {OLD_JAVA_MAJOR} to {JAVA_MAJOR}." in output
    assert output.count("Removed the Prism instance's") == len(removed)
    for uid in removed:
        assert f"Removed the Prism instance's {before[uid]['cachedName']} {before[uid]['version']};" in output


def test_a_new_loader_alone_leaves_the_dependencies_prism_added_for_the_pinned_minecraft(
    harness: Harness, tmp_path: Path
):
    original = prism_saved_pack(MC_VERSION, OLD_LOADER_VERSION, "3.4.3")
    instance = make_instance(tmp_path / "PrismLauncher", cfg=first_release_cfg(), pack=original)

    output = harness("InitializeInstance", InstanceDir=instance)

    components = components_by_uid(read_pack(instance))
    assert components[FABRIC_LOADER]["version"] == LOADER_VERSION
    assert list(components) == list(components_by_uid(original))
    assert components["org.lwjgl3"] == components_by_uid(original)["org.lwjgl3"]
    assert components["net.fabricmc.intermediary"] == components_by_uid(original)["net.fabricmc.intermediary"]
    assert "Removed" not in output


def test_an_instance_the_installer_just_made_is_left_alone_by_the_next_run(harness: Harness, tmp_path: Path):
    instance = tmp_path / "PrismLauncher" / "instances" / INSTANCE_NAME
    created = harness("InitializeInstance", InstanceDir=instance)
    assert "Creating Prism instance" in created
    written = snapshot(instance)
    assert components_by_uid(read_pack(instance))[MINECRAFT]["version"] == MC_VERSION
    assert cfg_values(instance)["JavaVersion"] == str(JAVA_MAJOR)

    output = harness("InitializeInstance", InstanceDir=instance)

    assert "Reusing existing Prism instance" in output
    assert "Updated" not in output and "Set the Prism instance" not in output
    assert snapshot(instance) == written


@pytest.mark.parametrize("java_version", [f"{JAVA_MAJOR}.0.4.1", f'"{JAVA_MAJOR}.0.4.1"', str(JAVA_MAJOR)])
def test_a_pack_prism_saved_for_the_pinned_game_is_left_alone(harness: Harness, tmp_path: Path, java_version: str):
    pack = prism_saved_pack(MC_VERSION, LOADER_VERSION, "3.4.3")
    cfg = (
        f"[General]\r\nConfigVersion=1.3\r\nInstanceType=OneSix\r\nJavaVersion={java_version}\r\n"
        f"name={INSTANCE_NAME}\r\n"
    )
    instance = make_instance(tmp_path / "PrismLauncher", cfg=cfg, pack=pack)
    before = snapshot(instance)

    output = harness("InitializeInstance", InstanceDir=instance)

    assert snapshot(instance) == before
    assert "Updated" not in output and "Set the Prism instance" not in output


@pytest.mark.parametrize(
    ("java_version", "message"),
    [
        (f"{OLD_JAVA_MAJOR}", f"Updated the Prism instance's JavaVersion from {OLD_JAVA_MAJOR} to {JAVA_MAJOR}."),
        (
            f"{OLD_JAVA_MAJOR}.0.5",
            f"Updated the Prism instance's JavaVersion from {OLD_JAVA_MAJOR}.0.5 to {JAVA_MAJOR}.",
        ),
        ("1.8.0_392", f"Updated the Prism instance's JavaVersion from 1.8.0_392 to {JAVA_MAJOR}."),
        (None, f"Set the Prism instance's JavaVersion to {JAVA_MAJOR}."),
    ],
    ids=["major-alone", "full-version", "java-8", "unset"],
)
def test_the_instances_java_version_follows_the_game_major(
    harness: Harness, tmp_path: Path, java_version: str | None, message: str
):
    version_line = [] if java_version is None else [f"JavaVersion={java_version}"]
    cfg = "\r\n".join(["InstanceType=OneSix", f"name={INSTANCE_NAME}", *version_line, "OverrideJavaArgs=false", ""])
    pack = installer_written_pack(MC_VERSION, LOADER_VERSION)
    instance = make_instance(tmp_path / "PrismLauncher", cfg=cfg, pack=pack)
    pack_before = (instance / "mmc-pack.json").read_bytes()

    output = harness("InitializeInstance", InstanceDir=instance)

    assert message in output
    values = cfg_values(instance)
    assert values["JavaVersion"] == str(JAVA_MAJOR)
    assert values["OverrideJavaArgs"] == "false"
    assert (instance / "mmc-pack.json").read_bytes() == pack_before


def test_an_instance_without_a_pack_gets_the_pinned_one(harness: Harness, tmp_path: Path):
    instance = make_instance(tmp_path / "PrismLauncher", cfg=first_release_cfg(), pack=None)

    output = harness("InitializeInstance", InstanceDir=instance)

    assert "has no mmc-pack.json" in output
    pack = read_pack(instance)
    assert {uid: component["version"] for uid, component in components_by_uid(pack).items()} == {
        MINECRAFT: MC_VERSION,
        FABRIC_LOADER: LOADER_VERSION,
    }


def test_a_pack_the_installer_cannot_read_is_left_as_it_is(harness: Harness, tmp_path: Path):
    instance = make_instance(tmp_path / "PrismLauncher", cfg=first_release_cfg(), pack="{ not json")

    output = harness("InitializeInstance", InstanceDir=instance)

    assert "Could not parse mmc-pack.json" in output
    assert (instance / "mmc-pack.json").read_text(encoding="utf-8") == "{ not json"
    assert not (instance / "mmc-pack.json.bak").exists()
    assert cfg_values(instance)["JavaVersion"] == str(JAVA_MAJOR)


def managed_jdk(tmp_path: Path, *, present: bool = True) -> Path:
    """The folder the installer manages a JDK in, holding the pinned major's ARM64 JDK unless `present` is False."""
    folder = tmp_path / "RtxRemixMinecraft" / f"jdk-{JAVA_MAJOR}"
    if present:
        write_jdk(folder, version=f"{JAVA_MAJOR}.0.4.1", machine=ARM64)
    return folder


def pinned_cfg(javaw: Path) -> str:
    return (
        f"InstanceType=OneSix\r\nname={INSTANCE_NAME}\r\nJavaVersion={JAVA_MAJOR}\r\n"
        f"OverrideJavaLocation=true\r\nJavaPath={javaw.as_posix()}\r\n"
    )


def without_pin(cfg: str) -> list[str]:
    return [line for line in cfg.splitlines() if not line.startswith(("OverrideJavaLocation=", "JavaPath="))]


def pin_values(instance: Path) -> tuple[str | None, str | None]:
    values = cfg_values(instance)
    return values.get("OverrideJavaLocation"), values.get("JavaPath")


# The JDK an ARM64 instance's JavaPath names, its release file, and whether the installer has a managed JDK to pin
# instead, with what becomes of the pin: kept as it is, replaced by the managed JDK, or cleared because there is none.
JAVA_PIN_CASES = [
    pytest.param(f"{OLD_JAVA_MAJOR}.0.5", ARM64, True, "replaced", "Java 21", id="arm64-java-21"),
    pytest.param(f"{JAVA_MAJOR + 1}.0.1", ARM64, True, "replaced", f"Java {JAVA_MAJOR + 1}", id="arm64-newer-java"),
    pytest.param(f"{JAVA_MAJOR}.0.1", X64, True, "replaced", "non-ARM64", id="x64-java-25"),
    pytest.param(None, ARM64, True, "replaced", "could not be read", id="arm64-no-release-file"),
    pytest.param(f"{JAVA_MAJOR}.0.1", ARM64, True, "kept", "leaving untouched", id="arm64-java-25"),
    pytest.param(f"{JAVA_MAJOR}.0.1", ARM64, False, "kept", "leaving untouched", id="arm64-java-25-no-managed-jdk"),
    pytest.param(f"{OLD_JAVA_MAJOR}.0.5", ARM64, False, "cleared", "no managed JDK", id="arm64-java-21-no-managed-jdk"),
]


@pytest.mark.parametrize(("version", "machine", "managed", "outcome", "message"), JAVA_PIN_CASES)
def test_the_arm64_java_pin_has_to_be_a_native_java_of_the_games_major(
    harness: Harness, tmp_path: Path, version: str | None, machine: int, managed: bool, outcome: str, message: str
):
    pinned = write_jdk(tmp_path / "pinned" / "jdk", version=version or "0", machine=machine)
    if version is None:
        (pinned / "release").unlink()
    managed_dir = managed_jdk(tmp_path, present=managed)
    instance = make_instance(tmp_path / "PrismLauncher", cfg=pinned_cfg(pinned / "bin" / "javaw.exe"), pack=None)
    original = (instance / "instance.cfg").read_bytes()

    output = harness("JavaPin", Arm64=True, InstanceDir=instance, ManagedJdk=managed_dir)

    assert message in output
    if outcome == "kept":
        assert (instance / "instance.cfg").read_bytes() == original
        return
    if outcome == "replaced":
        assert pin_values(instance) == ("true", (managed_dir / "bin" / "javaw.exe").as_posix())
    else:
        assert pin_values(instance) == ("false", (pinned / "bin" / "javaw.exe").as_posix())
    # Nothing but the pin changes.
    assert without_pin((instance / "instance.cfg").read_text(encoding="utf-8")) == without_pin(original.decode("utf-8"))


def test_an_x64_host_never_has_a_java_pin_touched(harness: Harness, tmp_path: Path):
    pinned = write_jdk(tmp_path / "pinned" / "jdk", version=f"{OLD_JAVA_MAJOR}.0.5", machine=X64)
    instance = make_instance(tmp_path / "PrismLauncher", cfg=pinned_cfg(pinned / "bin" / "javaw.exe"), pack=None)
    original = (instance / "instance.cfg").read_bytes()

    harness("JavaPin", InstanceDir=instance, ManagedJdk=managed_jdk(tmp_path))

    assert (instance / "instance.cfg").read_bytes() == original


def test_an_arm64_upgrade_moves_the_components_the_java_version_and_the_pin_together(harness: Harness, tmp_path: Path):
    old_jdk = write_jdk(tmp_path / "RtxRemixMinecraft" / f"jdk-{OLD_JAVA_MAJOR}", version="21.0.5", machine=ARM64)
    managed = managed_jdk(tmp_path)
    prism_data = tmp_path / "PrismLauncher"
    instance = make_instance(
        prism_data,
        cfg=first_release_cfg(old_jdk / "bin" / "javaw.exe"),
        pack=prism_saved_pack(OLD_MC_VERSION, OLD_LOADER_VERSION, "3.3.3"),
    )

    output = harness("InitializePrism", Arm64=True, Unattended=True, DataDir=prism_data, ManagedJdk=managed)

    # Initialize-Prism returns the instance folder alone, so no step leaks into its output.
    assert lines_after(output, "Instance: ") == [str(instance)]
    components = components_by_uid(read_pack(instance))
    assert components[MINECRAFT]["version"] == MC_VERSION
    assert components[FABRIC_LOADER]["version"] == LOADER_VERSION
    assert cfg_values(instance)["JavaVersion"] == str(JAVA_MAJOR)
    assert pin_values(instance) == ("true", (managed / "bin" / "javaw.exe").as_posix())
    assert "Pinned Prism instance to managed ARM64 JDK" in output


def test_an_arm64_host_gets_microsoft_build_of_openjdk(harness: Harness):
    source = json.loads(harness("ManagedJdkSource", Arm64=True))

    assert source == {
        "Name": "Microsoft Build of OpenJDK",
        "Slug": "microsoft",
        "Arch": "aarch64",
        "BinaryUrl": ARM64_JDK_URL,
        "ChecksumUrl": ARM64_JDK_SIDECAR_URL,
        "AssetUrl": None,
    }


def test_an_x64_host_still_gets_eclipse_temurin_from_adoptium(harness: Harness):
    source = json.loads(harness("ManagedJdkSource"))

    assert source == {
        "Name": "Adoptium Temurin JDK",
        "Slug": "adoptium",
        "Arch": "x64",
        "BinaryUrl": X64_JDK_URL,
        "ChecksumUrl": None,
        "AssetUrl": X64_JDK_ASSETS_URL,
    }


def jdk_zip(path: Path) -> str:
    """A zip laid out like the JDK archives Temurin and Microsoft publish, with one top-level folder, and its
    SHA-256."""
    top = f"jdk-{JAVA_MAJOR}.0.4.1+1"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(f"{top}/bin/java.exe", b"MZ")
        archive.writestr(f"{top}/bin/javaw.exe", b"MZ")
        archive.writestr(f"{top}/release", f'JAVA_VERSION="{JAVA_MAJOR}.0.4.1"\n')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_arm64_installs_the_jdk_after_reading_its_checksum_from_the_sidecar(harness: Harness, tmp_path: Path):
    archive = tmp_path / "jdk.zip"
    target = tmp_path / "RtxRemixMinecraft" / f"jdk-{JAVA_MAJOR}"

    output = harness(
        "InstallManagedJdk", Arm64=True, Unattended=True, Fixture=archive, Sha256=jdk_zip(archive), ManagedJdk=target
    )

    assert "Thrown" not in output
    # The checksum is read before the archive is downloaded, and Adoptium is never asked.
    assert lines_after(output, "Requested: ") == [ARM64_JDK_SIDECAR_URL, ARM64_JDK_URL]
    assert f"Auto-installing Microsoft Build of OpenJDK {JAVA_MAJOR} (aarch64" in output
    assert (target / "bin" / "java.exe").is_file()
    assert (target / "release").is_file()


def test_arm64_refuses_a_jdk_that_does_not_match_the_published_checksum(harness: Harness, tmp_path: Path):
    archive = tmp_path / "jdk.zip"
    jdk_zip(archive)
    target = tmp_path / "RtxRemixMinecraft" / f"jdk-{JAVA_MAJOR}"

    output = harness(
        "InstallManagedJdk", Arm64=True, Unattended=True, Fixture=archive, Sha256="0" * 64, ManagedJdk=target
    )

    assert "Thrown: SHA-256 mismatch" in output
    assert not target.exists()


def test_an_unattended_arm64_run_refuses_a_jdk_whose_checksum_it_cannot_read(harness: Harness, tmp_path: Path):
    archive = tmp_path / "jdk.zip"
    sha256 = jdk_zip(archive)
    target = tmp_path / "RtxRemixMinecraft" / f"jdk-{JAVA_MAJOR}"

    output = harness(
        "InstallManagedJdk",
        Arm64=True,
        Unattended=True,
        Sidecar="unreachable",
        Fixture=archive,
        Sha256=sha256,
        ManagedJdk=target,
    )

    assert "Thrown: Cannot verify" in output
    assert "Refusing to download unverified in -Unattended mode" in output
    # Only the checksum was asked for: the archive was never downloaded.
    assert set(lines_after(output, "Requested: ")) == {ARM64_JDK_SIDECAR_URL}
    assert not target.exists()


def test_an_interactive_arm64_run_asks_before_using_a_jdk_it_cannot_verify(harness: Harness, tmp_path: Path):
    archive = tmp_path / "jdk.zip"
    sha256 = jdk_zip(archive)
    target = tmp_path / "RtxRemixMinecraft" / f"jdk-{JAVA_MAJOR}"

    output = harness(
        "InstallManagedJdk",
        Arm64=True,
        Sidecar="unreachable",
        Answer="no",
        Fixture=archive,
        Sha256=sha256,
        ManagedJdk=target,
    )

    assert "Confirm-YesNo: Proceed with UNVERIFIED download?" in output
    assert "Thrown: Aborted: refused to proceed without integrity verification." in output
    assert set(lines_after(output, "Requested: ")) == {ARM64_JDK_SIDECAR_URL}
    assert not target.exists()


def test_x64_installs_temurin_with_the_checksum_in_adoptiums_asset_list(harness: Harness, tmp_path: Path):
    archive = tmp_path / "jdk.zip"
    target = tmp_path / "RtxRemixMinecraft" / f"jdk-{JAVA_MAJOR}"

    output = harness("InstallManagedJdk", Unattended=True, Fixture=archive, Sha256=jdk_zip(archive), ManagedJdk=target)

    assert "Thrown" not in output
    assert lines_after(output, "Requested: ") == [X64_JDK_ASSETS_URL, X64_JDK_URL]
    assert f"Auto-installing Adoptium Temurin JDK {JAVA_MAJOR} (x64" in output
    assert (target / "bin" / "java.exe").is_file()


@pytest.mark.parametrize("launcher", ["Prism", "Microsoft"])
def test_fabric_api_of_another_version_is_replaced_in_a_prism_instance_only(
    harness: Harness, tmp_path: Path, launcher: str
):
    mc_dir = tmp_path / ".minecraft"
    mods = mc_dir / "mods"
    mods.mkdir(parents=True)
    old_api = mods / "fabric-api-0.105.0+1.21.1.jar"
    others = [mods / "rtx-remix-renderer-0.1.0-20261023-abc1234.jar", mods / "another-mod-1.0.jar"]
    for jar in [old_api, *others]:
        jar.write_bytes(b"jar")

    output = harness("InstallFabricApi", McDir=mc_dir, Launcher=launcher)

    new_api = mods / f"fabric-api-{FABRIC_API_VERSION}.jar"
    assert new_api.stat().st_size >= 1024
    assert all(jar.is_file() for jar in others)
    # A Prism instance is the installer's own, so it is left with one Fabric API. The Microsoft launcher's mods folder
    # is shared with its other Fabric profiles, which may still need the old one.
    assert old_api.exists() is (launcher == "Microsoft")
    assert lines_after(output, "Requested: ") == [
        f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{FABRIC_API_VERSION}/{new_api.name}"
    ]


OLD_PROFILE_KEY = f"fabric-loader-{OLD_MC_VERSION}"
OLD_VERSION_ID = f"fabric-loader-{OLD_LOADER_VERSION}-{OLD_MC_VERSION}"
PROFILE_KEY = f"fabric-loader-{MC_VERSION}"
VERSION_ID = f"fabric-loader-{LOADER_VERSION}-{MC_VERSION}"
# A Fabric Loader build that the installer did not pin, which a profile for the same Minecraft version can launch.
OTHER_VERSION_ID = f"fabric-loader-0.19.2-{MC_VERSION}"
# Stands in for the Java that runs the Fabric installer: it logs its command line, and sets the launcher profiles to
# after.json beside it when there is one, as the Fabric installer sets the profile's version id.
FAKE_JAVA = (
    "@echo off\r\n"
    'echo %* >> "%~dp0fabric-installer-calls.txt"\r\n'
    'if exist "%~dp0after.json" copy /Y "%~dp0after.json" "%APPDATA%\\.minecraft\\launcher_profiles.json" >nul\r\n'
    "exit /b 0\r\n"
)


def launcher_profiles(launches: str | None) -> dict[str, Any]:
    """launcher_profiles.json holding the first release's profile for Minecraft 1.21.1, and the profile for 26.3 when
    `launches` names the version id it launches."""
    profiles = {OLD_PROFILE_KEY: {"name": INSTANCE_NAME, "lastVersionId": OLD_VERSION_ID, "type": "custom"}}
    if launches is not None:
        profiles[PROFILE_KEY] = {"name": PROFILE_KEY, "lastVersionId": launches, "type": "custom"}
    return {"profiles": profiles, "version": 3}


def run_microsoft_launcher_step(
    harness: Harness, tmp_path: Path, launches: str | None, *, installer_fixes: bool
) -> str:
    """Initialize-MicrosoftLauncher over a launcher that has the pinned version's files and the profile in
    launcher_profiles.json, with the Fabric installer standing in as `installer_fixes` says."""
    appdata = tmp_path / "AppData"
    (appdata / ".minecraft" / "versions" / VERSION_ID).mkdir(parents=True)
    profiles = appdata / ".minecraft" / "launcher_profiles.json"
    profiles.write_text(json.dumps(launcher_profiles(launches)), encoding="utf-8")
    java = tmp_path / "java" / "java.cmd"
    java.parent.mkdir()
    java.write_text(FAKE_JAVA, encoding="ascii")
    if installer_fixes:
        (java.parent / "after.json").write_text(json.dumps(launcher_profiles(VERSION_ID)), encoding="utf-8")
    return harness("InitializeMicrosoft", Unattended=True, JavaExe=java, env={"APPDATA": str(appdata)})


def fabric_installer_calls(tmp_path: Path) -> list[str]:
    calls = tmp_path / "java" / "fabric-installer-calls.txt"
    return calls.read_text(encoding="utf-8").splitlines() if calls.exists() else []


def written_profiles(tmp_path: Path) -> dict[str, Any]:
    return json.loads((tmp_path / "AppData" / ".minecraft" / "launcher_profiles.json").read_text(encoding="utf-8"))[
        "profiles"
    ]


def test_the_profile_names_the_pinned_loader_for_the_pinned_game(harness: Harness):
    constants = json.loads(harness("Constants"))

    assert constants == {"profileKey": PROFILE_KEY, "versionId": VERSION_ID, "jdkFolder": f"jdk-{JAVA_MAJOR}"}


def test_a_profile_that_launches_the_pinned_loader_is_reused_beside_the_old_one(harness: Harness, tmp_path: Path):
    output = run_microsoft_launcher_step(harness, tmp_path, VERSION_ID, installer_fixes=False)

    assert "Reusing existing Fabric" in output
    assert fabric_installer_calls(tmp_path) == []
    profiles = written_profiles(tmp_path)
    assert profiles[OLD_PROFILE_KEY] == launcher_profiles(None)["profiles"][OLD_PROFILE_KEY]
    assert profiles[PROFILE_KEY]["lastVersionId"] == VERSION_ID


def test_a_profile_that_launches_another_loader_build_is_pointed_at_the_pinned_one(harness: Harness, tmp_path: Path):
    output = run_microsoft_launcher_step(harness, tmp_path, OTHER_VERSION_ID, installer_fixes=True)

    assert f"launches {OTHER_VERSION_ID}, not {VERSION_ID}" in output
    assert "Reusing existing Fabric" not in output
    calls = fabric_installer_calls(tmp_path)
    assert len(calls) == 1
    assert calls[0].strip().endswith(f"client -mcversion {MC_VERSION} -loader {LOADER_VERSION}")
    assert "still launches" not in output
    profiles = written_profiles(tmp_path)
    assert profiles[PROFILE_KEY]["lastVersionId"] == VERSION_ID
    assert profiles[OLD_PROFILE_KEY] == launcher_profiles(None)["profiles"][OLD_PROFILE_KEY]


def test_the_installer_warns_when_the_profile_still_launches_another_loader_build(harness: Harness, tmp_path: Path):
    output = run_microsoft_launcher_step(harness, tmp_path, OTHER_VERSION_ID, installer_fixes=False)

    assert len(fabric_installer_calls(tmp_path)) == 1
    assert f"launcher profile '{PROFILE_KEY}' still launches {OTHER_VERSION_ID}" in output
    assert written_profiles(tmp_path)[PROFILE_KEY]["lastVersionId"] == OTHER_VERSION_ID
