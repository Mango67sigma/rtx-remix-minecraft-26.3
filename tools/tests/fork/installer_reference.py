# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""The catalog NVIDIA's installer tests were written against.

packaging/installer.ps1 reads project-state.json, from RTXREMIX_PROJECT_STATE or beside it, and offers every
version the catalog declares that the bundle stages. NVIDIA's installer tests assume a catalog declaring only their
reference target, 1.21.1 with Fabric, Forge and NeoForge, so a release laid out as mods/<loader>/ with unqualified
JAR names, or a source checkout, resolves to it. This checkout's catalog also declares 26.3, its default and only
built version. The `reference_catalog` fixture gives those tests the catalog they assume: this checkout's own, cut
down to the ambient reference version (RTXREMIX_MINECRAFT_VERSION, which tests/conftest.py sets), and points
RTXREMIX_PROJECT_STATE at it. The installer itself has no test hook; the fork's 26.3 installer tests use the real
catalog and select 26.3 explicitly."""

import json
import os
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

REFERENCE_FALLBACK = "1.21.1"


def write_reference_catalog(path: Path, version: str | None = None) -> Path:
    """This checkout's project-state.json declaring `version` alone (default: the ambient reference version), as the
    default, with no builtVersions, written to `path`."""
    version = version or os.environ.get("RTXREMIX_MINECRAFT_VERSION") or REFERENCE_FALLBACK
    state = json.loads((util.PROJECT_DIR / "project-state.json").read_text(encoding="utf-8-sig"))
    state["versions"] = {version: state["versions"][version]}
    state["defaultMinecraftVersion"] = version
    state.pop("builtVersions", None)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    return path


@pytest.fixture
def reference_catalog(tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch) -> Path:
    """RTXREMIX_PROJECT_STATE names the reference catalog for the test, which a test copies into a release it lays
    out with shutil.copy2(os.environ["RTXREMIX_PROJECT_STATE"], ...)."""
    path = write_reference_catalog(tmp_path_factory.mktemp("reference-catalog") / "project-state.json")
    monkeypatch.setenv("RTXREMIX_PROJECT_STATE", str(path))
    return path
