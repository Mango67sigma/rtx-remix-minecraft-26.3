# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""options.txt as the tools write it for the 26.3 target: its data version, its graphics preset, its fullscreen mode,
and a pack list with no built-in loader pack."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pytest
from conftest import FakeLauncher

from rtx_tools.benchmark import runner, screenshots

from .target import fork_registry

DATA_VERSION = fork_registry().minecraft_data_version


def options_value(options: str, key: str) -> str | None:
    """The value written for `key`, or None when the key is absent: Minecraft 26.3 reads no fullscreenResolution as
    "Current" but warns about an empty one, so substring assertions cannot tell absent from empty."""
    for line in options.splitlines():
        name, separator, value = line.partition(":")
        if separator and name == key:
            return value
    return None


@pytest.mark.usefixtures("fork_target")
class WriteOptionsTests(unittest.TestCase):
    def setUp(self):
        self.run_dir = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(patch.object(runner.util, "RUN_DIR", self.run_dir))
        self.options_path = self.run_dir / "options.txt"

    def written(self, config: dict | None = None, **kwargs) -> str:
        runner.write_options({"render": {}} if config is None else config, **kwargs)
        return self.options_path.read_text(encoding="utf-8")

    def written_options(self, render: dict) -> dict[str, str]:
        text = self.written({"render": render})
        return dict(line.split(":", 1) for line in text.splitlines() if ":" in line)

    def test_the_target_has_26_3s_options_layout(self):
        self.assertTrue(runner.options_split_graphics())
        self.assertFalse(runner.options_split_graphics(3955))
        self.assertTrue(runner.options_split_graphics(DATA_VERSION))

    def test_the_options_26_3_dropped_are_left_out(self):
        options = self.written()

        # hideBundleTutorial is gone from Options, and graphicsPreset replaced graphicsMode.
        self.assertIsNone(options_value(options, "hideBundleTutorial"))
        self.assertIsNone(options_value(options, "graphicsMode"))
        self.assertEqual(options_value(options, "graphicsPreset"), '"fancy"')

    def test_a_new_file_is_stamped_with_the_games_data_version(self):
        options = self.written()

        # world_version in 26.3's version.json, which the game stamps into the options.txt it saves.
        self.assertEqual(DATA_VERSION, 5023)
        self.assertEqual(options.splitlines()[0], f"version:{DATA_VERSION}")

    def test_an_existing_file_older_than_the_games_data_version_is_restamped(self):
        # 3955 is 1.21.1's. A file with no stamp reads as version 0, and an unreadable one is no better.
        for first_line in ("version:3955", "", "version:soon"):
            with self.subTest(first_line=first_line):
                self.options_path.write_text(
                    "\n".join(filter(None, [first_line, "graphicsMode:2", "key_key.jump:key.keyboard.space"])) + "\n",
                    encoding="utf-8",
                )

                options = self.written()

                self.assertEqual(options.splitlines()[0], f"version:{DATA_VERSION}")
                self.assertEqual(options.count("\nversion:") + options.startswith("version:"), 1)
                # The data fixers would have overwritten the preset of a file this old, so it is written beside the
                # stamp. Lines the harness does not own stay.
                self.assertEqual(options_value(options, "graphicsPreset"), '"fancy"')
                self.assertEqual(options_value(options, "key_key.jump"), "key.keyboard.space")

    def test_a_data_version_the_game_wrote_is_left_alone(self):
        for version in (str(DATA_VERSION), "5100"):
            with self.subTest(version=version):
                self.options_path.write_text(f'version:{version}\ngraphicsPreset:"custom"\n', encoding="utf-8")

                options = self.written()

                self.assertEqual(options.splitlines()[0], f"version:{version}")
                self.assertEqual(options_value(options, "graphicsPreset"), '"fancy"')

    def test_graphics_mode_keeps_its_numbers_and_picks_the_26_3_preset(self):
        for mode, preset in ((0, "fast"), (1, "fancy"), (2, "fabulous"), ("2", "fabulous"), (" Fast ", "fast")):
            with self.subTest(mode=mode):
                options = self.written_options({"graphicsMode": mode})

                self.assertEqual(f'"{preset}"', options["graphicsPreset"])
                self.assertNotIn("graphicsMode", options)

    def test_graphics_mode_also_writes_the_options_the_game_does_not_apply_for_a_preset(self):
        # Minecraft sets a preset's other options only when it is chosen in Video Settings. These are the values its
        # data fixer gives the options that 1.21.1's graphicsMode carried: 0 fast, 1 fancy, 2 fabulous.
        expected = {
            0: {"cutoutLeaves": "false", "weatherRadius": "5", "vignette": "false", "improvedTransparency": "false"},
            1: {"cutoutLeaves": "true", "weatherRadius": "10", "vignette": "true", "improvedTransparency": "false"},
            2: {"cutoutLeaves": "true", "weatherRadius": "10", "vignette": "true", "improvedTransparency": "true"},
        }
        for mode, values in expected.items():
            with self.subTest(mode=mode):
                options = self.written_options({"graphicsMode": mode})

                self.assertEqual(values, {key: options[key] for key in values})

    def test_a_graphics_mode_that_names_no_preset_fails_the_run(self):
        for mode in (3, -1, 1.5, True, "ultra", ""):
            with self.subTest(mode=mode), self.assertRaisesRegex(RuntimeError, "render.graphicsMode"):
                self.written_options({"graphicsMode": mode})

    def test_fullscreen_resolution_is_left_out_by_default(self):
        options = self.written({"render": {"overrideWidth": 1920, "overrideHeight": 1080}})

        self.assertIn("overrideWidth:1920", options)
        self.assertIn("overrideHeight:1080", options)
        # No line is how 26.3 is told to prefer no mode. An empty value is a mode to parse, which fails and logs
        # "Failed to parse video mode ''".
        self.assertNotIn("fullscreenResolution", options)

    def test_a_pinned_display_writes_26_3s_json_video_mode(self):
        options = self.written({"render": {"overrideWidth": 1920, "overrideHeight": 1080}}, pin_display=True)

        # What 26.3's VideoMode.write produces: a JSON object, where 1.21.1 wrote 1920x1080@60:24.
        self.assertEqual(
            options_value(options, "fullscreenResolution"),
            '{"width":1920,"height":1080,"red_bits":8,"green_bits":8,"blue_bits":8,"refresh_rate":60.0}',
        )

    def test_a_stale_fullscreen_resolution_is_cleared(self):
        # A pin from an earlier run, and the empty value that earlier tools wrote for no pin.
        for stale in (
            'fullscreenResolution:{"width":2560,"height":1440,"red_bits":8,"green_bits":8,"blue_bits":8,'
            '"refresh_rate":144.0}',
            "fullscreenResolution:",
        ):
            with self.subTest(stale=stale):
                self.options_path.write_text(f"{stale}\nkey_key.jump:key.keyboard.space\n", encoding="utf-8")

                # The window size is set, so no line can only come from an unpinned display; pinning would rewrite
                # it to 1920x1080.
                options = self.written({"render": {"overrideWidth": 1920, "overrideHeight": 1080}})

                self.assertNotIn("fullscreenResolution", options)
                self.assertEqual(options_value(options, "key_key.jump"), "key.keyboard.space")

    def test_a_stale_fullscreen_resolution_is_replaced_when_the_display_is_pinned(self):
        self.options_path.write_text("fullscreenResolution:\n", encoding="utf-8")

        options = self.written({"render": {"overrideWidth": 1920, "overrideHeight": 1080}}, pin_display=True)

        self.assertEqual(options.count("fullscreenResolution:"), 1)
        self.assertEqual(
            options_value(options, "fullscreenResolution"),
            '{"width":1920,"height":1080,"red_bits":8,"green_bits":8,"blue_bits":8,"refresh_rate":60.0}',
        )

    def test_no_fabric_pack_and_no_incompatible_pack_is_listed(self):
        # Fabric API 26.x loads the mod's resources as a pack of its own named for the mod, so there is no `fabric`
        # pack to list, and the game removes an entry for one with a warning. The art pack declares the game's format,
        # so nothing is listed as incompatible either.
        fresh = self.written(resource_pack_files=["rtx-remix-art"])

        self.options_path.write_text(
            'resourcePacks:["vanilla","fabric","file/rtx-remix-art"]\nincompatibleResourcePacks:[]\n',
            encoding="utf-8",
        )
        rewritten = self.written(resource_pack_files=["rtx-remix-art"])

        self.assertEqual(options_value(fresh, "resourcePacks"), '["vanilla","file/rtx-remix-art"]')
        self.assertIsNone(options_value(fresh, "incompatibleResourcePacks"))
        self.assertEqual(options_value(rewritten, "resourcePacks"), '["vanilla","file/rtx-remix-art"]')
        # The game keeps that line itself, so a file that has one keeps it.
        self.assertEqual(options_value(rewritten, "incompatibleResourcePacks"), "[]")

    def test_the_pack_order_is_vanilla_world_then_art(self):
        art = runner.ResourcePackSource(name="rtx-tools-art", file_name="rtx-tools-art", source_path=Path("art"))

        options = self.written(resource_pack_files=runner.append_art_pack_files(["world-demo-pbr"], art))

        packs = json.loads(options_value(options, "resourcePacks") or "null")
        self.assertEqual(packs, ["vanilla", "file/world-demo-pbr", "file/rtx-tools-art"])


def test_screenshot_provenance_names_the_mods_hidden_pack():
    # The built-in baseline of 26.3 Fabric is the mod's own hidden pack, which Fabric API names for the mod id.
    cases = (
        ({}, ["rtx-remix-renderer"]),
        ({"mode": "pinned", "name": "texture", "version": "0.55.0"}, ["rtx-remix-renderer", "texture 0.55.0"]),
        ({"mode": "local"}, ["rtx-remix-renderer", "a local art pack"]),
    )
    for resource_pack, expected in cases:
        assert screenshots.enabled_pack_names({"resourcePack": resource_pack}) == expected


class MainMenuArtPackTests(unittest.TestCase):
    """The main-menu launch through the real staging and options.txt, up to the game's start, with an art pack that was
    published for 1.21.1: the game must find it compatible, or it drops it from options.txt."""

    @pytest.fixture(autouse=True)
    def _launcher(self, fake_launcher: FakeLauncher) -> None:
        self.launcher = fake_launcher

    def test_the_game_starts_with_an_art_pack_it_keeps_and_no_fullscreen_mode_to_parse(self):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        pack = root / "art"
        pack.mkdir()
        legacy = json.dumps({"pack": {"pack_format": 34, "description": "PBR Test Pack"}})
        (pack / "pack.mcmeta").write_text(legacy, encoding="utf-8")
        run_dir = root / "run"
        config = {
            "render": {"overrideWidth": 1920, "overrideHeight": 1080},
            "resourcePack": {"mode": "local", "localPath": str(pack), "stageName": "rtx-remix-art"},
        }

        with (
            patch.object(runner.util, "RUN_DIR", run_dir),
            patch.object(runner, "load_config", return_value=config),
            patch.object(runner, "ensure_no_live_client"),
            patch.object(runner, "host_arch", return_value="win64"),
            patch.object(runner, "fetch_sdk", return_value=root / "d3d9.dll"),
            patch.object(runner, "gradle_env", return_value={}),
            patch.object(runner, "park_stale_runtime_state"),
        ):
            runner.launch_client_main_menu(sdk_variant="release", quiet=True)

        self.assertEqual(len(self.launcher.requests), 1)
        options = dict(
            line.split(":", 1)
            for line in (run_dir / "options.txt").read_text(encoding="utf-8").splitlines()
            if ":" in line
        )
        self.assertEqual(options["resourcePacks"], '["vanilla","file/rtx-remix-art"]')
        self.assertNotIn("incompatibleResourcePacks", options)
        self.assertNotIn("fullscreenResolution", options)
        staged = json.loads((run_dir / "resourcepacks" / "rtx-remix-art" / "pack.mcmeta").read_text(encoding="utf-8"))
        self.assertEqual(staged, {"pack": {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}})
        self.assertEqual((pack / "pack.mcmeta").read_text(encoding="utf-8"), legacy)
