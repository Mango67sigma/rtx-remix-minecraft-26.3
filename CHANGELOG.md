# Changelog

Notable changes to RTX Remix for Minecraft, newest first. Changes that a
player, a map author, or a contributor would notice go here; internal
refactoring does not.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The version in a release is the `mod_version` in `gradle.properties`, and it is
also part of every mod file name.

## 0.1.3 - 2026-11-06

NVIDIA's changes to the 1.21.1 project through 2026-11-05 are merged and
carried over to 26.3.

### Changed

- A release ships as an installer bundle,
  `rtx-remix-renderer-<version>-installer.zip`, that also carries the RTX Remix
  Materials texture pack and the RTX Remix Showcase World, both optional.
  Install, Upgrade, and Repair offer the mod, the pack, and the
  world as a checklist with all three ticked. The pack goes in turned on, above
  the mod's built-in textures and below your own packs; the world goes into
  `saves\NVIDIA RTX Demo` and never over a copy already there. The release also
  lists the mod, the pack, and the world as files of their own. The pack was
  made for 1.21.1, so its copy declares 26.3's resource pack format, and
  Minecraft upgrades the world, saved in 1.21.1, the first time it is opened.
- The installer runs on PCs that give every program administrator rights, such
  as with User Account Control turned off or in the built-in Administrator
  account; it still refuses administrator rights as an account other than the
  one signed in. A Prism Launcher it does not find, such as a portable copy kept
  anywhere, can be named at the launcher question with Use a Prism I already
  have, and later runs remember it. A portable Prism it does find gets the
  instance in its own folder rather than in `%APPDATA%\PrismLauncher`.
- End portals and end gateways show their speckled surface, path traced, where
  the frame showed what lay beneath. The pattern is fixed to the world and does
  not drift with the time of day as vanilla's does.
- DLSS Frame Generation is on by default and presents on a graphics queue, so
  overlays that draw at present time, such as RivaTuner's, no longer lose the
  graphics device. Benchmark runs keep it off, since they measure rendered
  frames.
- RTX Remix presents the picture through a window of its own inside Minecraft's
  window, on a newer RTX Remix runtime, while Minecraft keeps its OpenGL window.
- A native crash inside an RTX Remix call reaches RTX Remix's crash reporting,
  when it is on, where the Java runtime used to close the game first and nothing
  was sent. Before relaunching after a crash, copy `remix-dxvk.log`: its Sentry
  session id finds the report.
- The minidump the mod writes to `crash\` after a native crash no longer copies
  the memory that thread stacks point at, which could include the Minecraft
  sign-in token the launcher passes to the game. Dumps from 0.1.2 and earlier
  can still hold it, so bug reports ask for the text crash files and leave the
  `.dmp` files out. `-Drtxremix.crashDumpDetail=true` brings the full detail
  back for dumps that stay private.
- An upgrade delivers changed defaults for the shipped lighting profiles under
  `rtx-remix/mods/minecraft-logic/`: a profile that still holds an earlier
  release's default is updated on the next launch, and profiles you edited are
  kept as before.
- On a first launch, while another program such as an antivirus scanner holds
  the freshly unpacked runtime, the mod retries putting it in place for about
  ten seconds, where it used to run without path tracing until the next launch.

### Fixed

- In single player, loading a sky-light section below every one loaded before
  no longer makes the integrated server's light engine throw.

## 0.1.2 - 2026-11-01

### Added

- Entity Culling works alongside the path tracer: what it hides from the camera
  stays in the path-traced scene for shadows and reflections. A block entity it
  hides keeps the model it was last drawn with, where before it vanished, and an
  entity it hides replays its last pose while moving with its live position,
  rather than being captured afresh every frame, which keeps most of Entity
  Culling's saving. A placed Copper Beacon it hides keeps its light and its
  mount; only its yoke and lens leave the scene, so a head held at an older angle
  never stands in its own beam. `-Drtxremix.entity.entityCulling=false` captures
  as if Entity Culling were absent.

## 0.1.1 - 2026-10-29

### Added

- Distant Horizons 3.3.4, experimental: with it installed, the distant terrain
  it builds beyond Minecraft's render distance is path traced instead of drawn
  by Distant Horizons, so far land casts shadows, shows in reflections and takes
  the sky's light. Distant Horizons still decides which terrain exists and at
  what detail. Its terrain gives way exactly where Minecraft's own is drawn,
  changes detail without holes, keeps to a sixteenth of the graphics card's
  memory, nearest first, and the view fade moves out to its radius. Turning
  path tracing off hands the terrain back to Distant Horizons.
  `-Drtxremix.lod.distantHorizons=false` leaves it to Distant Horizons
  altogether. With Distant Horizons installed the game needs a 6 GB heap; on
  the installer's 2 GB its terrain generation stalls the game in garbage
  collection.

### Changed

- NVIDIA's changes to the 1.21.1 project through 2026-10-28 are merged: the
  shared Java facade under `rtxremix-java/` stays compatible with Java 8, the
  developer tools' `tools\rtx.bat` clears PowerShell 7's module path, and an
  experimental Forge 1.12.2 bootstrap with a Windows OpenGL interop for legacy
  Java clients lives under `forge-1.12.2/` with its own build. The release
  archive holds the Minecraft 26.3 mod only.

## 0.1.0 - 2026-10-26

The first release of the Minecraft 26.3 port.

### Changed

- The mod targets Minecraft Java Edition 26.3 in place of 1.21.1: Java 25,
  Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, and Mojang's official names
  for Minecraft's code.
- The installer moves a Prism instance an earlier release made to Minecraft
  26.3, Fabric Loader 0.20.1, and Java 25. On Windows ARM64 it downloads
  Microsoft Build of OpenJDK 25, because Eclipse Temurin publishes no build of
  Java 25 for it.
- The developer tools write `options.txt` the way 26.3 reads it, so
  `render.graphicsMode` still picks the graphics preset.
- Enchanted items, tridents, shields and armour render without their glint.
  Since 26.2 the glint is a second sampler of the surface's own draw, and a
  separate layer in front of the surface hides it from the path tracer, so the
  capture bakes the glint into the surface's emission instead. The bake is a
  static sheen and is off until it scrolls as vanilla's does;
  `-Drtxremix.entity.glint=true` turns it on.

## 0.1.0 (NVIDIA, Minecraft 1.21.1) - 2026-10-23

The first public release, a beta.

### Added

- Path-traced rendering of Minecraft Java Edition 1.21.1 through RTX Remix:
  terrain, entities and block entities, particles, the sky, and clouds, with
  baseline LabPBR textures built into the mod JAR through Fabric's `fabric` pack.
- Lighting that follows the game: time of day, weather, being underwater, and
  per-dimension skies, with the End's constant dark dome and a black sky with
  no ambient where a dimension has none. Map authors can give zones and biomes
  their own looks through gameplay-driven conf profiles installed beside the
  mod, with an authoring guide.
- Block lights from resource-pack rules, a Light Reach setting and an Auto
  light cull distance, flames and lights on hand-held items, and the `G` key
  to put out and relight what the local player holds.
- The Copper Beacon, a placeable and craftable directional lamp: dye and
  saturation, intensity, cone angle, redstone control, and static, rotating,
  oscillating, or patrolling motion that every player sees the same way.
- An RTX Remix settings screen in Options, a button into Remix's own Graphics
  Settings, the `/rtx` command tree, `F8` to toggle path tracing, and
  session-only quality presets through `/rtx preset <name>` or
  `-Drtxremix.preset=<name>`.
- Camera paths: record a flythrough by walking it and replay it on wall-clock
  time or one fixed step per frame. Still shots keep a pose together with its
  time of day, weather, path-tracing state, and cloud style.
- Procedural clouds, selectable in place of vanilla's.
- Steadier frames at high render distance: paced chunk delivery and unloads
  in single-player, a hold behind the loading screen after joining a world,
  and allocation-free hot paths that keep garbage collection out of the frame.
- A self-contained mod JAR that any Fabric launcher can install on its own,
  and a Windows installer for Prism Launcher and the Microsoft launcher that
  sets up Java, Fabric, the mod, and tuned JVM arguments, and repairs,
  upgrades, uninstalls, or exports an install.
- Developer tools under `tools/` through the `rtx` CLI: launching, benchmarks
  on the pinned demo world, built by [BlockWorks](https://www.blockworks.uk/),
  and the LabPBR art pack, created by an art team at NVIDIA, or on locally
  staged fixtures, JFR and Tracy profiling, and the contributor checks.
- Developer tools launch Minecraft through a Prism Launcher data folder for
  each checkout, signed in to a Microsoft account that owns Minecraft: Java
  Edition. `tools\rtx.bat prism signin` signs a checkout in, and
  `launch.jvmArgs` carries developer JVM options.
