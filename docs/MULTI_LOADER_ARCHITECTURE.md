# Multi-Loader And Version Architecture

This project is one canonical renderer reached through symmetric loader
adapters. The root `project-state.json` declares every supported Minecraft
target and its loader catalog. Minecraft 1.21.1 currently contains Fabric,
Forge, and NeoForge.

> **Fork note.** This guide is NVIDIA's and describes NVIDIA's targets. This
> checkout is the unofficial Minecraft 26.3 port, which builds 26.3 on Fabric
> only and departs from this design in stated ways; see
> [This Fork: The 26.3 Port](#this-fork-the-263-port) at the end.

## Ownership

There are four boundaries:

1. `renderer-core/` owns Remix scene policy, resources, frame ordering, queues,
   and budgets. It does not depend on Minecraft or a loader.
2. `versions/<version>/minecraft/` owns behavior shared by that Minecraft
   version: capture, lifecycle, menus, networking, and translation into
   renderer inputs.
3. Each `versions/<version>/<loader>/` module owns only registration, metadata,
   hook-signature translation, and a narrow provider for genuine loader
   differences.
4. The host graphics provider owns window, graphics-API, framebuffer-transfer,
   and presentation details. The current Minecraft host is Windows/OpenGL; that
   is not a property of the canonical scene renderer.

Equivalent policy in multiple loader modules belongs in a shared layer.
Conversely, Fabric API setup, Forge JarJar/remapping, and NeoForge's ordinary
module JAR are real provider differences and should not be hidden behind
generic conditionals. The same rule applies below APIs: Fabric and Forge select
the shared vanilla-shaped section-compiler mixin, while NeoForge's patched
compiler signature has a thin mixin that delegates to the same canonical
section-capture implementation.

## Project State And Loader Catalog

`project-state.json` is the machine-readable state of the whole checkout. Its
schema-versioned contents identify the mod version, default Minecraft target,
and a keyed catalog of Minecraft versions. Each version declares its support
tier, shared integration module, default loader, Java target, and loader map.
A loader entry supplies the Gradle module and tasks, artifact and metadata
information, Prism component, companion dependencies, and provider identity
needed by build, tooling, CI, and installation.

Consumers enumerate the catalog; they do not carry a second closed loader list.
Unknown schemas, duplicate IDs, missing providers, or disagreement between a
declaration and its module fail clearly. Adding a loader therefore means adding
one declaration, one module, and only the provider behavior that is truly
different. It does not mean copying the renderer.

Minecraft 1.21.1 and Fabric remain the defaults for compatibility. Fabric,
Forge, and NeoForge are the loaders declared by that target, not a permanent
closed list.

## Build, Tools, And Releases

The neutral Gradle root applies no loader plugin. Shared conventions own common
sources, tests, licenses, runtime payload, and package contracts; each loader
module keeps its plugin and remapping verification local. Gradle selects one
Minecraft target with `-PmcVersion` and can narrow configuration to one loader
with `-Ploader`. The resulting JAR contains only that target's sources.

`renderer-core` deliberately remains within the Java 8 source-language subset
even though the current Minecraft target requires Java 21. This keeps stable
renderer policy reusable by an older Minecraft target without creating a
second core; version and loader integrations may use their target's Java level.

The root Gradle wrapper and loader plugins are one compatibility set. Gradle
8.11.1 is the newest shared baseline supported by the pinned ForgeGradle 6
toolchain, while Fabric Loom 1.9 remains on that Gradle generation. The public
branch therefore does not inherit main's Gradle 9/Loom 1.15 pair. Upgrade these
pins together only after every declared loader configures, builds, tests, and
packages on the proposed set.

```powershell
.\gradlew.bat build "-PmcVersion=1.21.1" "-Ploader=fabric"
.\gradlew.bat package "-PmcVersion=1.21.1" "-Ploader=forge"
tools\rtx.bat launch --minecraft-version 1.21.1 --loader neoforge
```

CI validates the checked-out manifest, derives its target matrix, then builds,
tests, and packages every supported Minecraft-version/loader pair.

Published loader JARs include the loader ID, Minecraft target, and mod version;
the installer bundle includes the Minecraft target and mod version. Release
staging contains one JAR per declared loader plus the target's other release
content, so asset count is derived rather than fixed.

## Version Folders

Every supported version is self-describing and buildable in the same checkout:

```text
versions/
  1.21.1/
    minecraft/
    fabric/
    forge/
    neoforge/
```

A port adds a catalog entry, a version-specific Minecraft integration, and the
loader adapters that target supports. It does not copy `renderer-core/` or use
source-level version switches. `tools\rtx.bat versions` reports the catalog;
`tools\rtx.bat target-status` summarizes proportionate automated-contract,
build, artifact, and automated-live-run evidence for a selected target. These
commands report state; they do not rewrite source or treat a log as proof of
visual parity.

## Semantic Parity

Source equality is not the goal across Minecraft versions or loader providers.
Semantic parity is: the same scene decisions, ownership invariants, and visible
result for equivalent game state. Deterministic world, camera, and shot fixtures
provide reusable evidence across loaders and versions. Automated tests
cover contracts and artifacts; recorded fixture results and an actual launch
cover behavior that compilation cannot prove.

## Why There Is No Preprocessor

The current split already isolates stable renderer policy, version-specific
Minecraft integration, and small loader providers. Gradle performs
configuration-time module selection, producing a lean target without
preprocessor directives in Java sources. Stonecutter or another source
preprocessor would add conditional source paths before repeated port work
demonstrates that they reduce maintenance. Revisit that choice only with
measured, recurring duplication that the existing boundaries cannot absorb.

## This Fork: The 26.3 Port

*This section is the fork's own. Everything above it is NVIDIA's text, kept
as NVIDIA wrote it so that its changes merge cleanly, and describes NVIDIA's
targets, not what this checkout builds.*

This checkout is the unofficial community port of the renderer to Minecraft
26.3, not affiliated with or supported by NVIDIA. It builds, tests, packages,
and releases Minecraft 26.3 on Fabric only, with Java 25 and the Gradle 9.8.2
wrapper. Upstream's 1.21.1 sources (`versions/1.21.1/`) and shared core
(`renderer-core/`) are carried byte-identical to NVIDIA's so that NVIDIA's
changes merge cleanly. They are never edited here, and they are not built
here: one Gradle wrapper serves the checkout, 26.3 needs Gradle 9 and Java 25,
and upstream's 1.21.1 targets need Gradle 8 (ForgeGradle 6, Loom 1.9). The
catalog records that in `builtVersions`.

### Layout

```text
renderer-core/            NVIDIA's shared core: carried, not built here
versions/
  1.21.1/                 NVIDIA's 1.21.1 integration and loaders: carried, not built here
  26.3/
    minecraft/            the 26.3 integration (main/java, main/resources, test/java)
    fabric/               Fabric entry points, metadata, access widener, Gradle project
    renderer-core/        the port's own copy of the core (src, main/resources, test)
```

`versions/26.3/fabric/` is the Gradle project `:versions:26.3:fabric`. Like
NVIDIA's loader modules it applies `gradle/loader-module.gradle`, which compiles
one source set from the minecraft module, the renderer core, and
`rtxremix-java/src`. For 26.3 the renderer core is
`versions/26.3/renderer-core/`, named by the catalog's `rendererCore`, so the
shared `renderer-core/` is not compiled into the 26.3 JAR. Every class keeps
its `com.nvidia.rtxremix` package; a file sits in the directory NVIDIA moved
its counterpart to. The port's Fabric-specific code is not yet confined to
`fabric/`: some classes in `versions/26.3/minecraft/` still import Fabric.

### The Transitional Copy Of The Core

The guide above says a port does not copy `renderer-core/`. This port still
does: `versions/26.3/renderer-core/` is the 26.3 counterpart of
`renderer-core/`, and it is not limited to Java 8. Most of its files sit at
their upstream counterpart's relative path, while the files upstream renamed or
rewrote when it extracted the core keep the port's names or live in
`versions/26.3/minecraft/`. That is a deliberate, transitional state, and
converging the port onto the shared core is the next project. Until then:

- A fix to shared renderer logic for 26.3 goes into the matching file under
  `versions/26.3/renderer-core/`. Never edit `renderer-core/` or
  `versions/1.21.1/`.
- Nothing new is added to `versions/26.3/renderer-core/`, and no new
  `net.fabricmc` import appears in `versions/26.3/minecraft/`. The ledgers in
  `tools/tests/fork/test_transition_ledgers.py` enforce both: their lists may
  lose entries, never gain them. New 26.3 code and tests go in
  `versions/26.3/minecraft/` or `versions/26.3/fabric/`.
- An upstream change that a merge brings into `renderer-core/` or
  `versions/1.21.1/` is a porting to-do for its counterpart under
  `versions/26.3/`, found by class and content rather than by path.
  [Syncing With Upstream](../CONTRIBUTING.md#syncing-with-upstream) describes
  the merge and how to list those changes with `git diff` over the two
  directories.

[`versions/26.3/renderer-core/README.md`](../versions/26.3/renderer-core/README.md)
states the same rule beside the copy.

### Catalog Fields The Fork Adds

Each field has a default that leaves NVIDIA's 1.21.1 block valid unchanged.

| Field | Meaning | Default when absent | Readers |
|---|---|---|---|
| `builtVersions` (root) | The versions this checkout builds, tests, and releases; any other declared version is carried as source and catalog data only. Must list the default version. Here `["26.3"]` | Every declared version | `settings.gradle` refuses an unlisted `-PmcVersion` unless `-PbuildUnlistedVersion=true`; `validate-project-state.ps1` builds the CI matrix and release target from it; `rtx versions` and `target-status` report it |
| `versions.<v>.rendererCore` | Repository-relative directory whose `src`, `main/resources`, and `test` are the target's renderer core. Here `versions/26.3/renderer-core` | `renderer-core` | `settings.gradle`, `gradle/loader-module.gradle`, the validator, the tools |
| `versions.<v>.minecraft.resourcePackFormat` | The resource pack format the target's RTX Remix Materials zip and the tools' staged art pack declare, as `min_format` = `max_format`, with `pack_format` and `supported_formats` removed. Here 97 | The pinned pack's own `pack.mcmeta` | Root `build.gradle` (`materialsPackMeta`), the tools' art-pack staging |
| `versions.<v>.loaders.<l>.builtinResourcePackListed` | Whether the loader's `builtinResourcePack` is written to `options.txt`. `false` for 26.3 Fabric, which keeps mod resources in a hidden pack above vanilla; a stale `fabric` entry is removed instead | `true` | The tools' `options.txt` writer, `packaging/installer.ps1`, `packaging/migrate_resource_packs.ps1`, the validator |

The mod version is `mod.version` (0.1.3 for this fork's release line) and the
default target `defaultMinecraftVersion` (26.3).

### Commands And Artifacts

A bare `.\gradlew.bat build`, `test`, or `package -Parch=win64` builds the
26.3 Fabric target; `-PmcVersion` and `-Ploader` are optional, and a dotted
`-PmcVersion` must be quoted in PowerShell. `-PmcVersion=1.21.1` stops at
settings evaluation with a message that the version is carried from upstream
and not built in this checkout.

| Artifact | Path |
|---|---|
| Development JAR | `versions/26.3/fabric/build/libs/rtx-remix-renderer-<buildVersion>.jar` |
| Self-contained JAR | `versions/26.3/fabric/build/distributions/rtx-remix-renderer-fabric-mc26.3-<buildVersion>.jar` |
| Installer bundle | `build/distributions/rtx-remix-renderer-mc26.3-<buildVersion>-installer.zip`, with the JAR under `mods/fabric/` |
| Installed mod | `<game>/mods/rtx-remix-renderer-fabric.jar`, replacing the versioned `rtx-remix-renderer-*.jar` earlier fork releases installed |

`tools\rtx.bat launch`, `run`, and `install` default to 26.3 and Fabric. The
installer in the bundle offers Minecraft 26.3 alone and does not ask for a
loader. CI builds only the versions in `builtVersions`, and the release
workflow releases the catalog default, tagged `v<mod.version>`.

### Boundaries That Still Hold

Every boundary and invariant of the port holds in the new layout, with paths
under `versions/26.3/`:

- Only mixins live in the mixin package,
  `versions/26.3/minecraft/main/java/com/nvidia/rtxremix/minecraft/mixin/`
  and its Fabric counterpart (`MixinPackageBoundaryTest`).
- Nothing outside `lod/dh/` and `mixin/distanthorizons/` names a Distant
  Horizons type, and nothing outside `entity/ec/` an Entity Culling type
  (`EntityCullingBoundaryTest`); both mods stay compile-only dependencies of
  `versions/26.3/fabric/build.gradle`, never packaged.
- `rtxremix-java/` imports no Minecraft or loader API
  (`RemixJavaBoundaryTest`).

[AGENTS.md](../AGENTS.md) carries the full architecture and invariants of the
port.
