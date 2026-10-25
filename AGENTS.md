# AGENTS.md: rtx-remix-minecraft

Context for AI coding agents working in this repository.

## Project Overview

`rtx-remix-minecraft` is a Fabric mod that renders Minecraft 26.3 through
NVIDIA RTX Remix. It captures terrain, entities, particles, sky, and clouds from
the client and submits them to the Remix runtime as a path-traced scene. The
Java facade and JNI bridge are tracked under `rtxremix-java/`; the native
runtime and the Remix C API come from dxvk-remix. A change often spans all
three layers.

## Source Of Truth

Prefer these sources in this order:

1. source under `src/main/java/`
2. `build.gradle`
3. the Architecture section below
4. `README.md` and the guides under `docs/`

There is deliberately no standalone architecture document. One existed and went
stale faster than the code it described. Code standards and review expectations
are in [CONTRIBUTING.md](CONTRIBUTING.md) and apply to agent-written code too. A
subdirectory may carry its own `AGENTS.md`; read the nearest one as well.

## Build, Run, Package, Test

Java 25, Windows, and the C++ toolchain are required. Gradle fetches a missing
Remix runtime automatically. [Getting Started](docs/GETTING_STARTED.md) covers local overrides.

```powershell
.\gradlew.bat build
.\tools\rtx.bat launch
.\gradlew.bat test
.\gradlew.bat package -Parch=win64
```

`tools\rtx.bat launch` builds the mod, installs it into a checkout-specific
Prism Launcher instance, and starts Minecraft through Prism. Installed Prism
reuses its normal account profile; portable Prism keeps its profile under
`run-prism/`. Use `tools\rtx.bat prism signin` with a Microsoft account that
owns Minecraft: Java Edition. See
[Getting Started](docs/GETTING_STARTED.md#sign-in-to-prism).

`classes` depends on `setupRuntime`, which compiles the JNI bridge, fetches a
missing runtime, and stages it, so even `test` needs the C++ toolchain.
`build` leaves a lightweight JAR in `build/libs/`; `package` embeds
the runtime and payload in `build/distributions/`. It verifies inclusion of
every `packaging/licenses/` file and excludes Python interpreter and binding
payloads. Staged and packaged contents must agree with `NativeExtractor`.
See [Getting Started](docs/GETTING_STARTED.md) and
[`build.gradle`](build.gradle) for the staging inventory.

Do not run Gradle while a client from the same checkout is up: staging can fail
on locked DLLs or replace its live configuration. Stop the client or use another
worktree. Build guards should prevent a silently wrong artifact, as the
mixed-runtime-source guard does, rather than police checkout cleanliness.

Keep the unconditional tuning arguments in `loom.runs.client.vmArgs` in
`build.gradle` and `RECOMMENDED_JVM_ARGS` in `packaging/installer.ps1`
identical, and when `RECOMMENDED_JVM_ARGS` changes, append its outgoing value to
`PREVIOUS_RECOMMENDED_JVM_ARGS`. Opt-in benchmark and profiling arguments are
excluded. See
[JVM Arguments](docs/TUNING.md#jvm-arguments).

## Runtime And Install Model

The mod needs the native runtime, not only Java code, and `NativeExtractor`
unpacks the bundled payload on first launch, version or architecture change,
or when `remix-sdk/` or its `d3d9.dll` is missing. Deleting
`.rtx-remix-version` also forces full re-extraction. Full extraction replaces
the mod-owned `remix-sdk/`, `rtx.conf`, and the shipped runtime textures in
`textures/`, leaving anything else in that folder alone, so a JAR installed
without the release installer is complete. With a matching marker and runtime
present, launch restores a missing `rtx.conf`, a missing runtime texture, and
any missing shipped files under `rtx-remix/` without replacing existing files. The `rtx-remix/` payload
is author-owned: existing files, including edited confs, are never overwritten,
even during full extraction. Deleting a shipped conf restores its default on
the next launch. A present conf that fails to acquire still prevents renderer
initialization; the error identifies the file.

Baseline LabPBR textures live in `src/main/resources/assets/` and load through
Fabric's `fabric` resource pack below user packs. Development setup, `rtx install`,
and the release installer use `packaging/migrate_resource_packs.ps1` to remove
their exactly identified legacy baseline copies and migrate existing pack order.

## Architecture

**Orchestration.** `RtxRemixMod` is the Fabric entrypoint: lifecycle hooks, key
bindings, and the `/rtx` command tree. `RemixRenderer` is the render thread's
bridge into Remix and owns the frame. Thread-safe stores wrap Remix-side meshes,
instances, textures, materials, and lights over raw `long` handles from the JNI
wrapper. `JoinBurstHold` keeps vanilla's level-loading screen up for a bounded
wait for stable frames after a world join (`LevelLoadingScreenMixin`), hiding
first-sight mesh, pipeline, and BLAS costs without holding the screen
indefinitely.

**Frame.** Minecraft 26.3 extracts render state before it renders, and the
mod's frame follows that split. `LevelExtractorFrameMixin` begins the frame at
the head of `LevelExtractor.extract`, before anything a pending texture or
material reload invalidates is extracted. `LevelRendererFrameMixin` brackets
`LevelRenderer.render`: its head opens the entity frame and its return marks the
world ready to submit. `GameRendererMixin` submits the Remix frame in
`GameRenderer.render3dHud`, after the first-person hand and before the screen
effects and the GUI. `TextureOverlay` composites the traced image onto the main
render target through a `RenderPass`, and `RemixWeatherRenderer` draws weather
over it the same way. Present goes through Remix by wrapping the backend call in
`FrontendGpuSurface.present` (`FrontendGpuSurfaceMixin`). Vanilla skips the
present while the window is iconified, so `MinecraftClientMixin` presents then
too and every Remix submit stays paired with a present. The GL interop needs
the OpenGL backend. `MinecraftClientMixin` starts the game on OpenGL when the
launcher passes no `--graphicsBackend`, and `RemixRenderer.initialize` refuses
any other backend with a logged message, so the game keeps rendering through
vanilla (`GraphicsBackendPolicy`, `-Drtxremix.forceOpenGl=false` turns the
force off).

**Overlay input.** `RemixInputGate` owns the Minecraft-side capture session for
the Remix overlays; `KeyboardMixin` and `MouseMixin` route the SDL input events
vanilla queues (keys, text input and IME composition, buttons, motion, scroll)
through it. Left Alt+X is matched by scancode with the event's modifier mask, so it
is the same physical key on every layout. A cancelled event still reports input
to vanilla's `FramerateLimitTracker`, so an open menu never trips the AFK frame
cap. Capture follows whether a native menu is shown, which `rtx.showUI` records:
`GameRendererMixin` has the gate read it through `GetConfigVariableResolved` at
the head of `GameRenderer.update`, the first game-renderer work of each frame.
The mod opens and closes menus by writing it, and the menus' own close and
switch controls, the runtime's menu hotkey, and its First Use Guide change it
too. Left Alt+X without Control toggles the developer menu over
gameplay or a screen. The only other open command in the mod is the RTX Remix
Graphics Settings button on the settings landing page; both open through
`RtxRemixMod.openRemixMenu`, and the button queues its request so the click
finishes dispatching first. Opening, by the mod or the runtime, finalizes held
key bindings, vanilla's F3 combination state, and active mouse interactions
because their releases will not reach Minecraft. Capture excludes Minecraft
input and cursor-lock requests, and screens see an off-screen cursor through
`MouseHandler.getScaledXPos` and `getScaledYPos` to clear hover state. Remix
resolves a write a frame or more later, so after the mod's own write the gate
ignores reads until one agrees with it. After
`OWN_WRITE_OVERDUE_FRAMES` frames without one, it logs that a config layer above
Derived Settings likely sets `rtx.showUI` and follows reads again. The native
controls take effect only because `SetConfigVariable` writes `NoSave` options
such as `rtx.showUI` to the Derived layer, where the menus write them; a User
Settings value would mask every native close. The packaged `rtx.conf` parks the
runtime binding on a chord Windows cannot deliver.

**Camera paths.** A time-aware shape-preserving cubic Hermite path over
nonuniform waypoint times with shortest-route unwrapped yaw, a strict version 1
JSON codec, and world-scoped storage. Playback applies each pose just before
`Minecraft.renderFrame`, so `GameRenderer.update` builds that frame's camera
from it. A waypoint's time of day is the overworld clock
(`Level.getOverworldClockTime`); playback writes it to the client clock
manager at once and, in single player, to the integrated server's clock on the
server thread. The default clock follows wall time; the fixed-60 clock
advances one deterministic sample per rendered frame, which benchmarks reuse
with their own frame index. `docs/CAMERA_PATHS.md` is the canonical guide.

**Gameplay-driven conf layers.** Option layers come from a mod root shipping
archetypes, acquired at init, and a world root inside a save supplying zone and
biome profiles, acquired on join. A world layer sits just above its mod
counterpart, so a map overrides per option rather than replacing an archetype,
and it needs the integrated server, so it is single-player only. Profiles
resolve above `rtx.conf` and below user settings and quality presets. The
time-of-day layer reads the overworld clock.

**Sky and weather.** Sky textures are generated and published on the render
thread, branched on the dimension's `DimensionType.skybox()`: the End keeps a
constant dark dome and a skyless dimension a black one with no sky ambient. The
sun, star, and moon-phase values come from the environment attributes at the
camera (`CelestialClock`), and the star field is copied from vanilla's own
`SkyRenderer.buildStars` mesh (`SkyRendererMixin`, `VanillaStarField`), so it
can arrive a frame after the sun and moon. `AtmosphericSkyGenerator` dispatches
raw GL compute outside any open `RenderPass` and restores the program, texture
units, and buffer bindings the GL backend caches. Weather is taken from
`WeatherEffectRenderer.prepare` as vanilla's prepared vertex buffer and its rain
and snow textures; vanilla's private draw, which both transparency modes use, is
cancelled, and `RemixWeatherRenderer` draws that buffer over the composited
image against a depth seeded from Remix (`WeatherEffectRendererMixin`).

**Procedural clouds.** Cloud identity and motion are stateless functions of cell
coordinates and synchronized world time, so clients reconstruct the same clouds
with no network state. One worker builds the mesh palette lazily, the render
thread uploads one mesh per frame, and each visible cloud submits one palette
mesh with one transform. Choosing the Classic cloud style switches to vanilla
texture partitions that keep vanilla's cloud period and phase; like 26.3
vanilla, they drift by game time, so every client sees them in the same place.
Vanilla's own clouds are withheld for a whole frame or not at all: the decision
is latched in `CloudRenderer.prepare` and applied to its `render` and `renderOit`
(`CloudRendererMixin`).

**Terrain.** Minecraft workers compile section meshes, and the mod captures
inside that compile (`SectionBuilderMixin` on `SectionCompiler.compile`). Every
block model, vanilla or modded, emits through Fabric's
`FabricBlockStateModel.emitQuads` into the capture emitter
(`BlockQuadEmitter`), fluids are redirected from `FluidRenderer.tesselate`, and
vanilla's own tesselation is skipped by reporting `RenderShape.INVISIBLE` while
capturing, so a capturing compile hands vanilla no layers. Each emitted quad
carries its own chunk layer, as vanilla now renders, so a block's quads can
land in different layers. A low-concurrency executor merges the captures into
chunk-column assets split into byte-capped pages per category, and the render
thread publishes them under a frame budget and retires replaced pages through a
bounded queue. Concurrent vanilla section builds are capped.
With capture on, `ChunkSectionsToRenderMixin` cancels vanilla's terrain draws
(`ChunkSectionsToRender.renderGroup` and `renderOit`) while the visibility walk,
section compiles, and uploads around them keep running. Vanilla's terrain
dirty state lives in `LevelExtractor` and its `SectionUpdateTracker` in 26.3,
so the scheduling hooks sit there (`LevelExtractorTerrainMixin`) and on
`LevelRenderer` (`LevelRendererTerrainMixin`). While capture is on, integrated-server chunk delivery and
chunk unloads are paced by measured render-thread cost under separate budgets,
with chunk caps on delivery and a ring-edge bypass for unloads
(`LocalChunkBatchPacing`, `PacedUnloadQueue`, `ChunkDataSenderMixin`); the
client light engine's `LayerLightSectionStorage.swapSectionMap` publishes the
live section map as the visible snapshot instead of a clone
(`ClientLightSnapshotPolicy`, `LightStorageMixin`) while the integrated server's
engines, which have real readers on other threads, publish an immutable layered
view instead of vanilla's whole-map clone (`ServerLightSnapshot`); and the
client's sky-light surface walk is seeded at the packet's `WORLD_SURFACE`
height (`SeededSurfaceScan`, `SkyLightSurfaceScanPolicy`). `VanillaSectionBuilds`
keeps real copies until every build that latched capture off has finished.
Each policy has a `rtxremix.*` kill switch listed in `docs/TUNING.md`. 26.3
never compiles an uncompiled section until its eight neighbour columns are
loaded and lit (`SectionUpdateTracker.hasAllNeighbors`), and a column's light
comes on in the same render-thread task that re-flags its neighbourhood with
`setSectionRangeDirty`, so the 1.21.1 chunk-load rebuild storm is gone and chunk
loads stay vanilla. `NeighbourReflagFilter` instead drops the light-data
re-flags: while RTX is enabled, `LevelExtractorTerrainMixin` cancels
`LevelExtractor.setSectionDirtyWithNeighbors` at its head, which a light update
packet calls for the 3x3x3 neighbourhood of every section it carries light for.
Remix terrain reads no client light, so those rebuilds would recapture identical
geometry. The switch is `rtxremix.chunk.neighbourReflagFilter`; the rebuilds it
drops are counted as `Chunk.NeighbourReflagsDropped` and, with the switch off,
the ones vanilla then runs as `Chunk.NeighbourReflagsKept`. Block updates and
biome updates stay vanilla. The repeats a chunk load can still cause while the
light queue is behind are only measured: `Chunk.BuildsAheadOfChunkLight` counts
a build whose 3x3 column neighbourhood holds a column that is loaded but not yet
lit. 26.3 also keeps sections that turned to all air out of the visible graph,
so `ClientChunkManagerMixin` reports such sections for removal from the Remix
scene.

**Visibility.** The occlusion walk is the mod's allocation-free port of
vanilla's `SectionOcclusionGraph.runUpdates`. It runs by default with RTX on, on
a low-priority executor of its own (`ChunkRenderingDataPreparerMixin`,
`terrain/visibility/`); `-Drtxremix.vanillaVisibilityWalk=true` switches to
vanilla's walk instead. Only the frustum cull is left to vanilla: since 1.21.2 the graph keeps reached sections
in an `Octree` that culls whole octants. The mod's coarse frustum index
(`BuiltChunkFrustumIndex`) and its replay of the render-thread slot move log
(`ChunkSlotMoveLog`, still recorded) are therefore not installed. Bringing the
index back would mean reproducing the octree's emit order and its near-section
classification, because in 26.3 that order drives compile scheduling.
`BuiltChunkStorageMixin` re-derives `ViewArea.repositionCamera` so a re-centre
moves only the slot planes whose target changed.

**Allocation.** The collector's concurrent work is what stretches frames at
high render distance, so the hot vanilla paths that run per section or per
frame are ported allocation-free and kept that way: the chunk visibility walk
and its far-section probe (`ChunkRenderingDataPreparerMixin`,
`terrain/visibility/`), whose graph nodes come from a per-state pool, and biome
colour blending. The walk still allocates what vanilla's own containers
allocate: the `Octree`, the propagation queue, and the per-chunk waiting lists.
Block model variant and part selection and the integrated server's per-tick
chunk iteration no longer allocate per call or per chunk in vanilla 26.3, so
the mixins that ported them are gone. Retained section meshes live in
C-allocated direct buffers outside the JDK's direct-memory budget
(`SectionMeshData`, `DirectBufferPool`), under explicit reference counts: the creator's reference travels worker, `BlockDataQueue`,
`TerrainState` entry; a column build retains each snapshot before submission
and releases in its executor task's `finally`; the last release returns the
buffers and makes the surfaces unreadable while the cached hashes stay readable
for diffing. Render-region snapshots are vanilla's per-section `SectionCopy`
objects; their block-state storage comes from pooled heap arrays
(`RenderRegionStoragePool`, `RenderedChunkMixin`). A copy is referenced by its
creator, by each `RenderSectionRegion` built from it, and by the frame's
`RenderRegionCache` until `LevelExtractor.extract` returns; a compile task
releases its region when it finishes, and the last release returns the arrays
(`ChunkRendererRegionMixin`, `RebuildTaskMixin`, `LevelExtractorTerrainMixin`).

**Textures and atlases.** Atlas capture produces per-sprite materials plus
atlas-level PBR, emissive, and luminance textures. The block atlas holds only
block sprites in 26.3; items have their own atlas. Vanilla animates atlases on
the GPU, so no animated frame ever exists on the CPU: after each
`SpriteContents$AnimationState.tick` the mod rebuilds the frame patch, or for
an interpolated sprite the blend of two frames, from the animation state
(`SpriteAnimationStateMixin`, `TextureAtlasSpriteMixin`) and forwards the
batched patches (`AtlasAnimationForwarder`). That needs the patch types in the
wrapper and runtime support for updating a texture from memory. Glyph pages are
mirrored from the texture writes and buffer-to-texture copies of the frontend
command encoder (`FrontendCommandEncoderMixin`, `GlyphAtlasTextureMixin`),
because vanilla assembles them on the GPU and never keeps them on the CPU.
Downloaded skins and other dynamic textures are snapshotted at
`TextureManager.register` and `DynamicTexture.upload`.

**Particles.** Vanilla still ticks and extracts particles; the mod withholds
only the submit of the extracted state (`LevelRendererParticleMixin` on
`ParticlesRenderState.submit`) and reads the live particles from vanilla's
particle groups. Slot preparation and CPU mesh snapshots run on a dedicated
worker and are double buffered: the render thread publishes the previous
frame's payload at the top of the frame and joins the worker after submit, so
particles trail the scene by exactly one frame. Each particle's bucket follows
its `SingleQuadParticle.Layer` atlas: the particle atlas and the block atlas
have Remix materials, while particles on the item atlas or a mod atlas have no
route yet and are not drawn. Vanilla particle colours are lightmap tints, not
albedos, and the runtime lights a blended particle from the radiance cache with
no self-shadowing, so `ParticleTint` draws soot near-black to keep smoke dark
over a lit floor.

**Entities.** Since 26.2 vanilla renderers do not write vertices; they push
submit nodes into a `SubmitNodeCollector` that feature renderers later batch
by render type. The mod captures at the submit stage, where the owner and the
live pose stack are still known. `LevelRendererEntityMixin` and
`BlockEntityRenderDispatcherMixin` hand an extracted render state, whose entity
or block entity `EntityRenderStateMixin` and `BlockEntityRenderStateMixin`
attach at extraction, to `RemixSubmitNodeCollector`. That collector executes
each submit the way its feature renderer would, into the capture, so a model is
evaluated once and vanilla's collector receives nothing for a captured entity;
shadows, outline copies, crumbling overlays, and Fabric `submitCustom` nodes
are dropped. A glowing entity is also submitted to vanilla, without its blob
shadow, so the outline post-process still draws. Entities outside vanilla's
frustum are swept up closest-first under a budget, because a path tracer needs
them for shadows and reflections, and an entity that owns a Remix light this
frame is swept at any distance so its light never arrives without its housing.
`GameRendererMixin` diverts the first-person
`FirstPersonHandsAndItemsRenderer.submitHandsWithItems` into the capture.
Rigid occurrences retain instances keyed by owner, category, layer, and part;
`CuboidMixin` keys a cube by its sprite at `ModelPart.Cube.compile`; strips and
beams stream transient meshes per frame. Enchantment glint is a second sampler
of one pipeline in 26.3, so its capture is decided in
`RemixSubmitNodeCollector` and `RenderLayerUtils`. `RemixBlockDamagePass` submits
static terrain damage decals from `LevelRenderer.submitBlockDestroyAnimation`
(`LevelRendererBlockDamageMixin`), and the block outline is captured at
`LevelRenderer.submitBlockOutline` (`LevelRendererBlockOutlineMixin`).
`CopperBeaconBlockEntityRenderer` submits the articulated beacon model; the
mount stays in terrain. `CopperBeaconPose` is shared with the per-frame disk
light. Server-authoritative block-entity state owns the axis parameters and
anchor tick; clients reconstruct motion from those and synchronized world time.

**Lights.** Block lights are created, updated, and destroyed on the render
thread under their own budget, from resource-pack rules keyed by block state. A
hand-held item whose placed block shows a lit particle gets the same element in
hand, emitted from the capture collector's item submit once the item's pose is
on the pose stack (`RemixSubmitNodeCollector`, `HeldItemEffectsRenderer`), so
first person, the hidden shadow model, and other players share one hook. The
sun and moon light follow the `SUN_ANGLE` attribute at the camera and exist
only in a dimension whose skybox is the overworld's. `HeldLightState` is the
one decision on whether a held item's lit element is burning: the local
player's Toggle Held Lights key puts out everything in their own hands, and by
default an open flame goes out for any holder whose eyes are under water.

Terrain block lights are per-frame draws, not retained instances: every frame
`ChunkManager.draw` submits each surviving `TerrainLight` (the `RemixLight`
plus its position, power, and last drawn frame), so not drawing a light is a
complete cull. Culling applies the column Chebyshev box and the section
connectivity flood in `TerrainState`, then a power-aware reach test with
hysteresis. A light cull distance of 0 means Auto: `ChunkManager` resolves it,
and any explicit value, against Minecraft's render distance and pushes the
radius into `TerrainState` only on change, so `TerrainState` is always finite.
The flood rebuilds into a second set in per-frame slices and swaps it in when
complete; a section added mid-build marks the set dirty rather than expanding a
set about to be replaced. `LightManager` lights are never culled. Some small
emitters use `BuiltinEmission` instead of built-in analytic-light rules to avoid
diluting the sampler's candidate pool.

## Invariants

- Every Remix handle is created, updated, and destroyed on the render thread;
  workers hand over CPU data through bounded, generation-tagged queues
  (`BlockDataQueue`, `ChunkManager`).
- A retained instance is submitted where it is created or mutated, never once
  per frame (`ChunkManager`, `RemixEntitySubmitter`).
- A prepared particle payload holds world-space vertices and a live bucket, so
  every path that suspends or tears down rendering discards it rather than
  publishing it, including a world swap that keeps the connection alive
  (`RemixParticleRenderer`).
- A coplanar decal stack, such as a banner's pattern layers, is one mesh in
  paint order, because Remix orders coplanar decals by primitive index rather
  than by depth; text turns draw order into depth the same way
  (`RemixEntitySubmitter`).
- A local first-person light is submitted with a zero volumetric radiance scale,
  because the volumetric grid reuses the previous frame's radiance and a
  camera-locked emitter would smear into distant geometry (`LightManager`).
- Ordinary classes must not live in the mixin package (`MixinPackageBoundaryTest`).
- The game runs on the OpenGL backend. The GL interop, the composite, the
  weather overlay, and the sky generator's raw GL depend on it; on any other
  backend the renderer refuses to initialize rather than half-work
  (`GraphicsBackendPolicy`, `MinecraftClientMixin`, `RemixRenderer`).
- A mixin that cancels vanilla render work leaves its caller the state it
  relies on: the render-pass bindings later draws in the same pass read, any
  work outside the pass (buffer writes, index-buffer growth, matrix and
  projection stacks), and the pass a method opens for itself
  (`ChunkSectionsToRenderMixin`, `CloudRendererMixin`,
  `WeatherEffectRendererMixin`, `LevelRendererParticleMixin`). Each hook is a
  mixin class named after its 26.3 target; there is no catch-all
  `LevelRenderer` mixin.
- Never cancel `LevelExtractor.extract` or `LevelRenderer.render`, or return
  early from either. Remix is hosted inside vanilla's extraction and traversal
  and captures entities, block entities, the hand, and decals at their submits.
  Cancel only replaced draws; see
  [Cancelling Vanilla Render Work](CONTRIBUTING.md#cancelling-vanilla-render-work).
- The mod's own draws into Minecraft's targets go through `RenderPass` and
  `RenderPipeline` (`TextureOverlay`, `RemixWeatherRenderer`). Raw GL runs only
  outside an open pass and restores what the GL backend caches
  (`AtmosphericSkyGenerator`).
- A terrain recapture must clear Remix terrain, reload vanilla terrain, and
  explicitly queue the previous built set. Snapshot `builtSections` before
  `LevelExtractor.allChanged()` invalidates the compiled geometry, then call
  `recaptureTerrainForRemix`. The reload lands at the next extraction, and the
  queued set is applied there, right after `LevelRenderer.invalidateCompiledGeometry`
  (`LevelExtractorTerrainMixin`). `RtxRemixMod.rebuildLoadedTerrain()` owns
  this sequence. Commit settings once per transaction, never once per slider
  step.
- Schedule world-transition work through `client.execute`, not a flag drained
  by a world-render hook: those hooks stop when `client.level` is null, so the
  clear would land in the next world's initialization. Re-check the session
  after an executor hop.
- `JOIN` and `DISCONNECT` are not paired across server reconfiguration. A join
  handler must release the previous session's state before acquiring new state.
- Keep terrain budget loops allocation-free and do not time individual native
  calls with additional `System.nanoTime` reads. Reuse `ProfileScope`, the
  loop's deadline read, and clock-free `TerrainNativeCallStats` tallies.
  Avoid per-block/per-quad capture allocation and per-frame render allocation.
- Terrain capture has one path: every block model emits through Fabric's
  `emitQuads` into the capture emitter inside `SectionCompiler.compile`, the
  same path for vanilla and modded models (`SectionBuilderMixin`,
  `BlockQuadEmitter`).
- `BakedQuad` is an immutable record, so the entity capture's geometry digest
  is cached once per quad object in fields `BakedQuadMixin` adds to the record,
  without content verification; a resource reload bakes new quads with empty
  caches. A quad that does not carry the cache is re-hashed on every capture
  (`BakedQuadStaticGeometryCache`). That the JVM accepts the added record
  fields at runtime is not yet confirmed by a launch.
- Every retained `SectionMeshData` or render-region reference is released
  exactly once: `SectionMeshData` rejects an over-release, `DirectBufferPool`
  ignores a buffer that is already idle, and `RenderRegionStoragePool` relies
  on its callers' counts (`SectionMeshData`, `DirectBufferPool`,
  `RenderRegionStoragePool`).
- The visibility walk port produces vanilla's graph exactly: every reached
  node with its step and direction masks, which decide every later partial
  update. `-Drtxremix.vanillaVisibilityWalk` and `-Drtxremix.logVisibleSet` are
  how to prove it on a live world (`ChunkRenderingDataPreparerMixin`,
  `FarChunkProbeTest`). Frustum application and its octree stay vanilla's,
  because their emit order schedules compiles.
- A queued chunk unload is cancelled by the column's re-send, and a queued
  column that reaches the client ring's edge is sent at once regardless of
  budget (`PacedUnloadQueue`).
- Nothing a `ServerLightSnapshot` has published is ever mutated; the engine
  copies a nibble array before its first write after a publish
  (`LightStorageMixin`).
- The camera schema stays strict version 1 and its discriminator stays
  `"catmull-rom"` (`CameraPathCodec`); still shots are a separate file with
  their own strict codec, never extra waypoint fields (`StillShotCodec`).
- Authored camera paths stay separate from launcher-owned fixture staging,
  and shot sets live in the directory the trigger names, never inside a world
  fixture (`CameraController`).
- A value meant to stay dynamically blended is written as a conf layer, never
  through a user-layer config write (`ConfLayerDriver`).
- `remix-sdk/` and `rtx.conf` stay mod-owned and the `rtx-remix/` payload
  author-owned, and the native compile tasks, `setupRuntime`, and `package` stay
  aligned with the packaged contents they verify (`NativeExtractor`,
  `build.gradle`).
- The launcher's account options (`--username`, `--accessToken`, `--uuid`,
  `--xuid`, `--clientId`) are blanked in the process command line before the
  runtime loads, because its crash dumps copy that line (`RemixRenderer`,
  `CommandLineRedaction`).
- Vanilla's shared worker pool and section-builder concurrency stay capped
  (`WorkerPoolCap`, `SectionBuildConcurrency`).
- Nothing but the render thread reads client light
  (`BlockAndLightGetter.getBrightness`, `LevelLightEngine.getLayerListener`)
  while capture is on: the published snapshot is then the engine's live map
  (`ClientLightSnapshotPolicy`, `LightStorageMixin`).
- There is one overlay toggle path. `RemixInputGate` alone owns the capture
  session. Every open request goes through `RtxRemixMod.openRemixMenu`, and
  every menu the runtime shows or hides reaches the gate through
  `RtxRemixMod.followRemixMenuVisibility`, which `GameRendererMixin` calls at
  the head of `GameRenderer.update` each frame and which allocates nothing on
  the Java heap. The packaged runtime menu binding stays away from Left Alt+X
  (`KeyboardMixin`, `MouseMixin`, `RtxSettingsScreen`, `packaging/rtx.conf`).
- The placed Copper Beacon is not a `BlockLightDefaults` rule: its block entity
  owns a dynamic articulated light, and a static rule would light it twice
  (`LightManager`, `CopperBeaconBlockEntityRenderer`).
- Whether a held item's lit element burns is decided once, in `HeldLightState`;
  the flame, the smoke, the hand light, and the held beam all consult it
  (`HeldItemEffectsRenderer`, `LightManager`).
- A terrain light's cull is the decision not to draw it this frame, never a
  destroy; the radius `TerrainState` holds is finite and pushed only on
  change, because a changed radius dirties the whole flood (`ChunkManager`,
  `TerrainState`).
- Keep `rtx.showUICursor = false` in `packaging/rtx.conf`. The mod does not
  write this key; the runtime software cursor can leave the OS cursor hidden.
- Change `BlockLightDefaults` and `BlockLightIntensityControls` together.
  Their rule-id sets are validated during resource reload, not just unit tests.
- Run entity capture on the render thread. Vanilla shares mutable renderer and
  model instances; gathering entities in parallel races on their part angles.
- A captured entity, block entity, or first-person hand is submitted to
  `RemixSubmitNodeCollector`, not to vanilla's collector; only a glowing
  entity's outline submit also reaches vanilla, with its blob shadow cleared,
  because Remix traces the real shadow (`LevelRendererEntityMixin`,
  `BlockEntityRenderDispatcherMixin`, `GameRendererMixin`).
- The developer tools launch only through a signed-in Prism; they never pass
  Prism's `--offline` or launch `runClient` (`test_public_boundary.py`).

## Focused Engineering Guides

- [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md): lifecycle, recapture,
  material updates, entity precision, and runtime surface-capacity symptoms.
- [Tuning](docs/TUNING.md): queue waiting, retirement gates, worker limits, and
  JVM argument ownership. Named source classes own the defaults.
- [Benchmarking](tools/docs/BENCHMARKING.md) and
  [Profiling](tools/docs/PROFILING.md): counter interpretation and measurement.
- [Getting Started](docs/GETTING_STARTED.md): runtime staging, initialization
  failures, debug views, and worktree setup. Debug-view indices belong to the
  matching dxvk-remix source, not this repository.

## Integrated JNI Wrapper

`rtxremix-java/` is a Minecraft-agnostic Java facade and JNI shim over the
RTX Remix C API. It must never import `net.minecraft` or `net.fabricmc`;
`RemixJavaBoundaryTest.shimSourcesDoNotImportMinecraftOrFabric` enforces this
boundary across `rtxremix-java/src/`. Keep game-state capture and Fabric
integration in the mod, not in the shim. Wrapper and affected mod callers
change in the same pull request.

The wrapper's source of truth, in order, is:

1. `rtxremix-java/src/com/nvidia/rtxremix/`: `RemixAPI.java` is the facade,
   `RemixBridge.java` declares static JNI entry points, and the other API types
   are data holders, builders, constants, and handles.
2. `rtxremix-java/native/remixbridge.cpp`: loads `d3d9.dll`, calls
   `remixapi_InitializeLibrary`, stores the native interface, and forwards calls.
3. `rtxremix-java/native/include/remix_c.h`: the copied C API header.
4. `rtxremix-java/native/build_native.bat`: the MSVC bridge build.
5. `packman/remix-sdk.packman.xml`: the published runtime pins.
6. SDK source management in `tools/src/rtx_tools/sdk.py` and fetching in `build.gradle`.

The root Gradle build compiles the Java sources directly and builds
`remixbridge.dll` into `build/native/{win64|arm64}/`. There is no separate
wrapper Java build or example launcher. The native build needs Visual Studio
C++ tools and `JAVA_HOME` pointing to a 64-bit JDK 25. It uses the
copied header unless `REMIX_INCLUDE` explicitly selects another directory.
The supported targets are Windows x64 and ARM64; agent shells may be POSIX
even though the Gradle wrapper and native compiler entry points are batch scripts.

Runtime payloads live in `remix-runtime/{win64|arm64}/`, outside `build/` so
Gradle `clean` cannot traverse Packman junctions into the shared package cache.
The runtime textures' junction, `remix-runtime/textures/`, sits beside them.
Treat these junctions as read-only, and keep every Packman link under
`remix-runtime/`, which git has never tracked: a checkout of a commit that
tracks files under a junction writes and deletes them in the shared cache. Staging and packaging combine the selected
SDK with the separately built bridge; never write the bridge into a
Packman-managed directory. The SDK contains `d3d9.dll`, dependent runtime DLLs,
and USD plugin payload under `usd/`. SDK payloads and bridge outputs stay
ignored by Git.

Gradle fetches a missing SDK before `syncRuntimeSdk` and fetches the selected
architectures before packaging. Existing runtime directories and local overrides
are left alone. Run source-management commands from the repository root:

- `tools\rtx.bat sdk status --arch {win64|arm64}` reports the staged source and
  local-copy freshness without changing it.
- `tools\rtx.bat sdk local --arch {win64|arm64} --path <path>` replaces the
  selected junction with a snapshot copy and writes `.local-sdk-source`.
  Without a path, it reuses the sentinel's source, then `sdk.localPath` from
  ignored `tools/config.local.json`, then `../dxvk-remix/_output`. Relative
  paths start at the repository root. Worktrees can set that local default
  once or pass `--path`. `sdk restage` refreshes the recorded snapshot.
- `tools\rtx.bat sdk packman --arch {win64|arm64|all}` clears local staging
  and restores the pinned runtime. It is a no-op on the pinned version unless
  `--force` is passed.
- Bridge-only builds use `gradlew.bat compileNativeWin64 compileNativeArm64`,
  or `rtxremix-java/native/build_native.bat x64` or `arm64`.

The C API is reached only through JNI, never a JNA interface table. Java-side
handles stay raw `long` values, not JNA `Pointer`s or wrapper objects. Native
methods that require direct `ByteBuffer`s must never receive heap buffers;
document buffer directness on every new JNI entry point.

## Runtime And Java API Changes

A change to native API functions, structure layouts, atlas animation forwarding,
or runtime assets may span the separate native runtime, the integrated wrapper,
and the mod, so check all three layers explicitly. Renderer behavior and the
C API surface belong in dxvk-remix; marshalling and Java API types belong in
the shim.

Once the matching runtime package is published, update both architecture pins
in `packman/remix-sdk.packman.xml`, the copied header, JNI marshalling in
`rtxremix-java/native/remixbridge.cpp`, `RemixBridge.java`, `RemixAPI.java`,
affected data-holder types, and mod callers together. The copied header
matches the pinned runtime, which can be ahead of dxvk-remix `main`; never
edit it in isolation. From the repository root, restore the SDKs with
`.\tools\rtx.bat sdk packman --arch all` and rebuild the bridges with
`.\gradlew.bat compileNativeWin64 compileNativeArm64`.

`StructType.java` constants must exactly match `REMIXAPI_STRUCT_TYPE_*` in
the copied header. Parallel API additions can claim the same number; after
rebasing, assign any colliding new type the next free value in all four places:

1. `public/include/remix/remix_c.h` in dxvk-remix.
2. `src/dxvk/rtx_render/rtx_remix_specialization.inl` in dxvk-remix.
3. `rtxremix-java/native/include/remix_c.h` here.
4. `rtxremix-java/src/com/nvidia/rtxremix/StructType.java` here.

The copied header defines `REMIXAPI_VERSION_{MAJOR,MINOR,PATCH}`. The JNI
bridge passes those constants through `REMIXAPI_VERSION_MAKE(...)` to
`remixapi_InitializeLibrary`; version changes must keep the header, bridge,
facade, and runtime payload in step.

Verify the full root `build`, `test`, and `tools\rtx.bat launch` path before
requesting review. Compilation and JVM tests do not exercise JNI loading or
rendering. Open the pull request only once the runtime package that implements
the change is published and pinned: the runtime is distributed as binaries, so
the package is what makes a change public. The header and facade must never run
ahead of it.

## Tests

`TerrainStateTest` is the entry point for the JVM-side chunk-pipeline tests.
The suite does not cross the JNI boundary or render, so a rendering change is
verified by launching with `tools\rtx.bat launch` and looking. It also never
applies the mixins: only a launch proves an anchor. The mixin config sets
`defaultRequire` to 1, so an anchor that no longer matches fails the launch
with the mixin's name instead of silently capturing nothing. The exceptions
are the profiling-phase injectors marked `require = 0` in
`MinecraftClientMixin`, `ParticleManagerMixin`, and `ParticleGroupMixin`; see
[Verifying A Mixin Injection](CONTRIBUTING.md#verifying-a-mixin-injection).

Python tooling uses `tools\rtx.bat check --tests` for its test suite.
Its shared defaults pin the demo world and art pack without selecting a
graphics preset; machine-local choices belong in ignored
`tools/config.local.json`. See `tools/AGENTS.md` for the configuration and
benchmark contracts.

Keep implementation plans and task checklists outside the repository. Source
and maintained guides are the durable record, not planning scaffolding.
