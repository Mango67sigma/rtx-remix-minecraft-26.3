# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

import pytest

from .target import FORK_TARGET


@pytest.fixture(autouse=True)
def fork_target(no_cached_launcher: None, monkeypatch: pytest.MonkeyPatch) -> str:
    """Select the fork's target for code that reads the catalog without naming a version, after tests/conftest.py set
    the 1.21.1 reference target."""
    monkeypatch.setenv("RTXREMIX_MINECRAFT_VERSION", FORK_TARGET)
    return FORK_TARGET
