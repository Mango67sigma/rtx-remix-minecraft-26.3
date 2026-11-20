# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""The fork's target, selected explicitly, for module-level use before any fixture runs."""

from __future__ import annotations

import json
import os
import zipfile
from pathlib import Path
from typing import Any

from rtx_tools import loader_registry
from rtx_tools.benchmark import util

FORK_TARGET = "26.3"
REPO_ROOT = util.PROJECT_DIR


def fork_registry() -> loader_registry.LoaderRegistry:
    return loader_registry.load_registry(minecraft_version=FORK_TARGET)


def cached_client_version_json(minecraft_version: str = FORK_TARGET) -> dict[str, Any] | None:
    """version.json of the client jar Loom caches for `minecraft_version`, or None before a Gradle build fetched it."""
    gradle_home = os.environ.get("GRADLE_USER_HOME")
    home = Path(gradle_home) if gradle_home else Path.home() / ".gradle"
    jar = home / "caches" / "fabric-loom" / minecraft_version / "minecraft-client.jar"
    if not jar.is_file():
        return None
    with zipfile.ZipFile(jar) as archive:
        return json.loads(archive.read("version.json"))
