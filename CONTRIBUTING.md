# Contributing to RTX Remix for Minecraft

This project is maintained by NVIDIA and released under the Apache License,
Version 2.0. Contributions are welcome.

This repository is an unofficial community port of NVIDIA's project to
Minecraft 26.3, not affiliated with or supported by NVIDIA. Contributions to
the port are made here, on
[this repository](https://github.com/Mango67sigma/rtx-remix-minecraft-26.3);
[Syncing With Upstream](#syncing-with-upstream) describes how NVIDIA's own
changes reach it.

## Which Repository

The renderer spans three layers across two repositories, so the first question
for any change is which repository it belongs in.

- Path tracing, denoising, upscaling, and the Remix C API itself live in
  [dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix).
- The Minecraft-agnostic Java facade, API types, marshalling, and JNI bridge
  are tracked here under `rtxremix-java/`. Gradle acquires missing runtimes
  from the package pins in `packman/remix-sdk.packman.xml`.
- Everything that reads Minecraft 26.3 state and hands it to Remix lives here
  under `versions/26.3/`: the terrain, entity, texture, particle, and cloud
  pipelines, the gameplay-driven conf layers, the settings screen, and the
  mixins that feed them in `versions/26.3/minecraft/`; the Fabric entry points
  and metadata in `versions/26.3/fabric/`; and the port's own copy of the
  renderer core in `versions/26.3/renderer-core/`.
- NVIDIA's loader-neutral `renderer-core/` and its Minecraft 1.21.1
  integration and Fabric, Forge, and NeoForge adapters under
  `versions/1.21.1/` are carried here byte for byte so that NVIDIA's changes
  merge cleanly. They are never edited in this repository and are not built
  here. A change to them belongs in
  [NVIDIA's repository](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft).

The checkout follows NVIDIA's layout, in which the root `project-state.json`
catalogs every Minecraft target, its support state, integration module, and
loaders, and a version port adds one `versions/<version>/` subtree that uses
the shared renderer core. The 26.3 port does not use the shared core yet: it
compiles its own copy, `versions/26.3/renderer-core/`, file for file the
counterpart of `renderer-core/`, as a stated transitional state. Moving it onto
the shared core is the next project.
[Multi-Loader Architecture](docs/MULTI_LOADER_ARCHITECTURE.md#this-fork-the-263-port)
describes the layout and the rules for changing it.

Wrapper and mod changes belong in the same pull request. The pinned runtime is
built from an NVIDIA branch of dxvk-remix whose source is not public yet, so
runtime changes are made by the maintainers: propose one on the
[RTX Remix tracker](https://github.com/NVIDIAGameWorks/rtx-remix/issues), or as
a pull request to dxvk-remix where it applies there too. Once a matching
runtime is published, the SDK manifest, the copied header, the JNI bridge, the
Java facade, and the mod callers are updated together here. The copied header at
`rtxremix-java/native/include/remix_c.h` matches the pinned runtime build and
must not be edited in isolation or ahead of the published runtime package.

## Set Up

[Getting Started](docs/GETTING_STARTED.md) covers the prerequisites, how to
obtain and stage a Remix runtime, the first build, and the one-time Prism
Launcher sign-in each checkout needs before it can launch. The runtime is the
part that surprises people: the bridge is compiled against a specific Remix C
API version, and a runtime that predates it loads and then fails.

## Where To Start Reading

Read [Multi-Loader Architecture](docs/MULTI_LOADER_ARCHITECTURE.md) first for
the ownership boundary between the canonical renderer, shared Minecraft
integration, and loader adapters.

In NVIDIA's 1.21.1 tree, `RendererFrameCoordinator` and `RendererScene` under
`renderer-core/` own captured-frame order and scene-resource lifecycle, and
`MinecraftClientRuntime`, `MinecraftRuntime`, and the narrow loader contracts
sit in `versions/1.21.1/minecraft/`. The 26.3 port predates that split, so two
classes are the way in here. `RtxRemixMod`, in `versions/26.3/fabric/`, is the
Fabric entrypoint: lifecycle hooks, key bindings, and the `/rtx` command tree.
`RemixRenderer`, in `versions/26.3/minecraft/`, is the render thread's
bridge into Remix, and every frame passes through it.

The three 26.3 directories compile as one source set, and a class sits in the
one NVIDIA moved its counterpart to, so its package name says nothing about
which directory holds it; search all three. New 26.3 tests go under
`versions/26.3/minecraft/test/java/` (or `versions/26.3/fabric/src/test/java/`
for Fabric-only wiring), never under `versions/26.3/renderer-core/test/`,
whose file list may only shrink.

From there, read by pipeline rather than by file. Packages are named for what
they own, so the package holding a problem is usually the package named after
it, and the class-level Javadoc in each one carries the design decisions that
are not obvious from the code. `AGENTS.md` in the repository root summarises
each pipeline in a paragraph and lists the invariants that hold them together.

## Rules That Bite

These are the mistakes that pass review by looking reasonable and then fail
intermittently, on someone else's machine, weeks later.

**Every Remix call belongs to the render thread.** Creating, updating, or
destroying a mesh, instance, texture, material, or light is a native call into
the runtime, and only the render thread may make one. Worker threads produce
CPU-side data and hand it over through bounded queues tagged with a generation
or epoch that the render thread validates before it publishes anything. A
worker that touches a handle directly works in testing and corrupts the scene
under load.

**Retained instances are mutate-then-submit.** Remix keeps a scene instance
until it is explicitly removed, so terrain and entity instances are submitted
where they are created or mutated, not re-submitted every frame. Any new
mutation of a retained instance must be followed immediately by a submit, or
the change never reaches the scene.

**A mixin that cancels vanilla work inherits its side effects.** Preserve the
render-pass bindings, buffer writes, and other state the caller and the rest
of its pass rely on, not only the entry calls. Never cancel
`LevelExtractor.extract` or `LevelRenderer.render` themselves: that extraction
and traversal host Remix's frame. See
[Cancelling Vanilla Render Work](#cancelling-vanilla-render-work).

**Only mixins live in the mixin package.** Mixin reserves that package, and a
helper class placed there crashes world load. `MixinPackageBoundaryTest` fails
the build if an unconfigured class appears in it.

**NVIDIA's trees are read-only here.** Never edit, add, or delete anything
under `renderer-core/` or `versions/1.21.1/`; they stay byte-identical to
NVIDIA's so that every merge from upstream applies cleanly. A fix to shared
renderer logic for 26.3 goes into the matching file under
`versions/26.3/renderer-core/`.

**The 26.3 copy of the core only shrinks.** Add no file under
`versions/26.3/renderer-core/`, and no new `net.fabricmc` import in
`versions/26.3/minecraft/`. `tools/tests/fork/test_transition_ledgers.py`
holds both lists and fails on a new entry; when a file leaves either list,
delete its line from the ledger beside the test.

**Runtime file changes touch three places at once.** The per-architecture native
compile tasks, `setupRuntime`, and `package` must agree on what a runtime
payload contains, and `NativeExtractor` must agree with what `package` produced.
A change to any one of them without the others produces a JAR that installs a
broken game directory.

**The JNI shim has no game dependencies.** `rtxremix-java/` must never import
`net.minecraft`, `net.fabricmc`, `net.minecraftforge`, or `net.neoforged`;
`RemixJavaBoundaryTest` enforces the boundary. Java-side handles are raw
`long` values, not wrapper objects or JNA `Pointer`s, and the C API is reached
only through JNI, never a JNA interface table. Use direct `ByteBuffer`s
wherever the native method requires them, and document whether buffers must be
direct on every new JNI entry point.

**An API change crosses the whole boundary.** Keep `StructType.java` constants
in step with the copied header and native runtime. Update the header, JNI
bridge, Java facade and data types, SDK manifest, and affected mod callers
together. After rebasing parallel API additions, check for conflicting struct
type values; the exact coordination points are in
[Runtime And Java API Changes](AGENTS.md#runtime-and-java-api-changes).

## Code Standards

These are principles, not an exhaustive list. Extrapolate to the situation in
front of you.

Match the surrounding code. The Java facade uses four-space indentation and
the naming conventions in `rtxremix-java/src/com/nvidia/rtxremix/`.

### Comments

Each file should read as if it was always designed this way. Comments guide the
next developer; they do not narrate the development journey.

Remove:

- **Implementation backstories**: "we do this because the other day X happened",
  "added after we found a bug where Y".
- **Obvious narration**: "create the attribute", "loop through keys". If the
  code says it, the comment is noise.
- **Debugging breadcrumbs** and **trial-and-error reasoning**: "we tried X but
  it caused Y so we do Z".
- **Foreign internals**: details of another system in code that is agnostic of
  it.

Keep:

- **Non-obvious design decisions**, stated as what and why this design, not what
  happened to us.
- **Tricky invariants**: conditions that are easy to break by accident.
- **API contracts**: docstrings on public methods with args, returns, raises.

Prefer renaming a variable or function over explaining it, type hints over
comments about types, and a short comment on the *why* over any comment on the
*what*.

### Engineering

Every change should make the codebase better, not just make the problem go
away. If a solution needs a paragraph to justify why it is not a hack, it is a
hack.

Remove:

- **Fixes in the wrong layer**. Put the fix where the problem originates.
- **Tolerance inflation**: widening deltas or adding retries to hide flaky
  behavior. If a value is wrong, find out why.
- **Catch-all exception swallowing** to hide symptoms.
- **Excessive error and null handling**. If an error is expected, handle it. If
  it is unexpected, raise it.
- **God methods**. Break them into named steps; design for the reader, and for
  fewer indentation levels.
- **Leaky abstractions**: implementation details escaping into layers that
  should be agnostic of them.

Design for single responsibility (if describing it needs an "and", split it),
ownership (the component that creates a problem owns its fix), and a minimal
public surface.

Commit to the new code:

- **No legacy fallbacks.** If you replace a system, remove the old one. No
  `try: new_way() except: old_way()` shims.
- **No dead code**: commented-out blocks, unused imports, orphan functions kept
  "just in case". Version control is the safety net.
- **No multiple paths to the same result.** If two exist, one is wrong.
- **No half-migrations.** Rename every reference; remove the API you replaced.

Smell tests:

- "It works if I add a sleep" means broken data flow.
- "It works if I read from the widget instead of storage" means the two are out
  of sync.
- "It passes alone but fails with other tests" means shared mutable state.
- "I added a flag to skip this code path" raises the question of why that path
  runs at all.

New files need the SPDX header used throughout the tree:

```java
/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
```

A file NVIDIA wrote keeps its NVIDIA copyright line when it is modified, and a
new file that descends from one keeps that line above the contributors' line.

PowerShell scripts must be ASCII or start with a UTF-8 BOM. Windows
PowerShell 5.1, used by `packaging/install.bat`, reads a BOM-less script using
the system ANSI code page. Typographic punctuation inside a string can then
cause parse errors pointing at unrelated lines. Use plain ASCII punctuation
for new script text and preserve the BOM on `packaging/installer.ps1`.

### Cancelling Vanilla Render Work

A `HEAD` cancel, `@Redirect`, `@WrapOperation`, or `@WrapWithCondition` that
skips a render method must leave the net state its caller expects. Disassemble
both ends of the target with `javap -p -c` before changing it.

Minecraft 26.3 draws through `RenderPass` objects. A draw sets its pipeline
and its uniforms and samplers on the pass with `setPipeline` and `setUniform`,
and the GL backend applies that state at each draw, so a skipped draw leaves no
stray GL state behind. Three things still need checking:

- **Bindings later draws read.** A binding set on a pass stays set for the rest
  of that pass. `ChunkSectionsToRender.renderGroup` sets `TerrainUniform`, the
  block atlas, and the lightmap on the main pass before drawing. The solid and
  translucent features that follow it bind their own textures from their render
  types, so skipping it is safe; a later draw that relied on those bindings
  would not be.
- **Work outside the pass.** `CloudRenderer.prepare` writes the cloud buffers
  and requests a larger shared quad index buffer, and
  `WeatherEffectRenderer.prepare` uploads vanilla's weather vertex buffer. Both run before the pass that draws
  them. Skip a draw, not the preparation another consumer needs: the weather
  capture reads the buffer `prepare` filled.
- **Passes a method opens itself.** `ChunkSectionsToRender.renderOit` and
  `CloudRenderer.renderOit` each open and close their own order-independent
  transparency pass with try-with-resources, so skipping either is
  self-contained.

What the mod cancels in 26.3, each only while Remix renders the world:

| Hook | Vanilla method | Why it needs no replay |
|---|---|---|
| `ChunkSectionsToRenderMixin` | `ChunkSectionsToRender.renderGroup` and `renderOit`, at the head | Pure terrain draws; the visibility walk, compiles, and uploads around them still run |
| `WeatherEffectRendererMixin` | The private `WeatherEffectRenderer.render(WeatherRenderState, RenderPass, RenderPipeline)`, which both `render` and `renderOit` call | It sets its own pipeline and bindings; `prepare` still runs and is captured at its return |
| `CloudRendererMixin` | `CloudRenderer.prepare`, `render`, and `renderOit`, decided once in `prepare` for the frame | `prepare` touches only the cloud renderer's own buffers, so when vanilla clouds return it rebuilds what changed |
| `LevelRendererParticleMixin` | `ParticlesRenderState.submit` in `LevelRenderer.submitFeatures` | Skipping a submit queues no nodes and opens no pass; ticking and extraction still run |

Entity, block-entity, first-person, block-damage, and block-outline capture
divert a submit into the mod's own collector rather than cancel a draw, and a
submit vanilla never receives costs it no state. The mod's own draws into
Minecraft's targets, the Remix composite and the weather overlay, go through
their own `RenderPass` and `RenderPipeline`. Raw GL is reserved for work outside
any open pass: `AtmosphericSkyGenerator` restores the current program, the
texture units, and the buffer bindings the GL backend caches.

A pure read or early-return guard needs no replay. In Minecraft 26.3,
`WeatherEffectRenderer.render` returns when there are no columns or no buffer,
and `LevelRenderer` skips cloud preparation and drawing when the cloud option is
Off or the cloud colour is fully transparent. Check the pinned Minecraft version
rather than treating these examples as permanent contracts.

Cancel only draws that Remix replaces, such as terrain, weather, particles and
clouds. Do not bypass vanilla's extraction or the frame graph it feeds: camera,
frustum, matrices, render targets, tick delta, the render states the capture
reads, section scheduling, and Fabric's `LevelRenderEvents` all come from them.
Judge a cancellation by its injection and state correctness, not only a
frame-time delta. A GPU-bound run can hide a real render-thread saving.

### Reading Vanilla Bytecode

Minecraft 26.1 and later ship unobfuscated, so the names in the game's own
jar are the names the mod targets: no mappings, no refmap. The merged jar Loom
caches is enough for `javap`; `gradlew genSources` additionally writes a
decompiled sources jar next to the processed jar under
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-<hash>/26.3/`.
Those sources are decompiled after the mod's and Fabric's access wideners are
applied, so a member they show as `public` may be private in the game. Check
access flags, and whether a member or call exists at all, with `javap -p` on
the unmodified merged jar before adding or deleting an access-widener line.
Earlier versions and the Yarn- or Mojang-mapped jars of the 1.21.1 era remain
in the cache after a version bump; check the version in the path.

A Git Bash example:

```bash
javap -p -c -classpath ~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar com.mojang.blaze3d.vertex.VertexConsumer
```

If you need to search all callers of a descriptor, extract the whole JAR into
a temporary directory outside the repository. A shell `unzip` wildcard may
stop at a directory separator and silently omit subpackages. Search the
extracted classes for the exact descriptor, disassemble candidates, and remove
the extraction afterwards. Do not leave thousands of class files in the tree.

### Verifying A Mixin Injection

`Mixing X from ... into net.minecraft...` (or `com.mojang...` for the
`renderpearl` GPU classes such as `FrontendGpuSurface` and `GlBackend`) in
`run/logs/debug.log` proves registration, not an applied injection or an
executed target. The check reads Loom's development client, which
`:versions:26.3:fabric:runClient`, Fabric's standard Gradle task, starts from
`run/` at the repository root. Temporarily add `-Dmixin.debug.export=true` to
the client `vmArgs` in `versions/26.3/fabric/build.gradle`, launch
`runClient`, and disassemble the transformed class under
`run/.mixin.out/class/<package path>/<Class>.class` with `javap -p -c`, for
example `run/.mixin.out/class/net/minecraft/client/renderer/chunk/ChunkSectionsToRender.class`.
A mixin on a nested target, such as `RebuildTaskMixin` on
`SectionRenderDispatcher$RenderSection$CompileTask`, exports under the nested
class's binary name. Classes are exported at load, so a later shutdown crash
does not invalidate the export.

An `@Inject` merges a `handler$<id>$rtx-remix-renderer$<handler name>` method
and a call at the intended offset: `invokespecial` for a private instance
handler, `invokestatic` for a static one. MixinExtras injectors
(`@WrapOperation`, `@WrapWithCondition`, `@WrapMethod`,
`@ModifyExpressionValue`) merge handlers under their own prefixes, such as
`wrapOperation$` and `wrapWithCondition$`, and a `@ModifyArg` under
`modify$`; search the export for the handler name. If the call is present but
does not fire, investigate vanilla's frustum, dimension, cloud-mode, weather and
render-state gates, and whether the frame reached `LevelExtractor.extract` at
all. Minecraft 26.3 ships unobfuscated, so the exports, the classes loaded from
`versions/26.3/fabric/build/classes/`, and the JAR in
`versions/26.3/fabric/build/libs/` all use the game's own names; nothing is
remapped. Remove the diagnostic flag and export afterwards.

## Tests

```powershell
.\gradlew.bat test
```

With no `-PmcVersion`, the root task runs the 26.3 Fabric suite,
`:versions:26.3:fabric:test`, which covers JVM-side behavior and data
contracts. It runs with the repository root as its working directory, so a
test reads `packaging/`, `docs/`, or `rtxremix-java/` by those paths. Before
launch, verify the distributable artifact too:

```powershell
.\gradlew.bat package -Parch=win64
```

The suite does not cross the JNI boundary and it does not render, so it cannot
tell you that a change looks right. Two things follow. First, `test` still
needs the C++ toolchain, because every task that reaches `classes` builds the
bridge and fetches and stages a missing runtime first. Second, a rendering
change is not reviewed until someone has looked at it in a running game: build
and launch it with `tools\rtx.bat launch`, and say in the pull request what you
looked at and what you saw. Screenshots of the before and after, taken from the
same position, are the most useful thing you can attach.

NVIDIA's offline mixin and packaging gates for its 1.21.1 targets, such as
`ForgeMixinContractTest`, are carried with those sources and do not run here.

For wrapper changes, the same root Gradle workflow compiles the shim with its
mod callers and builds the bridge into `build/native/{win64|arm64}/`.
Visual Studio C++ tools and `JAVA_HOME` pointing to a 64-bit JDK 25 are
required. Gradle fetches the pinned runtime automatically when it is missing.
Use `.\tools\rtx.bat sdk local --path <runtime dir> --arch win64` for a local build,
as described in [Getting Started](docs/GETTING_STARTED.md#get-a-runtime).
Packman-managed runtimes in `remix-runtime/{win64|arm64}/` are junctions into
the shared cache. Never copy bridge outputs into them or commit SDKs or native
build outputs. Launch with `tools\rtx.bat launch` before requesting review on an
API change: Java compilation alone does not validate native loading.

The Python tooling has a separate check command:

```powershell
.\tools\rtx.bat check --tests
```

It uses the shared uv project and tracked `tools/uv.lock` to run Ruff format
checks and linting, ty type checks, and the pytest suite, including the
public-boundary gate. The bootstrap installs uv when needed and manages Python
>=3.15; uv >=0.13 is required. This check needs neither a GPU nor a staged
world, art pack, or runtime. See [Developer tooling](tools/README.md#configuration)
for configuration and assets.

Before publishing, include the commit-message boundary check:

```powershell
$env:PUBLIC_COMMIT_RANGE = "main..HEAD"
.\tools\rtx.bat check --tests
Remove-Item Env:PUBLIC_COMMIT_RANGE
```

Use the actual target branch instead of `main` if the change targets another
branch. Keep machine-local choices in ignored `tools/config.local.json`; the
shipped defaults pin the demo world and art pack and select no graphics preset.

To build an installable release, run `.\gradlew.bat releaseArchive`. It
verifies the 26.3 Fabric package, stages its self-contained JAR under
`build/release/mods/fabric/` with the RTX Remix Materials and RTX Remix
Showcase World zips, the installer, its documentation, `project-state.json`,
and the license notices, and writes a ZIP with the same layout,
`build/distributions/rtx-remix-renderer-mc26.3-<buildVersion>-installer.zip`.
Use `-Parch=win64` or `-Parch=arm64` for one architecture; omitting it
includes both. The baseline resource pack is built into the JAR.

The pack and world come from the `resourcePack` and `world` pins in
`tools/config.json` that development launches use, so moving a pin changes the
next release too. `.\gradlew.bat stageReleaseAssets` collects the four files a
release carries in `build/release-assets/`:
`rtx-remix-renderer-fabric-mc26.3-<buildVersion>.jar`, the Materials zip, the
Showcase World zip, and the installer bundle. Only these release tasks download
the pack and world; `build`, `test`, and `package` never do.

### Visual Comparisons

Capture both revisions from one dedicated, clean worktree so its
checkout-specific Prism instance keeps the save, pose, and `options.txt`
settings unchanged. Stop the client, check out the comparison revision detached, launch
it with `tools\rtx.bat launch` and capture it, then return to the feature
revision and repeat. The integrated Java facade and JNI sources switch with the
checkout. If its runtime manifest changed, restore that revision's pin with
`tools\rtx.bat sdk packman` before launching, or explicitly select the matching
local runtime. Keep the shipped `packaging/rtx.conf` matched to the runtime too.
`tools\rtx.bat launch` rebuilds and installs each revision, and refuses to start
while a client from the checkout is still running.

F1 hides the first-person hand as well as the HUD. Vanilla's
`GameRenderer.renderItemInHand` skips
`FirstPersonHandsAndItemsRenderer.submitHandsWithItems` when the HUD is hidden,
and Remix captures the hand from that call. Do not use F1 for a hand or held-item comparison. To avoid chat text,
wait eleven seconds after the last command, beyond its ten-second fade.

[Remix debug views](docs/GETTING_STARTED.md#viewing-a-remix-debug-view) separate
geometry and material inputs from the final shaded image. Their indices belong
to the matching dxvk-remix source, not a stable mod enum. Read
[Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) before attributing a
clean geometry view with a final-image artifact to the capture path.

## Porting A Minecraft Version

Every supported target lives in the same checkout under
`versions/<minecraft-version>/`. The root `project-state.json` marks each
version `primary`, `maintenance`, or `archived` and declares exactly the
loaders CI must validate.

Keep a port low ceremony:

1. add the version to `project-state.json`;
2. add its `versions/<version>/minecraft/` integration;
3. add only the declared loader-provider hooks affected by API drift;
4. run target-specific build, package, and semantic fixtures; and
5. record live checks in the target-status report and pull request.

Use the same deterministic world, camera path, and shot set when comparing
loaders or versions. These fixtures test semantic parity, not byte-identical
sources. `tools\rtx.bat versions` reports the catalog in the checked-out
manifest; `tools\rtx.bat target-status` reports automated-contract, build,
artifact, and automated-live-run evidence and does not mutate or generate
source. A completed benchmark proves the declared fixture ran to completion;
visual or semantic parity remains an explicit reviewer attestation.

Do not introduce Stonecutter or source preprocessors merely to avoid a small
port. The canonical renderer and provider boundaries already share the stable
code. Gradle selects a version and loader at configuration time, so built JARs
contain no source-level version switches. Reconsider preprocessing only when
repeated ports demonstrate substantial duplication those boundaries cannot
absorb.

This checkout departs from that process in one stated way: the 26.3 port
keeps its own copy of the renderer core, `versions/26.3/renderer-core/`, named
by the catalog field `rendererCore`, until it moves onto the shared
`renderer-core/`. Until then, a fix to shared renderer logic for 26.3 goes into
that copy, and nothing new is added to it.

## Syncing With Upstream

NVIDIA's repository is the upstream. A sync is a merge, never a rebase, so the
fork's history stays intact:

```powershell
git remote add upstream https://github.com/NVIDIAGameWorks/rtx-remix-minecraft.git  # once
git fetch upstream
git switch -c sync/upstream-<date>
git merge --no-ff upstream/main
```

`renderer-core/` and `versions/1.21.1/` merge without conflicts, because
nothing here edits them. Conflicts land in the shared files both sides change:
the guides, `build.gradle`, the installer, the tools, and the workflows. Take
upstream's structure and re-apply what the port needs, through the catalog
where upstream made a thing catalog data.

A merge brings NVIDIA's changes to its own trees but not to the 26.3 port. Each
change under `renderer-core/` or `versions/1.21.1/` is a porting to-do for the
matching file under `versions/26.3/`. List them with:

```powershell
git diff --stat HEAD MERGE_HEAD -- renderer-core versions/1.21.1
```

while the merge is in progress, or `git diff --stat HEAD^1 HEAD -- renderer-core
versions/1.21.1` once the merge commit exists. A file in `renderer-core/`
has its counterpart at the same relative path under
`versions/26.3/renderer-core/`; a file in `versions/1.21.1/minecraft/` has its
counterpart under `versions/26.3/minecraft/` when the port has one. Read the
upstream commit messages for intent, port each change to the 26.3 API, and add
its tests under `versions/26.3/minecraft/test/java/`. Changes to
`rtxremix-java/`, `packaging/`, and the tools are shared and merge directly.
Finally run `.\gradlew.bat build`, `.\tools\rtx.bat check --tests`, and
`tools\rtx.bat launch`, and confirm `git diff upstream/main -- renderer-core
versions/1.21.1` is still empty.

## Pull Requests

The GitHub repository is the source of truth. Fork it, make your change on a
branch, and open a pull request against `main`. That is where review and
merging happen, for NVIDIA developers as much as for anyone else. A maintainer
reviews on GitHub and, when a change warrants it, runs it on an RTX machine
before approving.

Keep a pull request to one logical change. Unrelated reformatting, renames, and
drive-by cleanups make review harder and are usually better as their own
request.

Write commit subjects in the imperative, describing what the commit changes
rather than the order it was written in: "Cap concurrent section builds", not
"fixed the thing from yesterday". Squash review fixups into the commits they
belong to before the final review pass, and make sure each commit builds on its
own. Commit messages and the pull request title and body are checked along
with the code, since the squashed commit is composed from them.

Every commit needs a `Signed-off-by` trailer. See
[Developer Certificate of Origin](#developer-certificate-of-origin) below.

Pull requests and pushes run manifest-derived workflows on GitHub-hosted Windows
runners. [`check`](.github/workflows/check.yml) installs uv and runs
`tools\rtx.bat check --tests`: Ruff formatting and lint, ty, the Python tests,
and the public-boundary gate. On a pull request it also scans the commit
messages from the base SHA to the head SHA.
[`build`](.github/workflows/build.yml) validates `project-state.json`, then
builds, tests, packages, and verifies every Minecraft-version/loader target of
a version the catalog lists in `builtVersions`, here 26.3 on Fabric, on the JDK
the catalog declares for it (25). NVIDIA's 1.21.1 targets, which the catalog
declares but this checkout does not build, never enter the matrix.
Both workflows use read-only repository permissions and no secrets, including
for fork pull requests. The builds fetch the pinned runtime from public
Packman, so they need no credentials; never add private ones to a workflow.

Neither workflow launches Minecraft, measures GPU performance, or produces a
release archive. Run `releaseArchive` locally when validating packaging; the
[`release`](.github/workflows/release.yml) workflow, described under
[Releases](#releases), runs only when a maintainer starts it.
Benchmarks are a developer tool, not a public pull request or post-merge gate:
measure a performance change locally and put the numbers, workload, and visual
checks in the description. Do not depend on access to additional CI services
or GPU runners to contribute.

A pull request needs the required maintainer approvals before it merges, and
any push to the branch, a rebase included, dismisses the approvals it has, so
push fixups before asking for review and rebase only to resolve a conflict. It
is merged as one squashed commit, so write the pull request description as the
commit message you want to end up with.

## Releases

A maintainer releases by raising `mod.version` in `project-state.json`, which
the build, the installer, and the release workflow all read, and closing the
`## Unreleased` section of [CHANGELOG.md](CHANGELOG.md) under that version and
the date in the same commit, merging that, and running the
[`release`](.github/workflows/release.yml) workflow from the Actions tab. It
builds the selected Minecraft target's manifest-derived release assets with
`stageReleaseAssets` on a GitHub-hosted runner. The default target uses
`v<mod-version>`; another target uses
`mc/<minecraft-version>/v<mod-version>` so versions can ship independently.
Tags are immutable, and existing published tag names are never rewritten.

A draft has no tag yet. A person writes the release notes from that changelog
section and pastes them into the draft, installs the draft's bundle on an RTX
machine to smoke-test it, then publishes it; GitHub creates the tag at that
moment, so do not push the tag beforehand.

## Developer Certificate of Origin

This project uses the Developer Certificate of Origin (DCO). There is no
Contributor License Agreement to sign. Every commit must carry a
`Signed-off-by` trailer certifying that you have the right to submit the work
under this project's license.

Git adds the trailer for you with `-s`:

```bash
git commit -s -m "Short description of the change"
```

Git uses your configured committer name and email for the trailer. Check your
identity before committing:

```bash
git config user.name
git config user.email
```

Three rules apply:

1. Use your real name. Anonymous contributions and pseudonyms cannot be
   accepted.
2. The sign-off address must match the commit author address.
3. Every commit in the pull request needs the trailer, not only the last one.

To add the trailer to the most recent commit:

```bash
git commit --amend -s --no-edit
```

To add it to every commit on a branch cut from `main`:

```bash
git rebase --signoff main
```

Both commands rewrite history, so force-push the branch afterwards.

### Developer Certificate of Origin 1.1

The full text of the certificate you agree to by signing off is reproduced
below and is also available at <https://developercertificate.org/>.

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## Reporting Bugs

A bug report is not a contribution you have to build: open an issue on the
project's [issue tracker](https://github.com/Mango67sigma/rtx-remix-minecraft-26.3/issues),
and include everything listed in
[Reporting a Bug](docs/PLAYING.md#reporting-a-bug).
For wrapper failures, include the JDK version and failing Java stack trace or
native crash log as well.
Include the player's F3 coordinates. Held-item precision artifacts can appear
only thousands of blocks from the world origin.

[Reporting a Bug](docs/PLAYING.md#reporting-a-bug) also says which problems
belong to the runtime and go to the
[RTX Remix tracker](https://github.com/NVIDIAGameWorks/rtx-remix/issues)
instead; dxvk-remix itself takes no issues.

## Licensing Of Contributions

Contributions are accepted under the Apache License, Version 2.0, the same
license that covers this repository. See [LICENSE](LICENSE) for the full text,
[NOTICE](NOTICE) for attribution, and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for components carrying other
licenses. If your change vendors third-party code, add it to
`THIRD_PARTY_NOTICES.md` in the same pull request. Vendored code must have a
license compatible with this project's Apache-2.0 distribution. Do not copy,
translate, or lightly rewrite GPL-, LGPL-, or AGPL-licensed rendering-mod code
into this tree. A reference can inform an approach, but the submitted
implementation must be independently written against the Remix API and
Minecraft's interfaces, with rights you can certify through the DCO sign-off.
