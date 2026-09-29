# LuxLoader plugin development guide

This guide describes the current **0.1.0 SDK, pipeline ABI 2, Minecraft Java 26.3 and Vulkan backend**, checked against the repository on 2026-09-29. The SDK is evolving: build the loader and plugins from matching SDK artifacts. ABI 2 is a loader admission check, not a guarantee that every future 0.1.0 build has identical Java APIs.

[README.md](README.md) remains authoritative for project scope and supported build workflows. This guide covers an independent Java rendering plugin; it does not create a Fabric/NeoForge mod or require Minecraft mixins in the plugin.

## Contents

1. [Choose ownership and responsibilities](#1-choose-ownership-and-responsibilities)
2. [Prepare the SDK and project](#2-prepare-the-sdk-and-project)
3. [Build a complete minimal plugin](#3-build-a-complete-minimal-plugin)
4. [Lifecycle and frame execution](#4-lifecycle-and-frame-execution)
5. [Choose host world stages](#5-choose-host-world-stages)
6. [Resources, commands and synchronization](#6-resources-commands-and-synchronization)
7. [Scene geometry and resource updates](#7-scene-geometry-and-resource-updates)
8. [Slang and SPIR-V workflow](#8-slang-and-spir-v-workflow)
9. [Temporal effects and reconstruction](#9-temporal-effects-and-reconstruction)
10. [Settings, languages and device features](#10-settings-languages-and-device-features)
11. [Test, install and diagnose](#11-test-install-and-diagnose)
12. [Development milestones and source map](#12-development-milestones-and-source-map)

## 1. Choose ownership and responsibilities

LuxLoader's target architecture gives the plugin control of resource processing, scene construction, GPU work and final composition. Minecraft supplies game data. The framework owns generic contracts, scheduling, device/resource services and game integration. Algorithms such as RT, GI, LabPBR, denoising and vendor reconstruction belong in plugins.

Current prepared Minecraft draws and atlas access are transitional compatibility paths. A plugin can use them incrementally; their existence does not mean the long-term design is restricted to post-processing vanilla output.

| Initial goal | Starting point | Work the plugin must supply |
| --- | --- | --- |
| Image effect over the host scene | The scene-copy plugin below | Own output, effect passes and presentation |
| Draw geometry over the host scene | `luxloader-example-raster` sibling repository | Graphics pipeline, shaders, attachments and draw commands |
| Replace world rendering | `luxloader-example-rt` sibling repository | Scene conversion, material processing, lighting, depth, feature visibility and composition |
| Upscale or reconstruct frames | `luxloader-example-upscale`, then RT reconstruction code | Valid color/depth/motion inputs and an implemented reconstruction backend |

The examples are separate repositories with their own Gradle wrappers. They are not subprojects of the loader. The upscale example demonstrates API usage; an API declaration alone does not implement a vendor's reconstruction or frame-generation algorithm.

## 2. Prepare the SDK and project

Use **JDK 25**. NeoForge preparation may also require JDK 21 when building the mod; an independent SDK-only plugin does not configure Minecraft modules.

In the `LuxLoader` checkout, publish the current SDK:

```powershell
.\gradlew "-Pluxloader.sdkOnly=true" build publishToMavenLocal
```

This publishes `dev.luxloader:luxloader-api`, `luxloader-native`, `luxloader-core` and `luxloader-shader`, currently version `0.1.0`. No public Maven endpoint is configured. For an exported Maven repository, use `publishAllPublicationsToSdkRepository`; its output is `build/sdk-repository/`, which consumers must explicitly add as a repository.

Create an independent sibling directory such as `luxloader-plugin-demo`. Copy the wrapper files from an existing example: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and `gradle/wrapper/gradle-wrapper.properties`. Keep the wrapper files together. Do not copy that project's Git metadata, build output or plugin identifiers.

The minimal project has this layout:

```text
luxloader-plugin-demo/
  gradlew / gradlew.bat
  gradle/wrapper/
  settings.gradle
  build.gradle
  gradle.properties
  src/main/java/com/example/lux/DemoPlugin.java
  src/main/resources/
    META-INF/services/dev.luxloader.api.plugin.PipelinePlugin
    com/example/lux/messages_en.properties
    com/example/lux/messages_zh.properties
  src/test/java/com/example/lux/DemoPluginTest.java
  .gitignore
```

SDK dependencies are provided by the loader at runtime. A regular Java JAR contains your classes/resources, not its dependency JARs. Do not shade a second copy of `dev.luxloader` API/core or LWJGL into a plugin. Additional third-party dependencies need an explicit packaging and licensing plan; they are not downloaded from Maven by the plugin loader.

## 3. Build a complete minimal plugin

Create each file below at the indicated path. The example copies the composed host scene into a plugin-owned image, publishes it and requests presentation. **The expected image is visually unchanged.** This first step verifies discovery and the output path without requiring a shader compiler. It deliberately retains host drawing; a later section explains scene replacement.

Replace `com.example.lux`, plugin names, author, version and license before distributing your own plugin. Plugin, pipeline and pass IDs must remain unique and stable. The entry class must be public, implement `PipelinePlugin`, and have a public zero-argument constructor.

### `settings.gradle`

<!-- file: settings.gradle -->
```groovy
rootProject.name = 'luxloader-plugin-demo'

def checkout = providers.gradleProperty('luxloaderCheckout').orNull
if (checkout) {
    includeBuild(checkout)
}
```

### `gradle.properties`

<!-- file: gradle.properties -->
```properties
plugin_version=0.1.0
luxloader_version=0.1.0
org.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8
org.gradle.caching=true
```

### `build.gradle`

<!-- file: build.gradle -->
```groovy
plugins {
    id 'java-library'
}

group = 'com.example.lux'
version = providers.gradleProperty('plugin_version').get()
def luxloaderVersion = providers.gradleProperty('luxloader_version').get()

repositories {
    mavenLocal {
        content { includeGroup 'dev.luxloader' }
    }
    mavenCentral()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

dependencies {
    compileOnly "dev.luxloader:luxloader-api:${luxloaderVersion}"
    testImplementation "dev.luxloader:luxloader-api:${luxloaderVersion}"
    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

tasks.withType(Test).configureEach {
    useJUnitPlatform()
    systemProperty 'file.encoding', 'UTF-8'
}

tasks.named('jar') {
    manifest {
        attributes(
                'Implementation-Title': 'LuxLoader Plugin Demo',
                'Implementation-Version': project.version,
                'LuxLoader-Pipeline-ABI': '2')
    }
}

tasks.register('installPlugin', Copy) {
    from(tasks.named('jar').flatMap { it.archiveFile })
    into(providers.provider {
        def gameDir = providers.gradleProperty('luxloader.gameDir').orNull
        if (!gameDir) {
            throw new GradleException('Set -Pluxloader.gameDir to the Minecraft instance directory')
        }
        new File(gameDir, 'luxloader/pipelines')
    })
    doNotTrackState('Preserve other installed plugins')
}
```

### `.gitignore`

<!-- file: .gitignore -->
```text
.gradle/
build/
docs/
local.properties
```

### `src/main/java/com/example/lux/DemoPlugin.java`

<!-- file: src/main/java/com/example/lux/DemoPlugin.java -->
```java
package com.example.lux;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.i18n.Messages;
import dev.luxloader.api.pipeline.FrameControl;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.FrameSetup;
import dev.luxloader.api.pipeline.HostSceneMode;
import dev.luxloader.api.pipeline.PassHost;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.PresentRequest;
import dev.luxloader.api.pipeline.RenderContext;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.pipeline.Requirements;
import dev.luxloader.api.pipeline.ResourceRequest;
import dev.luxloader.api.pipeline.StageKind;
import dev.luxloader.api.pipeline.WorldFramePlan;
import dev.luxloader.api.plugin.PipelinePlugin;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Supplier;

public final class DemoPlugin implements PipelinePlugin.SinglePipeline {
    public static final GpuId ID = new GpuId("com.example.lux", "scene-copy");
    public static final GpuId PIPELINE_ID = ID.child("pipeline");

    static String text(String key) {
        Locale locale = Messages.language().equals("zh") ? Locale.CHINESE : Locale.ENGLISH;
        return ResourceBundle.getBundle("com.example.lux.messages", locale,
                DemoPlugin.class.getClassLoader(), ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES)).getString(key);
    }

    @Override
    public LuxMod mod() {
        return LuxMod.builder(ID, text("plugin.name"), "0.1.0")
                .authors("Example Author")
                .description(text("plugin.description"))
                .license("MIT")
                .mcConstraint("[26.3,)")
                .build();
    }

    @Override
    public GpuId pipelineId() {
        return PIPELINE_ID;
    }

    @Override
    public PipelineDescriptor pipelineDescriptor() {
        return PipelineDescriptor.builder(PIPELINE_ID, text("pipeline.name"), "0.1.0")
                .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                .frameOwnership(PipelineDescriptor.FrameOwnership.SHARED)
                .requirements(Requirements.builder()
                        .minimumVulkanVersion("1.3")
                        .requireCapability(CapabilityDescriptor.Ids.VULKAN_BACKEND,
                                CapabilityLevel.NATIVE)
                        .build())
                .build();
    }

    @Override
    public Supplier<RenderPipeline> pipelineFactory() {
        return () -> new CopyPipeline(pipelineDescriptor());
    }

    private static final class CopyPipeline implements RenderPipeline {
        private final PipelineDescriptor descriptor;
        private CopyPass pass;

        CopyPipeline(PipelineDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public PipelineDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void initialize(RenderContext context) {
            pass = new CopyPass(descriptor.id().child("copy"), this);
            pass.initialize(context);
        }

        @Override
        public List<RenderPass> passes() {
            return pass == null ? List.of() : List.of(pass);
        }

        @Override
        public HostSceneMode hostSceneMode() {
            return HostSceneMode.COMPATIBILITY;
        }

        @Override
        public WorldFramePlan worldFramePlan() {
            return WorldFramePlan.builder().sky().preparedScene().pipeline().build();
        }

        @Override
        public FrameControl setupFrame(FrameSetup setup, FrameContext frame) {
            pass.produced = false;
            return setup.worldLoaded() ? FrameControl.normal() : FrameControl.skip(text("skip.noWorld"));
        }

        @Override
        public void encodeFrame(FrameGraph graph, FrameContext frame) {
            graph.addPass(pass);
        }

        @Override
        public PresentRequest present(FrameContext frame, PresentRequest.Target target) {
            return new PresentRequest(target, pass != null && pass.produced
                    ? PresentRequest.Request.of(pass.output) : PresentRequest.Request.DEFAULT);
        }

        @Override
        public void close() {
            if (pass != null) {
                pass.close();
                pass = null;
            }
        }
    }

    private static final class CopyPass extends PassHost {
        private ImageHandle output;
        private int width;
        private int height;
        private boolean produced;

        CopyPass(GpuId id, RenderPipeline owner) {
            super(id, text("pass.name"), StageKind.CUSTOM_POST, owner);
            reads(FrameGraph.COLOR);
            writes(FrameGraph.FINAL);
        }

        @Override
        protected ResourceRequest doInitialize(RenderContext context) {
            width = context.renderWidth();
            height = context.renderHeight();
            ImageDesc target = ImageDesc.builder("scene-copy-output", width, height,
                            context.setup().colorFormat())
                    .usage(ImageDesc.Usage.TRANSFER_DST, ImageDesc.Usage.TRANSFER_SRC,
                            ImageDesc.Usage.SAMPLED)
                    .build();
            output = context.resources().image(target);
            return ResourceRequest.builder().image(target).build();
        }

        @Override
        protected void doEncode(FrameGraph graph, FrameContext frame) {
            produced = false;
            ImageHandle source = graph.texture(FrameGraph.COLOR).orElse(null);
            if (source == null || source.isNull()) {
                throw new IllegalStateException(text("error.noColor"));
            }
            var commands = frame.commands().begin("scene-copy");
            commands.blitImage(source, null, width, height, output, null, width, height);
            commands.end();
            graph.publish(FrameGraph.FINAL, output);
            produced = true;
        }

        @Override
        protected void doClose() {
            if (output != null) {
                resources().release(output);
                output = null;
            }
            produced = false;
        }
    }
}
```

### `src/main/resources/META-INF/services/dev.luxloader.api.plugin.PipelinePlugin`

<!-- file: src/main/resources/META-INF/services/dev.luxloader.api.plugin.PipelinePlugin -->
```text
com.example.lux.DemoPlugin
```

### `src/main/resources/com/example/lux/messages_en.properties`

<!-- file: src/main/resources/com/example/lux/messages_en.properties -->
```properties
plugin.name=Example: Scene Copy
plugin.description=Copies the prepared host scene into a plugin-owned output
pipeline.name=Scene Copy
pass.name=Copy scene color
skip.noWorld=No world is loaded
error.noColor=The host did not provide frame.color
```

### `src/main/resources/com/example/lux/messages_zh.properties`

<!-- file: src/main/resources/com/example/lux/messages_zh.properties -->
```properties
plugin.name=示例：场景复制
plugin.description=将宿主准备的场景复制到插件持有的输出图像
pipeline.name=场景复制
pass.name=复制场景颜色
skip.noWorld=当前没有加载世界
error.noColor=宿主未提供 frame.color
```

### `src/test/java/com/example/lux/DemoPluginTest.java`

<!-- file: src/test/java/com/example/lux/DemoPluginTest.java -->
```java
package com.example.lux;

import dev.luxloader.api.i18n.Messages;
import dev.luxloader.api.pipeline.HostSceneMode;
import dev.luxloader.api.pipeline.WorldFramePlan;
import dev.luxloader.api.plugin.PipelinePlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

final class DemoPluginTest {
    @Test
    void serviceRegistrationCreatesAnIndependentPipeline() {
        var plugin = ServiceLoader.load(PipelinePlugin.class).stream()
                .filter(provider -> provider.type() == DemoPlugin.class)
                .findFirst().orElseThrow().get();
        var entry = assertInstanceOf(PipelinePlugin.SinglePipeline.class, plugin);
        try (var first = entry.pipelineFactory().get(); var second = entry.pipelineFactory().get()) {
            assertNotSame(first, second);
            assertEquals(entry.pipelineId(), first.descriptor().id());
            assertEquals(HostSceneMode.COMPATIBILITY, first.hostSceneMode());
            assertEquals(List.of(WorldFramePlan.Step.SKY, WorldFramePlan.Step.PREPARED_SCENE,
                    WorldFramePlan.Step.PIPELINE), first.worldFramePlan().steps());
        }
    }

    @Test
    void pluginCatalogFollowsHostLanguageAndFallback() {
        String previous = Messages.language();
        try {
            Messages.setLanguage("en");
            assertEquals("Example: Scene Copy", DemoPlugin.text("plugin.name"));
            Messages.setLanguage("zh-CN");
            assertEquals("示例：场景复制", DemoPlugin.text("plugin.name"));
            Messages.setLanguage("fr");
            assertEquals("Example: Scene Copy", DemoPlugin.text("plugin.name"));
        } finally {
            Messages.setLanguage(previous);
        }
    }
}
```

Build and run the CPU tests from the new plugin directory:

```powershell
.\gradlew build
```

The output is `build/libs/luxloader-plugin-demo-0.1.0.jar`. The tests exercise ServiceLoader discovery, independent pipeline creation, stage selection and English/Chinese/fallback catalogs. They do not create a GPU device or prove image correctness. Also inspect the built archive: it must contain the service file, translation resources and `LuxLoader-Pipeline-ABI: 2` in `META-INF/MANIFEST.MF`.

For coordinated source development instead of Maven Local:

```powershell
.\gradlew build "-PluxloaderCheckout=../LuxLoader"
```

The optional `includeBuild` substitutes matching `dev.luxloader` Maven coordinates. The loader's composite build defaults to SDK-only. Compile-time substitution does not replace an already installed loader mod; update that JAR as well after API changes.

## 4. Lifecycle and frame execution

```text
Scan JAR -> check ABI -> ServiceLoader -> declareConfig / onLoad
Device ready -> probe
Activate -> construct RenderPipeline -> initialize -> initialize each pass
Each world frame:
  select WorldFramePlan before host draws
  at PIPELINE: setupFrame -> encodeFrame -> execute pass encoders -> present
  endFrame after submission
Resize / settings change -> adapt or rebuild
Deactivate / reload -> GPU idle -> close passes and pipeline
Unload -> onUnload
```

| API | Responsibilities and constraints |
| --- | --- |
| `PipelinePlugin.onLoad` | Fast registration. Do not allocate GPU resources, compile shaders or perform blocking work. `SinglePipeline` implements registration for the minimal example. |
| `declareConfig` | Cheap static schema construction; it can run during prescan before a device exists. |
| `probe` | Report actual availability after device readiness. It cannot retroactively enable Vulkan device features. |
| `RenderPipeline.initialize` | Allocate resources, load/compile shaders, create GPU pipelines and explicitly initialize passes. |
| `passes` | Return the stable pass inventory. The loader caches it. |
| `worldFramePlan` | Choose retained host drawing before world execution. The overload receives the snapshot, geometry feed and commands. |
| `setupFrame` | Decide whether this frame runs and whether resolution/history changes are needed. Clear per-frame output-valid flags. |
| `encodeFrame` | Add pass nodes and dependencies to the graph. GPU recording occurs later inside pass `encode`/`doEncode`. |
| `present` | Request a valid image produced this frame. Do not return a stale output after a skipped or failed pass. |
| `resize` | Return `true` only after adapting all affected resources. The default `false` requests a rebuild. |
| `endFrame` | Submission has occurred; this is **not** proof that the GPU has completed the work. |
| `close` | Close passes and release owned resources. Normal loader teardown ensures the GPU is idle first. |
| `PipelinePlugin.onUnload` | Called exactly once per loaded plugin instance, after its pipeline has closed and before the loader drops the instance. Use it for plugin-level resources and for subscriptions that outlive a pipeline. A reload unloads the replaced instance; a pipeline switch does not. Throwing here is reported and does not stop other plugins from unloading. |

A plugin whose `onLoad` failed is unloaded immediately, not when the loader closes: the loader removes the failed instance from the active set, releases its scene contributions, revokes the capabilities it claimed, drops the pipelines it registered, and calls `onUnload` exactly once. Write release code that tolerates partial initialization.

**Instance identity.** A plugin ID is stable across reloads; the instance behind it is not. The loader gives every loaded instance its own `HostServices` object, and the object stops accepting calls once that instance is unloaded or its load failed. A plugin that keeps an old reference cannot then register, release, switch or reload anything with the same plugin ID:

- Read-only services (`mod`, `settings`, `capabilities`, `diagnostics`, `activePipeline`) keep working for diagnostics.
- State-changing calls (`registerPipeline`, `registerCapability`, `registerNativeLibraryPath`, `registerSceneContributor`, `deregisterSceneContributor`, `requestReload`, `requestPipelineSwitch`) throw `IllegalStateException`.
- Release whatever the instance owns in its own `onUnload`; do not rely on a later callback from an old object, and do not cache `HostServices` across a reload.

**Capability claims.** `registerCapability` is a claim recorded with your plugin as owner, kept apart from device, host and adapter facts:

- Levels compete; the highest valid level wins regardless of submission order. A claim below the current level is rejected.
- A rejected claim leaves no trace, so it never becomes authority to remove another provider's capability.
- When your plugin unloads or fails to load, only its own claims are released. The effective level then falls back to the next best valid source, which keeps a capability another plugin or the device still provides usable. It reports unsupported only when nothing valid remains.
- Registering the same ID twice updates your own claim; it does not create a second owner.

`PassHost` provides enabled/required state and a resource provider. Returning a `ResourceRequest` declares requirements; it does not allocate every described resource or compile shaders automatically. The minimal example both allocates the image and returns its declaration.

`StageKind` describes a pass's purpose. It does not place all GI, post or upscale passes into an automatic fixed order. Declare resource reads/writes and explicit dependencies for ordering.

## 5. Choose host world stages

`HostSceneMode` describes host-scene intent. `WorldFramePlan` selects the concrete ordered stages in the world hook. `PipelineDescriptor.kind` and `frameOwnership` are classification/compatibility metadata; setting `SCENE` or `FRAME` does not implement a scene renderer.

| Step | Retained host work |
| --- | --- |
| `SKY` | Host sky |
| `PREPARED_SCENE` | Prepared terrain, features and transparency |
| `PREPARED_FEATURES` | Prepared features and translucent draws, without opaque chunk terrain |
| `PREPARED_OPAQUE_SCENE` | Opaque/cutout terrain and opaque features |
| `PREPARED_OPAQUE_FEATURES` | Opaque features only; plugin supplies opaque/cutout terrain |
| `PREPARED_TRANSPARENCY` | Prepared transparency and overlays using current world color/depth |
| `PREPARED_TRANSPARENCY_FEATURES` | Transparent features and overlays without translucent chunk terrain |
| `PIPELINE` | The active plugin's frame graph |

Typical plans:

```java
// Process a fully prepared host scene.
WorldFramePlan.builder().sky().preparedScene().pipeline().build();

// Supply terrain and water, then retain host opaque and transparent features.
WorldFramePlan.builder().sky().pipeline()
        .preparedOpaqueFeatures().preparedTransparencyFeatures().build();

// Supply all world geometry, retaining only the host sky.
WorldFramePlan.builder().sky().pipeline().build();
```

For world replacement, opt into `HostSceneMode.PLUGIN_SCENE` and supply the necessary scene output. A plan must contain `PIPELINE` exactly once. Whole prepared-scene modes cannot be combined with split modes that redraw the same content. `PREPARED_FEATURES` is not equivalent to retaining only opaque entities; account for its translucent work.

Stages after `PIPELINE` need correct world color and depth for occlusion and blending. A plugin that omits host terrain or transparency must actually render the omitted content. Plan a compatibility fallback if scene data or required GPU features are unavailable. Do not suppress host work first and hope a later failed initialization supplies the missing image.

## 6. Resources, commands and synchronization

### Frame graph contracts

| Name | Intended input/output | Availability |
| --- | --- | --- |
| `FrameGraph.COLOR` | Host scene color at render resolution | Query the current graph; the compatibility hook supplies composed color when available |
| `DEPTH`, `MOTION` | Scene depth and motion at render resolution | Optional; the compatibility path does not guarantee access to the host frame graph's images |
| `EXPOSURE` | Exposure input, normally 1x1 | Optional |
| `UI`, `HISTORY`, `SWAPCHAIN` | Display-oriented resources | Optional; presence and semantics depend on the integration |
| `FINAL` | Plugin final output name | The plugin must publish its actual image |

Use `graph.texture(name)` and handle its `Optional` result. Never substitute an uninitialized image for a required input. An effect that requires unavailable depth/motion must generate valid inputs itself, use an explicit supported fallback, or decline activation.

`reads`/`writes` declare dependency and synchronization information. `graph.publish` exposes a real handle to later passes. `graph.define` describes a resource; it does not allocate it. Publishing a name and declaring a write are complementary operations.

### Ownership and recording

- Allocate persistent images/buffers through `RenderContext.resources()`. Release only resources your pipeline owns. Host color/depth, swapchain handles and borrowed native views are not yours to destroy.
- Keep render dimensions and display dimensions separate. Use the actual format and extent for each image; do not reinterpret a host handle as an assumed HDR or depth format.
- Record through `frame.commands().begin(...)` and end the recording. The loader controls normal submission and presentation. Avoid per-frame `waitIdle`, synchronous readback, shader compilation and resource creation.
- Declare all descriptors statically referenced by the selected shader, and bind valid resources before dispatch/draw. A runtime branch does not make an unwritten descriptor valid. Use a separate shader variant or a compatible initialized fallback resource.
- Explicitly register a compute/graphics pipeline with shader bytes during initialization. A `ComputePipelineDesc` containing a `.spv` path alone does not cause the loader to load and create that pipeline.
- Frame graph resource declarations cannot describe every hazard between multiple dispatches inside one pass. Record the required intra-pass transitions/barriers yourself.
- Preserve resources and upload ranges until GPU completion. `endFrame` is not a retirement fence. For simple resizing, use the default rebuild path; custom live replacement needs safe deferred retirement.
- The provider is pipeline-scoped and remaining allocations are reclaimed during teardown. Calling `PassHost.close()` alone only runs its cleanup hook. Do not assume it closes every resource automatically.
- A compute or transfer queue may be the graphics queue. Check actual queue support and host recording constraints before designing asynchronous GPU work.

`RenderContext` can be retained for the pipeline lifetime; its initial setup does not automatically become current after a resize. Treat `FrameContext` and host frame resources as current-frame data. CPU background jobs should consume owned immutable snapshots, then publish results with revision checks on the appropriate render thread.

## 7. Scene geometry and resource updates

Override `requiresSceneSnapshot()` when using prepared geometry/material references. Set `requiresDynamicGeometry()` when needing posed feature geometry for custom drawing or secondary visibility. These requests obtain data; they do not themselves render it.

### Static geometry

Use `SceneGeometryFeed.changesSince(lastRevision)` to consume deltas. Each consumer maintains its own cursor:

1. On `reset`, discard the logical old scene and rebuild from the supplied state.
2. Apply removals and upserts by mesh ID.
3. Rebuild/upload only affected geometry and acceleration structures.
4. Advance the cursor after the update has been accepted into your scene state.
5. Retire replaced GPU allocations only when no in-flight work references them.

An async build result may be stale by the time it finishes. Check the mesh/resource revision before publishing it. Avoid traversing, decoding and rebuilding every mesh on every camera movement.

Read `CompiledSceneMesh` topology, index type, stride, vertex attributes, material references and origin. Convert local vertices with the supplied origin and validate indices. Minecraft data includes custom models, cutout planes, fluids and arbitrary triangles; do not assume all accepted meshes are full cubes or have a fixed vertex layout.

### Dynamic geometry and first-person visibility

`DynamicSceneMesh` carries posed geometry and `cameraVisible`. A hidden first-person player body may still be needed for shadows and external reflections. Preserve that distinction through material packing and batching:

- Direct camera rays, including transmission through water/glass, respect camera visibility.
- Secondary reflected visibility and shadow casting need their own rules.
- Rays originating inside a hidden body must not turn its exit faces into visible black cross-sections.
- Older constructors default `cameraVisible` to `true`.

If your renderer supplies entity shadows, return `false` from `usesPreparedEntityShadows()` to avoid retaining the host's projected shadow decals as additional geometry. This switch does not implement your own shadows.

### Contribution ownership and release

`host().registerSceneContributor(id, contributor)` publishes plugin-owned geometry that the loader merges into the dynamic mesh list after the host's own meshes. Contributions are per plugin, not per pipeline:

- The ID must equal your plugin ID or be a child of it, for example `com.example.lux:scene-copy/terrain`. An ID outside your namespace is rejected, so a contribution can always be traced to exactly one owner.
- `host().deregisterSceneContributor(id)` releases it when your plugin decides to stop contributing. Releasing an unknown ID is ignored; releasing another plugin's ID is rejected.
- The loader releases everything your plugin registered when the plugin unloads, and it never carries a previous instance's registration into a reloaded session.
- A contributor that throws or returns `null` is reported once and skipped for that frame; the remaining contributors and the rest of the frame continue. Fix the cause rather than relying on silent skipping.
- Deactivating or switching a pipeline does not unregister contributions; the plugin instance is still loaded.
- Registry changes take effect at the next pass boundary. A pass runs the contributions captured when it started, so a contributor that releases itself, or is released by an earlier contributor, still runs exactly once in that pass; a contributor registered during the merge runs in the following pass of the same merge. Register, release and let contributions run from the same thread (the render thread or an equivalent single-threaded boundary).

### Resource processing

Use `ResourceAccess` to read the active resource stack and track `revision()` for resource changes. Decode textures/material formats and construct plugin-owned GPU assets at load/reload time. Resource-pack revision and texture animation are different update signals; unchanged resource revision does not freeze animated content.

The snapshot's current atlas references are a migration path. Keep format-specific decoding inside the plugin. Do not add block-name-specific PBR rules or RT concepts to the generic loader API. Prepared scene data also does not guarantee that every third-party native draw has a faithful mesh representation; validate compatibility with the host stages you retain.

## 8. Slang and SPIR-V workflow

Use separate locations for source, reviewed precompiled artifacts and transient build output:

```text
src/main/slang/                 # Authored entry points
src/main/slang/include/         # Shared shader code
src/main/slang/profiles/        # Optional diagnostics/benchmark variants
precompiled/shaders/           # Shipped SPIR-V, if the project checks binaries in
precompiled/shaders.lock.json  # Source/compiler/binary inventory, if using this workflow
tools/shaders/                 # Compilation and validation scripts
build/generated/shaders/       # Intermediate output, ignored by Git
src/main/resources/           # Other packaged runtime resources
```

`.slang` is source; `.spv` is compiled GPU code. Gradle packaging maps them to runtime classpath paths, commonly `/shaders/...`. The RT example currently packages source and SPIR-V in that shared classpath namespace for compatibility, while keeping their repository ownership separate. Moving source directories must not silently break Java resource paths.

For a standalone compute entry named `main`, a basic offline invocation is:

```powershell
New-Item -ItemType Directory -Force build/generated/shaders | Out-Null
& "C:/VulkanSDK/1.4.357.0/Bin/slangc.exe" src/main/slang/my_pass.slang -target spirv -profile spirv_1_5 -entry main -stage compute -o build/generated/shaders/my_pass.spv
& "C:/VulkanSDK/1.4.357.0/Bin/spirv-val.exe" --target-env vulkan1.2 build/generated/shaders/my_pass.spv
```

Adapt include paths, target environment, capabilities, entry names and compiler flags to the shader and device contract. The generic command is not the complete RT shader build. Validate **all affected variants** before replacing shipped binaries; promote the source/binary manifest only after successful compilation and validation.

The RT repository provides project-specific tasks:

```powershell
.\gradlew compileLightingShaders "-Pluxloader.slangc=C:/VulkanSDK/1.4.357.0/Bin/slangc.exe" "-Pluxloader.spirvVal=C:/VulkanSDK/1.4.357.0/Bin/spirv-val.exe"
.\gradlew verifyLightingShaders
```

Those task names are not built-in loader tasks and are not present in the minimal project. Pin compiler/SDK versions, preserve third-party notices, and verify packaged resource names against Java pipeline entry mappings.

Alternatively, use `luxloader-shader` and `ShaderLibrary` for runtime compilation as the raster example does. Compile during pipeline initialization and cache results, not in `onLoad` or every frame. Runtime compilation requires an available compiler; distributing validated precompiled SPIR-V avoids that end-user requirement.

## 9. Temporal effects and reconstruction

Temporal inputs need explicit conventions before adding denoising, TAA, FSR or XeSS:

- `CameraParams` matrices are row-major. Convert at boundaries with libraries that expose column-major storage; do not transpose opportunistically in unrelated shaders.
- Current/previous projection, inverse matrices and camera positions must describe the corresponding frames. SDK jitter is measured in render-resolution pixels; convert to the vendor's required units and sign exactly once.
- Document the depth convention, motion-vector direction, coordinate space and scale, color range, exposure and render/display dimensions. A texture handle does not imply that these conventions match a vendor SDK.
- Keep history on normal camera motion and reproject it. Invalidate on actual camera cuts, incompatible resource/format changes, resize or explicit reset; advancing a frame index alone is not a reset.
- Reject incompatible history using suitable depth, normal, material and disocclusion tests. Reflections/transmission can require secondary-hit motion and independent history, beyond primary surface motion.
- Reactive masks and transparency/composition masks have distinct backend contracts. Supply the correct inputs for each SDK instead of assuming one mask means the same thing everywhere.
- Temporal reconstruction cannot automatically repair missing geometry, incorrect alpha tests, bad motion vectors or a noisy lighting signal with no valid history. Validate these inputs before adjusting blur strength.

Test static convergence, camera translation, rotation, moving entities, alpha-cutout silhouettes against the sky, water/glass, newly exposed surfaces and history reset. Test with reconstruction disabled as well as each supported backend. Record visual observations separately from shader compilation and headless GPU results.

## 10. Settings, languages and device features

Declare options using `ConfigSchema`/`ConfigOption` in `declareConfig()`. Read plugin-scoped settings from host services. Return the appropriate `SettingsChangeResult`:

| Change | Typical action |
| --- | --- |
| Uniform-only parameter | Apply it and return `APPLIED` |
| Buffer/image dimensions, formats, shader variants or algorithm resources | Rebuild affected resources safely; `NEEDS_REINIT` is the simple path |
| Selection of another pipeline | `NEEDS_PIPELINE_SWAP` |
| Newly required Vulkan device feature/extension | Arrange a device restart; pipeline reinitialization cannot enable it on an existing device |

Declare required device feature sets through bootstrap requests early enough for device creation. Distinguish **supported** from **enabled** features. Check actual device readiness in probing/initialization and explain unsupported configurations with localized diagnostics. A device extension string alone is insufficient proof that its associated feature was enabled.

Use plugin-owned English and Chinese resource catalogs for plugin UI and runtime logs, as in the minimal example. `Messages.language()` provides the host-selected `en`/`zh`; the loader's `Messages.tr` catalog does not automatically load arbitrary plugin bundles. Core changes use the core catalogs instead.

Resolve strings at use/display time. Registration descriptors or pass names cached by the host may require a reload to refresh; do not promise live translation of metadata merely because the lookup method is dynamic. Keep technical IDs, shader names and external compiler output unchanged. Source comments and committed documentation use English; ignored local notes under `docs/` remain Chinese.

## 11. Test, install and diagnose

### Verification layers

1. **CPU/unit tests:** plugin discovery, ID/descriptor contracts, scene-delta handling, visibility metadata, resource revision changes and settings behavior.
2. **Build/package checks:** matching SDK, SPI file, ABI manifest, translation resources, shader inventory and dependency packaging.
3. **Offline GPU tests:** create/execute the affected pipeline, check image/buffer results, resize/reset behavior and resource cleanup. Configure the test task to forward the opt-in property if adopting the examples' pattern.
4. **In-game acceptance:** the project owner checks screenshots/motion and gameplay compatibility in the intended map. CPU or GPU test success alone does not prove this step.

In the example repositories, run GPU tests separately:

```powershell
.\gradlew test "-Dluxloader.test.gpuPipeline=true"
```

The minimal project has only CPU tests and does not enable this convention. GPU tests may terminate the JVM on a driver fault, so isolate them from ordinary unit runs. For meaningful performance comparisons, avoid a concurrently running game using the same GPU.

### Installation

Put the loader mod in the instance's `mods/` directory and the plugin JAR in **that same instance's** `luxloader/pipelines/`. A rendering plugin JAR is not a replacement loader mod.

From the minimal project's directory:

```powershell
.\gradlew build installPlugin "-Pluxloader.gameDir=C:/path/to/minecraft-instance"
```

`installPlugin` copies the built JAR without removing other plugins. When changing your own JAR filename/version, remove obsolete copies of that same plugin to prevent duplicate providers. Restart for new device features, then select the pipeline and apply its settings. The demo should preserve the original scene appearance.

For a loader development client, run from the **LuxLoader** directory:

```powershell
.\gradlew :mc-fabric:runClient "-Pluxloader.pipelineJars=C:/path/to/luxloader-plugin-demo/build/libs/luxloader-plugin-demo-0.1.0.jar"
```

This copies explicitly selected prebuilt JARs; it does not build the separate plugin project. Check activation details with the game's **Copy Info** diagnostics.

### Diagnosis and profiling

| Symptom | Check first |
| --- | --- |
| Plugin missing from selection | Correct instance directory, SPI path/class, public zero-argument constructor, ABI 2 manifest and dependency load errors |
| Plugin registered but rejected | Requirements, actual enabled features and probe diagnostics |
| Black or stale output | Actual pipeline creation, complete descriptor bindings, reads/writes, published handles and current-frame output validity |
| Terrain/entities/water disappear | Omitted or duplicated `WorldFramePlan` stages, alpha tests, depth and host/plugin composition |
| First-person legs become black patches | Propagation of `cameraVisible`, transparent camera paths and rays exiting hidden geometry |
| Resize or backend switch breaks rendering | Resource extents/formats, descriptor rebuilding, in-flight lifetime and history reset |
| Motion smears or never converges | Jitter units/sign, current/previous matrices, valid motion/depth, history rejection and repeated resets |
| New chunks or block edits cause stalls | Full-scene rebuilds, synchronous uploads/BLAS builds, waits, allocation spikes and stale async work |
| Shader changes have no effect | Packaged SPIR-V, resource path, shader manifest and actual installed JAR |
| `NoSuchMethodError` after SDK changes | Loader and plugin compiled against different API revisions, including stale Maven Local artifacts |

Use diagnostics facts for configuration and counters, and metrics with explicit units. CPU recording time is not GPU execution time: use GPU timestamps and asynchronous result collection for GPU cost. Compare the same map, camera, settings, resolution, warm-up and sample window; record median and tail frame times, not only a single FPS value. A changed scene is useful context, not a controlled performance result.

## 12. Development milestones and source map

Recommended implementation order:

1. Discover and activate the minimal scene-copy plugin.
2. Add one shader pass with a deterministic visible result; confirm packaging and output ownership.
3. Implement resize, settings reload, switching and cleanup.
4. Consume resource revisions and scene deltas; preserve arbitrary topology and material/visibility data.
5. Replace chosen host world stages while retaining an explicit fallback.
6. Validate depth, motion, alpha and transparency before temporal algorithms.
7. Add denoising/reconstruction and targeted GPU regressions.
8. Profile the same scene and optimize the measured bottleneck; keep visual acceptance separate.

Read these contracts alongside implementation:

- [PipelinePlugin](luxloader-api/src/main/java/dev/luxloader/api/plugin/PipelinePlugin.java) and [PluginBootstrap](luxloader-api/src/main/java/dev/luxloader/api/plugin/PluginBootstrap.java): registration and services.
- [RenderPipeline](luxloader-api/src/main/java/dev/luxloader/api/pipeline/RenderPipeline.java), [PassHost](luxloader-api/src/main/java/dev/luxloader/api/pipeline/PassHost.java) and [WorldFramePlan](luxloader-api/src/main/java/dev/luxloader/api/pipeline/WorldFramePlan.java): lifecycle and world stages.
- [FrameGraph](luxloader-api/src/main/java/dev/luxloader/api/pipeline/FrameGraph.java), [GpuResourceProvider](luxloader-api/src/main/java/dev/luxloader/api/pipeline/GpuResourceProvider.java) and [GpuCommands](luxloader-api/src/main/java/dev/luxloader/api/gpu/GpuCommands.java): graph/resources/recording.
- [FrameContext](luxloader-api/src/main/java/dev/luxloader/api/frame/FrameContext.java) and [CameraParams](luxloader-api/src/main/java/dev/luxloader/api/frame/CameraParams.java): frame and camera data.
- [SceneGeometryFeed](luxloader-api/src/main/java/dev/luxloader/api/scene/SceneGeometryFeed.java), [CompiledSceneMesh](luxloader-api/src/main/java/dev/luxloader/api/scene/CompiledSceneMesh.java) and [DynamicSceneMesh](luxloader-api/src/main/java/dev/luxloader/api/scene/DynamicSceneMesh.java): scene ingestion.
- [RenderDriverImpl](luxloader-core/src/main/java/dev/luxloader/core/runtime/RenderDriverImpl.java): actual loader orchestration when investigating lifecycle behavior.

For worked implementations, open the README and build files of the sibling raster, RT and upscale repositories. Their project-specific tasks and algorithms are examples, not mandatory public SDK behavior.
