# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""Ledgers of the 26.3 port's transitional state, which may only shrink.

Stage 1 of adopting upstream's multi-version layout kept a copy of the renderer core in versions/26.3/renderer-core/
and left loader imports in the shared 26.3 Minecraft module. Each ledger lists what is allowed today. A file that is
not listed fails, so the state cannot grow. A listed file that is gone fails too, until its line is deleted, so a
removed entry cannot quietly come back."""

import re
from pathlib import Path

from .target import REPO_ROOT, fork_registry

HERE = Path(__file__).parent
# The same rule as upstream's CanonicalArchitectureTest.LOADER_IMPORT for the shared 1.21.1 module.
LOADER_IMPORT = re.compile(r"(?m)^\s*import\s+(?:static\s+)?net\.(?:fabricmc|minecraftforge|neoforged)\.")
# Build output a local Gradle run may leave inside a module, and the directory's own guide.
IGNORED_PARTS = {"build", ".gradle", "out"}
IGNORED_FILES = {"README.md"}


def ledger(name: str) -> list[str]:
    lines = (HERE / name).read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.startswith("#")]


def check(current: set[str], name: str, what: str) -> None:
    allowed = ledger(name)
    assert len(allowed) == len(set(allowed)), f"{name} lists a file twice"
    added = sorted(current - set(allowed))
    removed = sorted(set(allowed) - current)
    assert not added, f"{what} may not grow; these are not in tools/tests/fork/{name}: {added}"
    assert not removed, f"{what} shrank; delete these lines from tools/tests/fork/{name}: {removed}"


def test_the_renderer_core_copy_only_shrinks():
    core = REPO_ROOT / fork_registry().renderer_core
    current = {
        path.relative_to(core).as_posix()
        for path in core.rglob("*")
        if path.is_file()
        and not IGNORED_PARTS.intersection(path.relative_to(core).parts)
        and path.relative_to(core).as_posix() not in IGNORED_FILES
    }

    check(current, "renderer_core_copy_ledger.txt", f"The renderer core copy in {fork_registry().renderer_core}")


def test_loader_imports_in_the_shared_minecraft_module_only_shrink():
    module = REPO_ROOT / fork_registry().minecraft_module
    current = {
        path.relative_to(module).as_posix()
        for path in module.rglob("*.java")
        if not IGNORED_PARTS.intersection(path.relative_to(module).parts)
        and LOADER_IMPORT.search(path.read_text(encoding="utf-8"))
    }

    check(current, "minecraft_loader_imports_ledger.txt", f"Loader imports in {fork_registry().minecraft_module}")
