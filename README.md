# LuxLoader

A programmable rendering and resource pipeline framework for Minecraft Java 26.3.

## Development status

LuxLoader is experimental and under active development. The SDK and plugin contracts are not frozen; build plugins against matching SDK artifacts. The current milestone includes plugin instance ownership, client state and events, bounded resource preparation, render observation and staged GPU uploads. Temporary world presentations now share the logical clock and route admitted visual geometry through the active pipeline, with optional Minecraft sound playback. A standalone development workbench remains planned work.

The primary development environment is Windows with JDK 25 and Vulkan. Fabric and NeoForge integrations are included, but successful compilation does not establish equivalent runtime behavior on every platform or GPU.

## Scope

Plugins own resource processing, scene construction, GPU work and final frame composition. Minecraft supplies game data. LuxLoader provides generic scheduling, GPU resources, capabilities and game integration. Disabling plugin mode restores vanilla rendering.

The architecture targets complete pipeline ownership. Current prepared-draw hooks and vanilla atlas dependencies are transitional integration paths. Ray tracing, GI, LabPBR, denoisers and vendor reconstruction SDKs belong in plugins. The current backend is Vulkan; OpenGL is not implemented.

## Modules

| Module | Responsibility |
| --- | --- |
| `luxloader-api` | Public GPU, frame graph, resource, scene and plugin contracts |
| `luxloader-core` | Plugin lifecycle, scheduling, Vulkan resources, synchronization and diagnostics |
| `luxloader-native` | FFM native library bridge and reference C headers |
| `luxloader-shader` | Shader compiler discovery, compilation and cache |
| `mc-adapter` | Version-specific game data and graphics access |
| `mc-fabric`, `mc-neoforge` | Minecraft loader integrations |
| `mc-hooks`, `mc-ui` | Shared sources compiled by both integrations |

`WorldFramePlan` declares which host stages a plugin uses, including sky, terrain, prepared features and transparency. Camera transforms, frame indices and history validity are explicit contracts. Capabilities report observed support; unsupported features remain unavailable.

`DynamicSceneMesh.cameraVisible` distinguishes the hidden first-person camera body from other posed geometry before render-type batching. It applies to direct camera views, including transparent surfaces; plugins retain independent control of that geometry's lighting and reflected visibility. Existing mesh constructors default to camera-visible geometry.

## Plugin development

See the [plugin development guide](PLUGIN_DEVELOPMENT.md) for a complete minimal plugin, SDK setup, lifecycle and frame graph contracts, scene updates, shader packaging, localization, testing and installation.

## Independent example projects

The examples are independent repositories, each with its own wrapper, build, tests and releases:

- `luxloader-example-raster`: basic raster pipeline and prepared host draws.
- `luxloader-example-rt`: terrain ray queries, dynamic secondary geometry, materials, lighting and reconstruction.
- `luxloader-example-upscale`: spatial upscale and frame synthesis API example.

They consume `dev.luxloader` Maven artifacts. The loader build does not build or download examples, and no example source directory is required to build this repository. In the local workspace, each repository sits next to `LuxLoader`.

## Build the loader

Use JDK 25 and the Gradle wrapper. NeoForge preparation tooling may also require JDK 21. Configure machine-specific JDK paths in the user-level Gradle properties file.

```powershell
.\gradlew build
.\gradlew :mc-fabric:build :mc-neoforge:compileJava
.\gradlew :luxloader-api:test :luxloader-core:test :mc-adapter:test
git diff --check
```

The first mod build downloads Minecraft toolchain artifacts. To build only the standalone SDK, without configuring Minecraft modules:

```powershell
.\gradlew "-Pluxloader.sdkOnly=true" build publishToMavenLocal
```

This publishes `luxloader-api`, `luxloader-native`, `luxloader-core` and `luxloader-shader` under `dev.luxloader:NAME:0.1.0`. `publishAllPublicationsToSdkRepository` also writes a Maven repository to `build/sdk-repository/`. Publication is local; no public Maven endpoint is configured.

Then run `./gradlew build` in an example repository. For coordinated source development, run its wrapper with `-PluxloaderCheckout=../LuxLoader`. The optional composite build substitutes the same Maven coordinates and loads only the SDK projects.

## Install and run

- Install the Fabric or NeoForge mod JAR in the game's `mods/` directory.
- Install separately built pipeline JARs in the same instance's `luxloader/pipelines/` directory.
- Restart after adding a plugin that requests Vulkan device features.
- Select a pipeline in video settings, adjust its options and apply.
- Use **Copy Info** to inspect activation failures and diagnostics.

Development clients start with Vulkan. Supply prebuilt plugins explicitly when needed:

```powershell
.\gradlew :mc-fabric:runClient "-Pluxloader.pipelineJars=C:/plugins/example.jar"
```

The comma-separated JAR list is copied into that module's run directory. Other installed plugins are preserved. Each example also provides `installPlugin "-Pluxloader.gameDir=..."`.

## Buffer uploads

Resource preparation uses bounded CPU workers, generation-aware task results and pipeline/plugin cancellation. Minecraft resource lookup and stream acquisition stay on the host thread; plugins own decoding. See the [resource processing contract](PLUGIN_DEVELOPMENT.md#resource-processing) for budgets, the legacy compatibility path and plan admission. Global resource invalidation is supported; dependency-specific invalidation is not yet implemented.

Large buffer update batches use reusable, persistently mapped staging pages and GPU buffer copies. Each recording retains its own upload ranges until submission completion; host-owned recordings retire at the host's completed-frame boundary. Idle staging pages are cached up to 64 MiB. Small batches retain inline updates. Diagnostics under `upload.staging.*` expose allocation, reuse and copy counts.

An explicit CPU recording benchmark is available with `.\gradlew :luxloader-core:test --tests '*VulkanUploadBenchmarkGpuTest' "-Dluxloader.test.gpuPipeline=true" "-Dluxloader.test.uploadBenchmark=true"`. It measures repeated 32 MiB batches with GPU completion outside the timed region; its results are not whole-frame timings.

## Language and verification

`HostServices.presentations()` registers metadata without producing output. An explicit request owns one temporary instance with a world session, start time, lifetime and coalesced parameters for visual and audio consumers. The active pipeline must declare supported visual type IDs and consume dynamic geometry. Sound assets can name a host event, a host OGG resource or immutable plugin-prepared OGG bytes. Host request results and channel activity do not prove device audibility. See [temporary world presentations](PLUGIN_DEVELOPMENT.md#temporary-world-presentations) for lifecycle, capability, source filtering and safety limits. The core and actual Minecraft sound bridge have CPU/offline tests; the independent presentation demo and in-game visual/audio acceptance remain separate work.

Source comments, committed documentation and future commit messages use English. UI and migrated diagnostic messages select English or Chinese from the Minecraft language setting. Outside Minecraft, `-Dluxloader.language=en` or `zh` overrides the system locale; unsupported languages fall back to English. Local research and verification notes under ignored `docs/` remain Chinese.

Use `Messages.tr(englishText)` for runtime messages and add matching entries to the UTF-8 `messages_en.properties` and `messages_zh.properties` catalogs in `luxloader-api`. Use the context-key overload when the same English text needs different translations. Translate cached metadata at display time, not during static initialization. Technical identifiers and external compiler output remain unchanged.

Production source, comments and test narratives use English. Tests retain Chinese fixtures for localized output and UTF-8 coverage. Historical Git commits are retained; future commit messages use English. Separate tests cover English, Chinese, fallback behavior, live language switching and locale-independent shader cache keys.

Tests use JUnit 5. GPU tests are opt-in and must run separately with `"-Dluxloader.test.gpuPipeline=true"`, because some drivers can terminate the JVM. Compilation, offline GPU validation and in-game visual acceptance are distinct results. The project owner validates game visuals. Plugin-specific features and limitations are documented in each example repository.

## License

LuxLoader is licensed under the [MIT License](LICENSE). Third-party dependencies retain their own licenses. Minecraft game code and assets are not distributed in this repository.
