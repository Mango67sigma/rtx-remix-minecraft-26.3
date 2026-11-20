# SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
# SPDX-License-Identifier: Apache-2.0

"""The art pack as the tools stage it for the 26.3 target: its pack.mcmeta declares the catalog's
minecraft.resourcePackFormat, because 26.3 drops a pack made for an earlier game from options.txt."""

import codecs
import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any
from unittest.mock import patch

from rtx_tools import loader_registry
from rtx_tools.benchmark import runner

from .target import FORK_TARGET

UPSTREAM_TARGET = "1.21.1"

# What the art pack published for 1.21.1 carries: pack_format alone, where 26.3 wants min_format and max_format.
LEGACY_METADATA = {"pack": {"pack_format": 34, "description": "PBR Test Pack"}}


def write_folder_pack(path: Path, metadata: object = LEGACY_METADATA) -> Path:
    texture = path / "assets" / "minecraft" / "textures" / "block" / "stone.png"
    texture.parent.mkdir(parents=True)
    texture.write_bytes(b"texture")
    (path / "pack.mcmeta").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return path


def write_zip_pack(path: Path, metadata: object | None = LEGACY_METADATA) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        if metadata is not None:
            archive.writestr("pack.mcmeta", json.dumps(metadata))
        archive.writestr("assets/", b"")
        archive.writestr("assets/minecraft/textures/block/stone.png", b"texture", compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr("assets/minecraft/textures/block/dirt.png", b"dirt", compress_type=zipfile.ZIP_STORED)
    return path


def folder_metadata(pack: Path) -> dict[str, Any]:
    return json.loads((pack / "pack.mcmeta").read_text(encoding="utf-8"))


def zip_members(path: Path) -> dict[str, tuple[bytes, int]]:
    with zipfile.ZipFile(path) as archive:
        return {member.filename: (archive.read(member), member.compress_type) for member in archive.infolist()}


class AmendedPackMetadataTests(unittest.TestCase):
    def amended(self, document: object, major: int = 97) -> dict[str, Any] | None:
        text = runner.amended_pack_metadata(json.dumps(document), major)
        return None if text is None else json.loads(text)

    def range_for(self, major: int) -> dict[str, int]:
        return {"min_format": major, "max_format": major}

    def test_a_pack_made_for_an_earlier_game_declares_the_range_in_place_of_pack_format(self):
        self.assertEqual(
            self.amended(LEGACY_METADATA),
            {"pack": {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}},
        )

    def test_supported_formats_go_with_pack_format_because_26_3_refuses_them_above_format_64(self):
        document = {"pack": {"pack_format": 34, "supported_formats": [34, 64], "description": "Multi-version pack"}}

        self.assertEqual(
            self.amended(document),
            {"pack": {"description": "Multi-version pack", "min_format": 97, "max_format": 97}},
        )

    def test_the_description_and_every_other_key_stay(self):
        description = {"text": "PBR", "color": "gold"}
        document = {
            "pack": {"pack_format": 34, "description": description, "future_key": [1, 2]},
            "language": {"en_us": {"name": "English"}},
            "filter": {"block": [{"namespace": "minecraft"}]},
        }

        amended = self.amended(document)

        assert amended is not None
        self.assertEqual(amended["pack"], {"description": description, "future_key": [1, 2], **self.range_for(97)})
        self.assertEqual(amended["language"], document["language"])
        self.assertEqual(amended["filter"], document["filter"])

    def test_a_pack_that_declares_the_range_already_comes_back_as_none(self):
        self.assertIsNone(self.amended({"pack": {"description": "x", **self.range_for(97)}}))
        # Key order and layout do not matter, only what is declared.
        self.assertIsNone(
            runner.amended_pack_metadata('{"pack":{"max_format":97,"description":"x","min_format":97}}', 97)
        )

    def test_a_range_of_another_major_is_replaced_by_this_one_and_then_stands(self):
        for earlier in (96, 98):
            with self.subTest(earlier=earlier):
                amended = self.amended({"pack": {"description": "x", **self.range_for(earlier)}})

                assert amended is not None
                self.assertEqual(amended["pack"], {"description": "x", **self.range_for(97)})
                self.assertIsNone(self.amended(amended))

    def test_text_that_is_not_a_pack_mcmeta_raises_value_error(self):
        for text in ("", "not json", "[]", "{}", '{"pack": []}', '{"pack": "x"}', '{"language": {}}'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                runner.amended_pack_metadata(text, 97)


class DeclarePackFormatTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))

    def test_a_folder_pack_is_amended_in_place_and_nothing_else_changes(self):
        pack = write_folder_pack(self.root / "art")

        self.assertTrue(runner.declare_pack_format(pack, 97))

        self.assertEqual(
            folder_metadata(pack), {"pack": {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}}
        )
        self.assertEqual((pack / "assets" / "minecraft" / "textures" / "block" / "stone.png").read_bytes(), b"texture")

    def test_declaring_again_writes_nothing(self):
        pack = write_folder_pack(self.root / "art")
        runner.declare_pack_format(pack, 97)
        declared = (pack / "pack.mcmeta").stat()

        self.assertFalse(runner.declare_pack_format(pack, 97))

        again = (pack / "pack.mcmeta").stat()
        self.assertEqual((again.st_mtime_ns, again.st_size), (declared.st_mtime_ns, declared.st_size))

    def test_a_byte_order_mark_is_read_and_not_written_back(self):
        pack = self.root / "art"
        pack.mkdir()
        (pack / "pack.mcmeta").write_bytes(codecs.BOM_UTF8 + json.dumps(LEGACY_METADATA).encode("utf-8"))

        self.assertTrue(runner.declare_pack_format(pack, 97))

        self.assertFalse((pack / "pack.mcmeta").read_bytes().startswith(codecs.BOM_UTF8))
        self.assertEqual(folder_metadata(pack)["pack"]["min_format"], 97)

    def test_a_zip_pack_is_rewritten_with_every_other_member_as_it_was(self):
        pack = write_zip_pack(self.root / "art.zip")
        before = zip_members(pack)

        self.assertTrue(runner.declare_pack_format(pack, 97))

        after = zip_members(pack)
        declared = after.pop("pack.mcmeta")
        before.pop("pack.mcmeta")
        self.assertEqual(
            json.loads(declared[0]), {"pack": {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}}
        )
        self.assertEqual(after, before)
        self.assertEqual([path.name for path in self.root.iterdir()], ["art.zip"])

    def test_declaring_a_zip_again_leaves_the_file_alone(self):
        pack = write_zip_pack(self.root / "art.zip")
        runner.declare_pack_format(pack, 97)
        declared = pack.read_bytes()

        self.assertFalse(runner.declare_pack_format(pack, 97))

        self.assertEqual(pack.read_bytes(), declared)

    def test_a_zip_that_fails_part_way_is_left_as_it_was_with_no_half_written_archive(self):
        pack = write_zip_pack(self.root / "art.zip")
        with zipfile.ZipFile(pack, "a") as archive:
            archive.writestr("zz-damaged.bin", b"DAMAGED-PAYLOAD", compress_type=zipfile.ZIP_STORED)
        damaged = bytearray(pack.read_bytes())
        damaged[damaged.index(b"DAMAGED-PAYLOAD")] ^= 0xFF
        pack.write_bytes(damaged)

        with self.assertRaisesRegex(RuntimeError, "cannot declare resource pack format 97"):
            runner.declare_pack_format(pack, 97)

        self.assertEqual(pack.read_bytes(), bytes(damaged))
        self.assertEqual([path.name for path in self.root.iterdir()], ["art.zip"])

    def test_metadata_that_cannot_be_amended_stops_the_stage_and_names_the_pack(self):
        folder_without_section = write_folder_pack(self.root / "no-section", {"language": {}})
        folder_not_json = write_folder_pack(self.root / "not-json")
        (folder_not_json / "pack.mcmeta").write_text("{pack: nope", encoding="utf-8")
        zip_without_metadata = write_zip_pack(self.root / "no-metadata.zip", None)
        not_a_zip = self.root / "not-a-zip.zip"
        not_a_zip.write_bytes(b"zip")
        for pack in (folder_without_section, folder_not_json, zip_without_metadata, not_a_zip):
            with self.subTest(pack=pack.name):
                with self.assertRaisesRegex(RuntimeError, "cannot declare resource pack format 97") as raised:
                    runner.declare_pack_format(pack, 97)
                self.assertIn(str(pack), str(raised.exception))
        # None of them leaves a half-written archive behind.
        self.assertEqual(
            sorted(path.name for path in self.root.iterdir()),
            ["no-metadata.zip", "no-section", "not-a-zip.zip", "not-json"],
        )


class TargetResourcePackFormatTests(unittest.TestCase):
    def test_the_format_is_the_catalogs_for_the_selected_target(self):
        with patch.dict(os.environ, {"RTXREMIX_MINECRAFT_VERSION": FORK_TARGET}):
            self.assertEqual(runner.target_resource_pack_format(), 97)
        with patch.dict(os.environ, {"RTXREMIX_MINECRAFT_VERSION": UPSTREAM_TARGET}):
            self.assertIsNone(runner.target_resource_pack_format())

    def test_the_catalog_format_is_the_26_3_one(self):
        registry = loader_registry.load_registry(minecraft_version=FORK_TARGET)
        self.assertEqual(registry.resource_pack_format, 97)


class StagedArtPackTests(unittest.TestCase):
    """The art pack as the launch stages it, from a source that stays as it was published."""

    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.run_dir = self.root / "run"
        self.source = write_folder_pack(self.root / "source" / "texture_0.58.0")
        self.art = runner.ResourcePackSource(name="rtx-remix-art", file_name="rtx-remix-art", source_path=self.source)
        self.enterContext(patch.object(runner.util, "RUN_DIR", self.run_dir))
        self.enterContext(patch.dict(os.environ, {"RTXREMIX_MINECRAFT_VERSION": FORK_TARGET}))

    def stage(self, art: runner.ResourcePackSource | None = None) -> list[str]:
        return runner.stage_resource_packs({}, None, self.run_dir, art or self.art)

    def test_the_staged_copy_declares_the_games_format_and_the_source_keeps_its_own(self):
        source_metadata = (self.source / "pack.mcmeta").read_bytes()

        self.assertEqual(self.stage(), ["rtx-remix-art"])

        staged = self.run_dir / "resourcepacks" / "rtx-remix-art"
        self.assertEqual(
            folder_metadata(staged), {"pack": {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}}
        )
        self.assertEqual(
            (staged / "assets" / "minecraft" / "textures" / "block" / "stone.png").read_bytes(), b"texture"
        )
        self.assertEqual((self.source / "pack.mcmeta").read_bytes(), source_metadata)

    def test_staging_replaces_a_copy_staged_for_an_earlier_game(self):
        earlier = write_folder_pack(self.run_dir / "resourcepacks" / "rtx-remix-art")
        (earlier / "left-over.txt").write_text("from an earlier pack", encoding="utf-8")

        self.stage()

        self.assertEqual(
            folder_metadata(earlier)["pack"], {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}
        )
        self.assertFalse((earlier / "left-over.txt").exists())

    def test_staging_again_leaves_the_same_pack(self):
        self.stage()
        staged = self.run_dir / "resourcepacks" / "rtx-remix-art" / "pack.mcmeta"
        first = staged.read_bytes()

        self.stage()

        self.assertEqual(staged.read_bytes(), first)

    def test_the_format_follows_the_catalog(self):
        with patch.object(runner, "target_resource_pack_format", return_value=98):
            self.stage()

        staged = self.run_dir / "resourcepacks" / "rtx-remix-art"
        self.assertEqual(folder_metadata(staged)["pack"]["min_format"], 98)
        self.assertEqual(folder_metadata(staged)["pack"]["max_format"], 98)

    def test_a_zip_art_pack_keeps_its_file_name_and_declares_the_format(self):
        source = write_zip_pack(self.root / "source" / "texture.zip")
        source_bytes = source.read_bytes()
        art = runner.ResourcePackSource(name="rtx-remix-art", file_name="texture.zip", source_path=source)

        self.assertEqual(self.stage(art), ["texture.zip"])

        staged = self.run_dir / "resourcepacks" / "texture.zip"
        self.assertEqual(
            json.loads(zip_members(staged)["pack.mcmeta"][0]),
            {"pack": {"description": "PBR Test Pack", "min_format": 97, "max_format": 97}},
        )
        self.assertEqual(source.read_bytes(), source_bytes)

    def test_an_art_pack_whose_metadata_cannot_be_amended_stops_staging(self):
        (self.source / "pack.mcmeta").write_text("{}", encoding="utf-8")

        with self.assertRaisesRegex(RuntimeError, "cannot declare resource pack format 97"):
            self.stage()

    def test_no_art_pack_asks_the_catalog_for_nothing(self):
        refuse = AssertionError("the target's format is only needed for an art pack")
        with patch.object(runner, "target_resource_pack_format", side_effect=refuse):
            self.assertEqual(runner.stage_resource_packs({}, None, self.run_dir), [])

    def test_a_target_without_a_format_stages_the_pack_as_published(self):
        source_metadata = (self.source / "pack.mcmeta").read_bytes()
        with patch.dict(os.environ, {"RTXREMIX_MINECRAFT_VERSION": UPSTREAM_TARGET}):
            self.stage()

        staged = self.run_dir / "resourcepacks" / "rtx-remix-art" / "pack.mcmeta"
        self.assertEqual(staged.read_bytes(), source_metadata)

    def test_a_world_pack_is_copied_as_authored(self):
        world_dir = self.root / "worlds" / "DemoWorld"
        world_pack = write_folder_pack(world_dir / "resourcepacks" / "world-demo-pbr")
        selection = runner.BenchmarkSelection(
            world="DemoWorld",
            camera="simple",
            world_dir=world_dir,
            world_path=world_dir / "world",
            cameras_dir=world_dir / "cameras",
            camera_path=world_dir / "cameras" / "simple.json",
        )

        enabled = runner.stage_resource_packs({"resourcePacks": ["world-demo-pbr"]}, selection, self.run_dir, self.art)

        self.assertEqual(enabled, ["world-demo-pbr", "rtx-remix-art"])
        staged = self.run_dir / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta"
        self.assertEqual(staged.read_bytes(), (world_pack / "pack.mcmeta").read_bytes())
        self.assertEqual(folder_metadata(staged.parent), LEGACY_METADATA)
