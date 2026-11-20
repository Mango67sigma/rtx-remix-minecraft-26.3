# The 26.3 Port's Copy Of The Renderer Core

This directory is the Minecraft 26.3 port's own renderer core. It is the 26.3
counterpart of NVIDIA's shared [`renderer-core/`](../../../renderer-core/):
`src/` holds the sources, `test/` the tests, and `main/resources/` the
resources. Most files sit at their upstream counterpart's relative path, while
the files upstream renamed or rewrote when it extracted the core keep the
port's names or live in `versions/26.3/minecraft/`. The
catalog names it as the 26.3 target's `rendererCore` in
[`project-state.json`](../../../project-state.json), and
`gradle/loader-module.gradle` compiles it with `versions/26.3/minecraft/` and
`versions/26.3/fabric/` as one source set. The shared `renderer-core/` is not
compiled into the 26.3 JAR.

## Why It Exists

NVIDIA split the renderer into a loader- and version-neutral core and
version-specific integrations after this port was written, and its design says
a port does not copy the core. The 26.3 code still mixes 26.3 integration into
classes NVIDIA has since made neutral, so the port keeps its own copy for now
while upstream's `renderer-core/` and `versions/1.21.1/` stay byte-identical to
NVIDIA's and merge cleanly. That is a stated, transitional state. Moving the
port onto the shared core is the next project, and it ends with this directory
gone.

## The Rule: It Only Shrinks

- Add no file here. New 26.3 code and tests go in `versions/26.3/minecraft/`
  or `versions/26.3/fabric/`.
- A fix to shared renderer logic for 26.3 goes into the matching file here,
  never into `renderer-core/`.
- When an upstream merge changes a file in `renderer-core/`, port that change
  to its counterpart here, found by class and content rather than by path. For
  example, under `src/com/nvidia/rtxremix/`, `core/DirectoryRuntimePayload.java`
  is `minecraft/NativeExtractor.java` here, and
  `minecraft/terrain/ChunkManager.java` is in `versions/26.3/minecraft/`.
- When a file here is replaced by the shared core's, delete it and its line in
  `tools/tests/fork/renderer_core_copy_ledger.txt`.

`tools/tests/fork/test_transition_ledgers.py` fails if a file is added here, or
if a file listed in the ledger has gone without its line being removed. This
README is the one file the ledger ignores.
[Multi-Loader Architecture](../../../docs/MULTI_LOADER_ARCHITECTURE.md#this-fork-the-263-port)
describes the whole layout.
