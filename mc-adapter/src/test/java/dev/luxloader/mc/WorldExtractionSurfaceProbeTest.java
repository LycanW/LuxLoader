package dev.luxloader.mc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates world-to-block extraction signatures against a real client JAR. Cover Minecraft.level as a
 * field, inherited LevelReader methods, bridge-method return types and loaded-chunk checks. Verify
 * names, parameters, return types, modifiers and public method-handle access without launching
 * Minecraft. Local investigation notes live in docs/world-surface.md.
 */
class WorldExtractionSurfaceProbeTest {

    /** Three 26.3 marker classes; absence indicates a different client version. */
    private static final List<String> TWENTY_SIX_THREE_MARKERS = List.of(
            "net.minecraft.client.renderer.state.level.CameraRenderState",
            "net.minecraft.client.renderer.extract.LevelExtractor",
            "net.minecraft.world.level.chunk.status.ChunkStatus");

    private static URLClassLoader loader;

    // Test infrastructure.

    @BeforeAll
    static void openRealClientJar() throws Exception {
        Path jar = MinecraftClientJar.resolve();
        if (jar == null) {
            fail("No Minecraft 26.3 client JAR found to verify world extraction. "
                    + "This contract test requires the real client rather than silently skipping"
                    + " so incompatible mappings are caught before release. "
                    + "Check for a 26.3 version under %APPDATA%/.minecraft/versions/, "
                    + "or set luxloader.test.minecraftJar or LUXLOADER_MINECRAFT_JAR.");
        }
        loader = MinecraftClientJar.classLoaderFor(jar);

        List<String> missing = new ArrayList<>();
        for (String marker : TWENTY_SIX_THREE_MARKERS) {
            try {
                Class.forName(marker, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                missing.add(marker);
            }
        }
        if (!missing.isEmpty()) {
            fail("This is not a 26.3 client JAR; missing " + missing + "; actual JAR: " + jar
                    + ". Stop before running assertions against an incompatible client.");
        }
        System.out.println("[Real JAR] " + jar);
    }

    @AfterAll
    static void closeRealClientJar() throws Exception {
        if (loader != null) {
            loader.close();
        }
    }

    // Reflection helpers.

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            fail("Class " + name + " cannot load from the real client: " + e
                    + ". The access chain cannot continue past this hop.");
            return null;
        }
    }

    private static Method[] safeMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (LinkageError e) {
            return new Method[0];
        }
    }

    /** Include available overloads in failures to avoid a separate javap lookup. */
    private static String overloads(Class<?> owner, String name) {
        List<String> found = new ArrayList<>();
        for (Method m : safeMethods(owner)) {
            if (m.getName().equals(name)) {
                found.add(m.getParameterCount() + " 参 → " + m.getReturnType().getSimpleName()
                        + (m.isBridge() ? " [bridge]" : ""));
            }
        }
        return found.isEmpty() ? " (none declared here)" : found.toString();
    }

    /**
     * Find inherited public methods, including interface defaults, with getMethod. LevelReader declares
     * the three-argument getChunk overload; Level itself does not.
     */
    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail(owner.getName() + "#" + name + params(params) + " does not exist in the real client. "
                    + "Overloads with the same name: " + overloads(owner, name));
            return null;
        }
    }

    /**
     * Find the unique declared non-bridge method matching both parameters and return type. Covariant
     * returns can produce a real method and a compiler bridge with identical parameters.
     */
    private static Method declaredMethod(Class<?> owner, String name, Class<?> returnType,
                                         Class<?>... params) {
        List<Method> matches = new ArrayList<>();
        for (Method m : safeMethods(owner)) {
            if (m.isBridge() || m.isSynthetic()) {
                continue;
            }
            if (m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), params)) {
                matches.add(m);
            }
        }
        if (matches.isEmpty()) {
            fail(owner.getName() + "#" + name + params(params) + " has no non-bridge declaration. Available overloads: "
                    + overloads(owner, name));
            return null;
        }
        assertEquals(1, matches.size(),
                owner.getName() + "#" + name + params(params) + " has multiple non-bridge declarations: " + matches
                        + "; review the mapping to resolve this ambiguity");
        Method m = matches.get(0);
        assertEquals(returnType, m.getReturnType(),
                owner.getName() + "#" + name + params(params) + " changed return type (now "
                        + m.getReturnType().getName() + "). Return types determine required casts; "
                        + "mismatches can fail or select an incorrect object at runtime");
        return m;
    }

    /** Count bridge methods sharing the parameter list to detect changes in covariant-return inheritance. */
    private static int bridgeCount(Class<?> owner, String name, Class<?>... params) {
        int count = 0;
        for (Method m : safeMethods(owner)) {
            if (m.isBridge() && m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), params)) {
                count++;
            }
        }
        return count;
    }

    private static String params(Class<?>... params) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static Method publicInstanceMethod(Class<?> owner, String name, Class<?> returnType,
                                               String symptom, Class<?>... params) {
        Method m = method(owner, name, params);
        String where = owner.getSimpleName() + "#" + name + params(params);
        assertTrue(Modifier.isPublic(m.getModifiers()),
                where + " is no longer public. " + symptom);
        assertFalse(Modifier.isStatic(m.getModifiers()),
                where + " became static and no longer supports instance-state access. " + symptom);
        assertEquals(returnType, m.getReturnType(),
                where + " changed return type (now " + m.getReturnType().getName()
                        + "; expected " + returnType.getName() + "）。" + symptom);
        return m;
    }

    private static Method publicStaticMethod(Class<?> owner, String name, Class<?> returnType,
                                             String symptom, Class<?>... params) {
        Method m = method(owner, name, params);
        String where = owner.getSimpleName() + "#" + name + params(params);
        assertTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()),
                where + " is no longer public static. " + symptom);
        assertEquals(returnType, m.getReturnType(),
                where + " changed return type (now " + m.getReturnType().getName() + "）。" + symptom);
        return m;
    }

    private static Field publicInstanceField(Class<?> owner, String name, Class<?> type, String symptom) {
        Field f;
        try {
            f = owner.getField(name);
        } catch (NoSuchFieldException e) {
            fail(owner.getName() + "#" + name + " field is missing; it may have become a getter, "
                    + "so review whether the mapping should access a field or method." + symptom);
            return null;
        }
        String where = owner.getSimpleName() + "#" + name;
        assertTrue(Modifier.isPublic(f.getModifiers()), where + " is no longer a public field. " + symptom);
        assertFalse(Modifier.isStatic(f.getModifiers()), where + " became static. " + symptom);
        assertEquals(type, f.getType(),
                where + " changed type (now " + f.getType().getName() + "）。" + symptom);
        return f;
    }

    /** Verify public callers can actually bind the method, beyond reflective visibility. */
    private static void publiclyBindable(Method m, String symptom) {
        try {
            MethodHandles.publicLookup().unreflect(m);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(m + " exists but publicLookup cannot bind it: " + e
                    + ". Visibility, module access or a non-public declaring class prevents invocation." + symptom);
        }
    }

    private static void publiclyBindable(Field f, String symptom) {
        try {
            MethodHandles.publicLookup().unreflectGetter(f);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(f + " exists but publicLookup cannot bind its getter: " + e + "。" + symptom);
        }
    }

    /** Each hop's result must be assignable to the next receiver type. */
    private static void receiverAccepts(String step, Class<?> produced, Class<?> receiver) {
        assertTrue(receiver.isAssignableFrom(produced),
                step + ": broken chain; the previous hop produces " + produced.getName()
                        + " while the next receiver requires " + receiver.getName() + ", with no compatible subtype relationship");
    }

    /**
     * After erasure, get() returns Object; inspect the declared generic argument to verify the contained
     * element type.
     */
    private static void genericArgumentIs(Method m, Class<?> expected, String symptom) {
        Type generic = m.getGenericReturnType();
        if (!(generic instanceof ParameterizedType parameterized)) {
            fail(m + " no longer has generic return arguments (now " + generic + "），"
                    + "The element type cannot be verified statically." + symptom);
            return;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        assertEquals(1, arguments.length, m + " changed its generic argument count: " + Arrays.toString(arguments));
        assertEquals(expected, arguments[0],
                m + " changed its generic argument type (now " + arguments[0] + "; expected " + expected.getName() + "）。"
                        + symptom);
    }

    // 1. Entry points.

    @Test
    @DisplayName("Minecraft Entry Point Is AField Not AGetter")
    void minecraftEntryPointIsAFieldNotAGetter() {
        Class<?> minecraft = cls("net.minecraft.client.Minecraft");
        Class<?> clientLevel = cls("net.minecraft.client.multiplayer.ClientLevel");
        Class<?> localPlayer = cls("net.minecraft.client.player.LocalPlayer");

        Method getInstance = publicStaticMethod(minecraft, "getInstance", minecraft,
                "The singleton entry point is required to access game objects.");
        publiclyBindable(getInstance, "Client instance lookup must be publicly callable.");

        // In 26.3, level and player are public fields rather than getters.
        Field level = publicInstanceField(minecraft, "level", clientLevel,
                "ClientLevel is the root of world extraction.");
        publiclyBindable(level, "World access is unavailable.");

        Field player = publicInstanceField(minecraft, "player", localPlayer,
                "LocalPlayer is required to center the extraction radius.");
        publiclyBindable(player, "Player position is unavailable.");

        // Public final rendering fields used to align extraction with render frames.
        publicInstanceField(minecraft, "levelExtractor",
                cls("net.minecraft.client.renderer.extract.LevelExtractor"),
                "Minecraft 26.3 uses LevelExtractor for render-state extraction; "
                        + "it does not generate voxel meshes.");
        publicInstanceField(minecraft, "levelRenderer",
                cls("net.minecraft.client.renderer.LevelRenderer"),
                "LevelRenderer is required to read visible sections for incremental extraction.");
        publicInstanceField(minecraft, "gameRenderer",
                cls("net.minecraft.client.renderer.GameRenderer"),
                "GameRenderer is required to obtain camera render state.");
    }

    // 2. Chunk access.

    @Test
    @DisplayName("Chunk Lookup Signatures")
    void chunkLookupSignatures() {
        Class<?> level = cls("net.minecraft.world.level.Level");
        Class<?> clientLevel = cls("net.minecraft.client.multiplayer.ClientLevel");
        Class<?> clientChunkCache = cls("net.minecraft.client.multiplayer.ClientChunkCache");
        Class<?> chunkSource = cls("net.minecraft.world.level.chunk.ChunkSource");
        Class<?> levelReader = cls("net.minecraft.world.level.LevelReader");
        Class<?> levelChunk = cls("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> chunkAccess = cls("net.minecraft.world.level.chunk.ChunkAccess");
        Class<?> chunkStatus = cls("net.minecraft.world.level.chunk.status.ChunkStatus");
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");

        // Three Level access forms.
        Method getChunk2 = declaredMethod(level, "getChunk", levelChunk, int.class, int.class);
        assertTrue(Modifier.isPublic(getChunk2.getModifiers()) && !Modifier.isStatic(getChunk2.getModifiers()),
                "Level.getChunk(int,int) must remain a public instance method; "
                        + "otherwise extraction must use the four-argument overload with explicit ChunkStatus.FULL");
        publiclyBindable(getChunk2, "Level.getChunk(int,int) is not publicly callable.");
        assertEquals(1, bridgeCount(level, "getChunk", int.class, int.class),
                "The Level.getChunk(int,int) bridge returning ChunkAccess is missing. "
                        + "A missing covariant-return bridge indicates changed inheritance; "
                        + "recheck mappings resolved by name and arity. Declared overloads: "
                        + overloads(level, "getChunk"));

        Method getChunk4 = publicInstanceMethod(level, "getChunk", chunkAccess,
                "The four-argument overload requests FULL chunks without generating missing chunks.",
                int.class, int.class, chunkStatus, boolean.class);
        assertEquals(level, getChunk4.getDeclaringClass(),
                "Level.getChunk(int,int,ChunkStatus,boolean) changed declaring class (now "
                        + getChunk4.getDeclaringClass().getName() + "）。");
        publiclyBindable(getChunk4, "The four-argument overload must be publicly callable for safe loaded-chunk access.");

        // The three-argument overload is a LevelReader default method, not declared on Level.
        Method getChunk3 = publicInstanceMethod(levelReader, "getChunk", chunkAccess,
                "The three-argument LevelReader default underlies Level.getChunk(int,int).",
                int.class, int.class, chunkStatus);
        assertTrue(getChunk3.isDefault(),
                "LevelReader.getChunk(int,int,ChunkStatus) is no longer a default method. "
                        + "In 26.3 it is an interface default, absent from Level.getDeclaredMethod; "
                        + "declared-only lookup would incorrectly report a removed overload.");
        assertTrue(Modifier.isPublic(getChunk3.getModifiers()), "Interface default methods must be public");
        publiclyBindable(getChunk3, "The three-argument LevelReader default is not publicly callable.");
        assertFalse(Arrays.stream(safeMethods(level))
                        .anyMatch(m -> !m.isBridge() && m.getName().equals("getChunk")
                                && m.getParameterCount() == 3),
                "Level now declares the three-argument getChunk overload. "
                        + "Recheck the mapping evidence in docs/world-surface.md.");

        // BlockPos access used by extraction.
        Method getChunkAt = publicInstanceMethod(level, "getChunkAt", levelChunk,
                "Without BlockPos chunk lookup, callers must convert block coordinates explicitly.",
                blockPos);
        publiclyBindable(getChunkAt, "Level.getChunkAt(BlockPos) is not publicly callable.");

        // --- ClientLevel → ClientChunkCache ---
        Method getChunkSource = declaredMethod(clientLevel, "getChunkSource", clientChunkCache);
        assertTrue(Modifier.isPublic(getChunkSource.getModifiers()),
                "ClientLevel.getChunkSource() must be public.");
        publiclyBindable(getChunkSource, "World-to-chunk-cache access is unavailable.");
        assertEquals(1, bridgeCount(clientLevel, "getChunkSource"),
                "The ClientLevel.getChunkSource bridge returning ChunkSource is missing; inheritance changed.");

        // ClientChunkCache access returns LevelChunk, not ChunkAccess.
        Method cacheGetChunk = declaredMethod(clientChunkCache, "getChunk", levelChunk,
                int.class, int.class, chunkStatus, boolean.class);
        assertTrue(Modifier.isPublic(cacheGetChunk.getModifiers())
                        && !Modifier.isStatic(cacheGetChunk.getModifiers()),
                "ClientChunkCache.getChunk(int,int,ChunkStatus,boolean) must be a public instance method.");
        publiclyBindable(cacheGetChunk, "Client chunk cache lookup is unavailable.");
        assertEquals(1, bridgeCount(clientChunkCache, "getChunk", int.class, int.class, chunkStatus, boolean.class),
                "The ClientChunkCache.getChunk bridge is missing; covariant returns changed.");

        // With loadOrGenerate=false, unloaded chunks return null; true permits an empty placeholder. This test verifies signatures, while bytecode establishes runtime semantics.
        Method chunkSourceGetChunk = publicInstanceMethod(chunkSource, "getChunk", levelChunk,
                "The status-free overload provides loaded-only access without generation.",
                int.class, int.class, boolean.class);
        publiclyBindable(chunkSourceGetChunk, "ChunkSource.getChunk(int,int,boolean) is not publicly callable.");
        Method getChunkNow = publicInstanceMethod(chunkSource, "getChunkNow", levelChunk,
                "getChunkNow provides immediate access without generation.", int.class, int.class);
        publiclyBindable(getChunkNow, "ChunkSource.getChunkNow(int,int) is not publicly callable.");
        publicInstanceMethod(chunkSource, "hasChunk", boolean.class,
                "Without hasChunk, callers must check isLoaded through block coordinates.", int.class, int.class);
    }

    @Test
    @DisplayName("There Is No Public Loaded Chunk List")
    void thereIsNoPublicLoadedChunkList() {
        Class<?> clientChunkCache = cls("net.minecraft.client.multiplayer.ClientChunkCache");
        Class<?> clientLevel = cls("net.minecraft.client.multiplayer.ClientLevel");
        Class<?> levelChunk = cls("net.minecraft.world.level.chunk.LevelChunk");

        publicInstanceMethod(clientChunkCache, "getLoadedChunksCount", int.class,
                "Loaded-chunk counts must remain available for diagnostics.");

        // Per-tick loaded/unloaded sets are deltas readable after flip, not a complete chunk enumeration.
        for (String name : List.of("addedLoadedChunks", "removedLoadedChunks",
                "addedEmptySections", "removedEmptySections")) {
            Method m = method(clientChunkCache, name);
            assertEquals("it.unimi.dsi.fastutil.longs.LongOpenHashSet", m.getReturnType().getName(),
                    name + "() changed return type (now " + m.getReturnType().getName()
                            + "). These are per-tick deltas, not a complete world enumeration");
        }
        publicInstanceMethod(clientChunkCache, "flipUpdateTrackingSets", void.class,
                "flip is required to obtain a stable delta-set snapshot.");

        // Public render-side chunk set: expected rendered chunks encoded as long ChunkPos values.
        Class<?> levelRenderer = cls("net.minecraft.client.renderer.LevelRenderer");
        Method expectedChunks = publicInstanceMethod(levelRenderer, "expectedChunks",
                it.unimi.dsi.fastutil.longs.LongCollection.class,
                "expectedChunks supplies public chunk coordinates without radius polling.");
        publiclyBindable(expectedChunks, "Expected-render-chunk enumeration is not callable.");

        // If a complete enumeration API is added, this assertion prompts switching to it.
        List<String> bulkAccessors = new ArrayList<>();
        for (Class<?> owner : List.of(clientChunkCache, clientLevel)) {
            for (Method m : owner.getMethods()) {
                if (returnsChunks(m, levelChunk)) {
                    bulkAccessors.add(owner.getSimpleName() + "#" + m.getName() + "()");
                }
            }
        }
        assertTrue(bulkAccessors.isEmpty(),
                "The client now exposes a public method returning LevelChunk collections: " + bulkAccessors
                        + ". Consider replacing radius polling with this enumeration API, "
                        + "then update the contract to require it.");
    }

    /** Whether the return type contains LevelChunk elements as an array or generic argument. */
    private static boolean returnsChunks(Method m, Class<?> levelChunk) {
        Class<?> raw = m.getReturnType();
        if (raw.isArray() && raw.getComponentType() == levelChunk) {
            return true;
        }
        Type generic = m.getGenericReturnType();
        if (generic instanceof ParameterizedType parameterized) {
            for (Type arg : parameterized.getActualTypeArguments()) {
                if (arg == levelChunk) {
                    return true;
                }
            }
        }
        return false;
    }

    // 3. Chunks and sections.

    @Test
    @DisplayName("Chunk To Sections")
    void chunkToSections() {
        Class<?> chunkAccess = cls("net.minecraft.world.level.chunk.ChunkAccess");
        Class<?> levelChunk = cls("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> section = cls("net.minecraft.world.level.chunk.LevelChunkSection");
        Class<?> chunkPos = cls("net.minecraft.world.level.ChunkPos");
        Class<?> heightAccessor = cls("net.minecraft.world.level.LevelHeightAccessor");

        Method getSections = publicInstanceMethod(chunkAccess, "getSections", section.arrayType(),
                "Extraction requires access to the chunk's section array.");
        assertEquals(chunkAccess, getSections.getDeclaringClass(),
                "getSections() changed declaring class (now " + getSections.getDeclaringClass().getName() + "）");
        publiclyBindable(getSections, "The section array is not publicly accessible.");

        Method getSection = publicInstanceMethod(chunkAccess, "getSection", section,
                "Indexed section lookup avoids traversing the complete array.", int.class);
        publiclyBindable(getSection, "ChunkAccess.getSection(int) is not publicly callable.");

        publicInstanceMethod(chunkAccess, "getPos", chunkPos,
                "Chunk coordinates are required for local-to-world conversion.");
        publicInstanceMethod(levelChunk, "getLevel", cls("net.minecraft.world.level.Level"),
                "The owning world provides dimension height bounds.");

        // LevelChunk inherits getSections from ChunkAccess rather than redeclaring it.
        assertTrue(Arrays.stream(safeMethods(levelChunk)).noneMatch(m -> m.getName().equals("getSections")),
                "LevelChunk now declares getSections(); recheck inheritance.");

        // LevelChunkSection exposes no Y coordinate; derive world position from the array index and the dimension's minimum section Y.
        List<String> positionAccessors = new ArrayList<>();
        for (Method m : safeMethods(section)) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            if (m.getParameterCount() == 0
                    && (n.contains("sectiony") || n.contains("sectionpos") || n.equals("getpos")
                    || n.equals("gety") || n.equals("origin"))) {
                positionAccessors.add(m.getName() + "()");
            }
        }
        assertTrue(positionAccessors.isEmpty(),
                "LevelChunkSection now exposes coordinates: " + positionAccessors
                        + ". Consider replacing index-plus-minimum-section coordinate inference, "
                        + "then update the contract to require it.");

        // Verify every required coordinate conversion helper exists.
        publicInstanceMethod(heightAccessor, "getMinY", int.class,
                "getMinY defines the block Y origin of section zero.");
        Method minSectionY = method(heightAccessor, "getMinSectionY");
        assertTrue(minSectionY.isDefault() && Modifier.isPublic(minSectionY.getModifiers()),
                "getMinSectionY must remain public default, matching 26.3.");
        assertEquals(int.class, minSectionY.getReturnType());
        publicInstanceMethod(heightAccessor, "getSectionsCount", int.class,
                "Section count defines the traversal bound.");
        for (String name : List.of("getSectionIndexFromSectionY", "getSectionYFromSectionIndex",
                "getSectionIndex")) {
            Method m = method(heightAccessor, name, int.class);
            assertEquals(int.class, m.getReturnType());
            assertTrue(m.isDefault(), name + " is no longer a default method");
        }
    }

    @Test
    @DisplayName("Section To Palette")
    void sectionToPalette() {
        Class<?> section = cls("net.minecraft.world.level.chunk.LevelChunkSection");
        Class<?> container = cls("net.minecraft.world.level.chunk.PalettedContainer");
        Class<?> containerRo = cls("net.minecraft.world.level.chunk.PalettedContainerRO");
        Class<?> blockState = cls("net.minecraft.world.level.block.state.BlockState");
        Class<?> fluidState = cls("net.minecraft.world.level.material.FluidState");
        Class<?> strategy = cls("net.minecraft.world.level.chunk.Strategy");

        Method getStates = publicInstanceMethod(section, "getStates", container,
                "Direct palette access avoids repeated section lookups and exposes palette information.");
        publiclyBindable(getStates, "The section block-state container is not publicly accessible.");

        Method hasOnlyAir = publicInstanceMethod(section, "hasOnlyAir", boolean.class,
                "hasOnlyAir avoids scanning every cell in empty sections.");
        publiclyBindable(hasOnlyAir, "The empty-section fast path is unavailable.");

        publicInstanceMethod(section, "getBlockState", blockState,
                "Section-local block-state lookup is unavailable.", int.class, int.class, int.class);
        publicInstanceMethod(section, "getFluidState", fluidState,
                "Fluid state is required for water-surface geometry.", int.class, int.class, int.class);
        publicInstanceMethod(section, "hasFluid", boolean.class, "hasFluid avoids scanning fluid-free sections.");

        // PalettedContainer.get(int,int,int) is public and uses local coordinates 0..15.
        Method get = publicInstanceMethod(container, "get", Object.class,
                "Palette container access is required to read section blocks.",
                int.class, int.class, int.class);
        assertEquals(Object.class, get.getReturnType(),
                "PalettedContainer.get returns generic T, erased to Object");
        publiclyBindable(get, "PalettedContainer.get(int,int,int) is not publicly callable.");
        assertEquals(containerRo, Arrays.stream(container.getInterfaces())
                        .filter(i -> i.getName().equals(containerRo.getName()))
                        .findFirst().orElse(null),
                "PalettedContainer no longer implements PalettedContainerRO");
        Method roGet = publicInstanceMethod(containerRo, "get", Object.class,
                "The read-only interface must expose get().",
                int.class, int.class, int.class);
        assertTrue(Modifier.isAbstract(roGet.getModifiers()),
                "PalettedContainerRO.get(int,int,int) must be abstract");

        // Strategy defines local-coordinate to storage-index conversion.
        publicInstanceMethod(strategy, "getIndex", int.class,
                "Strategy.getIndex defines local (x,y,z) to container-index conversion; "
                        + "hard-coded packing would fail silently if the layout changes.",
                int.class, int.class, int.class);
    }

    // 4. Block state classification.

    @Test
    @DisplayName("Block State Predicates")
    void blockStatePredicates() {
        Class<?> blockState = cls("net.minecraft.world.level.block.state.BlockState");
        Class<?> base = cls("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
        Class<?> block = cls("net.minecraft.world.level.block.Block");
        Class<?> blockGetter = cls("net.minecraft.world.level.BlockGetter");
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");
        Class<?> mapColor = cls("net.minecraft.world.level.material.MapColor");
        Class<?> renderShape = cls("net.minecraft.world.level.block.RenderShape");
        Class<?> fluidState = cls("net.minecraft.world.level.material.FluidState");
        Class<?> direction = cls("net.minecraft.core.Direction");

        assertEquals(base, blockState.getSuperclass(),
                "BlockState no longer extends BlockStateBase; predicate mappings must be reviewed");

        // Air, solid and opaque predicates.
        record Predicate(String name, String why) {
        }
        List<Predicate> predicates = List.of(
                new Predicate("isAir", "Air must not emit geometry; "
                        + "otherwise mesh counts inflate dramatically"),
                new Predicate("isSolid", "Neighbor occlusion must preserve faces adjacent to transparent geometry; "
                        + "incorrect occlusion creates missing surfaces"),
                new Predicate("isSolidRender", "Full opacity controls face culling and lighting"),
                new Predicate("canOcclude", "Occlusion classification used by palettes and mesh merging"),
                new Predicate("useShapeForLightOcclusion", "Whether the shape participates in occlusion"),
                new Predicate("propagatesSkylightDown", "Light transmission used by top-face selection"));
        for (Predicate p : predicates) {
            Method m = publicInstanceMethod(base, p.name(), boolean.class, p.why());
            assertEquals(base, m.getDeclaringClass(),
                    p.name() + "() is no longer declared on BlockStateBase (now "
                            + m.getDeclaringClass().getName() + "). The mapping candidate is "
                            + "BlockBehaviour$BlockStateBase#" + p.name() + "; update it when the declaring class changes");
            publiclyBindable(m, p.why());
        }

        // Render shape determines whether the block uses model rendering.
        Method getRenderShape = publicInstanceMethod(base, "getRenderShape", renderShape,
                "RenderShape determines whether a block emits model geometry.");
        assertEquals(base, getRenderShape.getDeclaringClass());
        publiclyBindable(getRenderShape, "Render shape must be available to skip invisible blocks.");

        // Resolve a block state's owning Block.
        Method getBlock = publicInstanceMethod(base, "getBlock", block,
                "The owning Block is required for material and color mapping.");
        publiclyBindable(getBlock, "Block-state to Block access is unavailable.");

        // Color access belongs to BlockStateBase and requires BlockGetter and BlockPos.
        Method getMapColor = publicInstanceMethod(base, "getMapColor", mapColor,
                "MapColor provides a fallback block color using world and position, "
                        + "allowing location-dependent colors.",
                blockGetter, blockPos);
        publiclyBindable(getMapColor, "Block color access is unavailable.");
        assertTrue(Arrays.stream(block.getMethods()).noneMatch(m -> m.getName().equals("getMapColor")),
                "Block now exposes getMapColor; review whether this avoids BlockGetter/BlockPos, "
                        + "then update the mapping and assertion.");
        assertTrue(Arrays.stream(block.getMethods()).noneMatch(m -> m.getName().equals("defaultMaterialColor")),
                "Block now exposes defaultMaterialColor; review this alternate entry point "
                        + "before updating the mapping.");
        publicInstanceField(mapColor, "col", int.class,
                "MapColor.col supplies the numeric packed color.");

        // Fluids.
        publicInstanceMethod(base, "getFluidState", fluidState,
                "Fluid-state access is required for separate surface handling.");

        // Use Minecraft's authoritative face-culling API.
        Method shouldRenderFace = publicStaticMethod(block, "shouldRenderFace", boolean.class,
                "Block.shouldRenderFace provides Minecraft's face-culling rules, "
                        + "including non-cube and connected transparent geometry.",
                blockState, blockState, direction);
        publiclyBindable(shouldRenderFace, "Minecraft face-culling rules are not publicly callable.");

        // Full-block collision predicate provides a cheaper solidity approximation than VoxelShape inspection.
        publicInstanceMethod(base, "isCollisionShapeFullBlock", boolean.class,
                "Full-block classification is required for shape-based culling.", blockGetter, blockPos);
        publicInstanceMethod(base, "getLightDampening", int.class,
                "Light attenuation is an input to light propagation.");
    }

    @Test
    @DisplayName("Render Shape Enum Lost AValue")
    void renderShapeEnumLostAValue() {
        Class<?> renderShape = cls("net.minecraft.world.level.block.RenderShape");
        assertTrue(renderShape.isEnum(), "RenderShape is no longer an enum");

        Set<String> names = new TreeSet<>();
        for (Object constant : renderShape.getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }
        assertEquals(Set.of("INVISIBLE", "MODEL"), names,
                "RenderShape values changed (now " + names + "）。"
                        + "In 26.3 the values are INVISIBLE and MODEL, without ENTITYBLOCK_ANIMATED; "
                        + "review switches and mappings when this set changes.");
    }

    // 5. Heights and Y-coordinate conversion.

    @Test
    @DisplayName("Chunk Status Fluid State And Block Pos")
    void chunkStatusFluidStateAndBlockPos() {
        Class<?> chunkStatus = cls("net.minecraft.world.level.chunk.status.ChunkStatus");
        Class<?> chunkType = cls("net.minecraft.world.level.chunk.status.ChunkType");
        Class<?> fluidState = cls("net.minecraft.world.level.material.FluidState");
        Class<?> fluid = cls("net.minecraft.world.level.material.Fluid");
        Class<?> blockGetter = cls("net.minecraft.world.level.BlockGetter");
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");
        Class<?> vec3i = cls("net.minecraft.core.Vec3i");

        // ChunkStatus constants used for explicit chunk requests.
        for (String name : List.of("EMPTY", "STRUCTURE_STARTS", "STRUCTURE_REFERENCES", "BIOMES",
                "TERRAIN", "FEATURES", "INITIALIZE_LIGHT", "LIGHT", "SPAWN", "FULL")) {
            assertFieldConstant(chunkStatus, name, chunkStatus,
                    "ChunkStatus." + name + " is missing; explicit chunk-status requests cannot resolve it, "
                            + "so reflective callers must not silently choose another status");
        }
        publicInstanceMethod(chunkStatus, "getIndex", int.class,
                "Status order determines whether a chunk has reached FULL.");
        publicInstanceMethod(chunkStatus, "getChunkType", chunkType,
                "Chunk type determines whether LevelChunk access is valid.");
        publicStaticMethod(chunkStatus, "getStatusList", List.class,
                "The status list bounds status traversal.");

        // FluidState values used for water-surface geometry.
        publicInstanceMethod(fluidState, "getType", fluid,
                "Fluid type determines material and transparency handling.");
        publicInstanceMethod(fluidState, "isEmpty", boolean.class,
                "isEmpty must detect fluid presence independently of block air state.");
        publicInstanceMethod(fluidState, "isFull", boolean.class,
                "The full-fluid predicate controls full-height surfaces.");
        publicInstanceMethod(fluidState, "getAmount", int.class,
                "Fluid amount contributes to surface height.");
        publicInstanceMethod(fluidState, "getHeight", float.class,
                "Fluid height directly determines surface geometry.", blockGetter, blockPos);
        publicInstanceMethod(fluidState, "createLegacyBlock",
                cls("net.minecraft.world.level.block.state.BlockState"),
                "Legacy block-state conversion aligns fluid handling with the host.");
        // Check constant declarations without reading static fields, which would initialize registries and require game bootstrap. javap ConstantValue output records AMOUNT_MAX=9 and AMOUNT_FULL=8.
        assertFieldConstant(fluidState, "AMOUNT_MAX", int.class,
                "The 26.3 AMOUNT_MAX constant is nine, "
                        + "distinct from AMOUNT_FULL");
        assertFieldConstant(fluidState, "AMOUNT_FULL", int.class,
                "The 26.3 AMOUNT_FULL constant is eight."
                        + "The relationship between getAmount() bounds and these constants remains unverified; see local investigation notes.");

        // BlockPos packing underlies bulk traversal and set keys.
        publicStaticMethod(blockPos, "asLong", long.class,
                "Packed long coordinates are standard chunk-set keys.",
                int.class, int.class, int.class);
        publicInstanceMethod(blockPos, "asLong", long.class, "Instance coordinate packing.");
        for (String name : List.of("getX", "getY", "getZ")) {
            Method m = publicStaticMethod(blockPos, name, int.class,
                    name + "Packed-coordinate decoding avoids duplicating bit shifts.", long.class);
            assertTrue(Modifier.isStatic(m.getModifiers()));
        }
        assertFieldConstant(blockPos, "ZERO", blockPos, "BlockPos.ZERO is the constant origin.");
        assertEquals(vec3i, blockPos.getSuperclass(),
                "BlockPos no longer extends Vec3i; coordinate getter mappings changed");
        assertEquals(int.class, method(vec3i, "getX").getReturnType(),
                "Vec3i.getX() changed return type");
    }

    /** Verify a public static final constant's declared type. */
    private static void assertFieldConstant(Class<?> owner, String name, Class<?> type, String symptom) {
        try {
            java.lang.reflect.Field f = owner.getField(name);
            assertEquals(type, f.getType(), owner.getSimpleName() + "#" + name + " changed type." + symptom);
            assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()),
                    owner.getSimpleName() + "#" + name + " is no longer a static final constant." + symptom);
        } catch (NoSuchFieldException e) {
            fail(owner.getName() + "#" + name + " constant is missing." + symptom);
        }
    }

    @Test
    @DisplayName("Height And Section YMath")
    void heightAndSectionYMath() {
        Class<?> heightAccessor = cls("net.minecraft.world.level.LevelHeightAccessor");
        Class<?> blockGetter = cls("net.minecraft.world.level.BlockGetter");
        Class<?> sectionPos = cls("net.minecraft.core.SectionPos");

        assertTrue(heightAccessor.isAssignableFrom(cls("net.minecraft.world.level.Level")),
                "Level no longer implements LevelHeightAccessor; review height access");
        assertTrue(heightAccessor.isAssignableFrom(cls("net.minecraft.client.multiplayer.ClientLevel")),
                "ClientLevel no longer implements LevelHeightAccessor");
        assertTrue(heightAccessor.isAssignableFrom(cls("net.minecraft.world.level.chunk.ChunkAccess")),
                "ChunkAccess no longer implements LevelHeightAccessor; "
                        + "height access may need to go through the world");
        assertTrue(heightAccessor.isAssignableFrom(blockGetter),
                "BlockGetter no longer extends LevelHeightAccessor");

        assertEquals(int.class, method(heightAccessor, "getMinY").getReturnType());
        assertEquals(int.class, method(heightAccessor, "getHeight").getReturnType());
        assertTrue(Modifier.isAbstract(method(heightAccessor, "getMinY").getModifiers()),
                "getMinY must remain abstract");

        // The three 26.3 conversion methods are defaults and provide inverse coordinate mappings.
        Method indexFromY = method(heightAccessor, "getSectionIndexFromSectionY", int.class);
        Method yFromIndex = method(heightAccessor, "getSectionYFromSectionIndex", int.class);
        Method indexFromBlockY = method(heightAccessor, "getSectionIndex", int.class);
        assertTrue(indexFromY.isDefault() && yFromIndex.isDefault() && indexFromBlockY.isDefault(),
                "Section-index conversion is no longer a default method");

        // SectionPos static conversions for chunk, section and local coordinates.
        publicStaticMethod(sectionPos, "blockToSectionCoord", int.class,
                "Block-to-section conversion must handle negative coordinates correctly.", int.class);
        publicStaticMethod(sectionPos, "sectionToBlockCoord", int.class,
                "Section-to-block conversion determines a section's minimum world coordinate.", int.class);
        publicStaticMethod(sectionPos, "sectionRelative", int.class,
                "World-to-local conversion must return 0..15 for negative coordinates too.", int.class);
    }

    // 6. Player and camera.

    @Test
    @DisplayName("Entity Position And Rotation")
    void entityPositionAndRotation() {
        Class<?> entity = cls("net.minecraft.world.entity.Entity");
        Class<?> localPlayer = cls("net.minecraft.client.player.LocalPlayer");
        Class<?> vec3 = cls("net.minecraft.world.phys.Vec3");
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");
        Class<?> level = cls("net.minecraft.world.level.Level");

        assertEquals(cls("net.minecraft.client.player.AbstractClientPlayer"), localPlayer.getSuperclass(),
                "LocalPlayer inheritance changed; review Minecraft.player type handling");

        for (String name : List.of("getX", "getY", "getZ")) {
            Method m = publicInstanceMethod(entity, name, double.class,
                    name + "() is required for player-centered extraction.");
            assertTrue(Modifier.isFinal(m.getModifiers()),
                    name + "() is no longer final; review overridden position semantics");
            publiclyBindable(m, name + "() is not publicly callable.");
        }
        assertEquals(double.class, method(entity, "getEyeY").getReturnType(),
                "getEyeY() changed return type");

        Method eye = publicInstanceMethod(entity, "getEyePosition", vec3,
                "Eye position provides the default camera location.");
        assertTrue(Modifier.isFinal(eye.getModifiers()), "getEyePosition() must remain final");
        publiclyBindable(eye, "getEyePosition() is not publicly callable.");

        for (String name : List.of("getXRot", "getYRot")) {
            Method m = publicInstanceMethod(entity, name, float.class,
                    name + "() supplies camera orientation for frustum culling.");
            assertFalse(Modifier.isStatic(m.getModifiers()));
            publiclyBindable(m, name + "() is not publicly callable.");
        }

        publicInstanceMethod(entity, "position", vec3, "position() supplies a Vec3 position.");
        publicInstanceMethod(entity, "blockPosition", blockPos, "blockPosition() supplies block coordinates directly.");
        publicInstanceMethod(entity, "level", level, "The entity's owning world supports extraction consistency checks.");
        publicInstanceMethod(entity, "getViewVector", vec3, "View direction vector.", float.class);
    }

    @Test
    @DisplayName("Camera Render State And Camera")
    void cameraRenderStateAndCamera() {
        Class<?> state = cls("net.minecraft.client.renderer.state.level.CameraRenderState");
        Class<?> vec3 = cls("net.minecraft.world.phys.Vec3");
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");
        // Resolve matrix and quaternion classes through the client loader rather than assuming JOML is on the adapter test classpath.
        Class<?> matrix4f = cls("org.joml.Matrix4f");
        Class<?> quaternionf = cls("org.joml.Quaternionf");
        Class<?> frustum = cls("net.minecraft.client.renderer.culling.Frustum");
        Class<?> fogType = cls("net.minecraft.world.level.material.FogType");
        Class<?> fogData = cls("net.minecraft.client.renderer.fog.FogData");

        // Extractor camera data: position, rotation, projection, view rotation and far plane.
        record Slot(String name, Class<?> type, String why) {
        }
        List<Slot> slots = List.of(
                new Slot("blockPos", blockPos, "Camera block position for initial chunk selection"),
                new Slot("pos", vec3, "Camera position for frustum and radius culling"),
                new Slot("xRot", float.class, "Camera pitch"),
                new Slot("yRot", float.class, "Camera yaw"),
                new Slot("orientation", quaternionf, "Orientation quaternion for transforms"),
                new Slot("projectionMatrix", matrix4f, "Projection matrix for frustum-plane extraction"),
                new Slot("viewRotationMatrix", matrix4f, "View rotation matrix"),
                new Slot("cullFrustum", frustum, "Host-computed frustum for section culling"),
                new Slot("depthFar", float.class, "Far-plane distance"),
                new Slot("fogType", fogType, "Fog medium for underwater and lava handling"),
                new Slot("fogData", fogData, "Fog parameters for matching host shading"),
                new Slot("entityRenderState",
                        cls("net.minecraft.client.renderer.state.level.CameraEntityRenderState"),
                        "Render state of the camera entity"),
                new Slot("cameraEntityPartialTicks", float.class, "Partial tick for interpolation"),
                new Slot("initialized", boolean.class, "Initialization state before reading camera fields"),
                new Slot("isFirstPerson", boolean.class, "First-person state for self-model visibility"),
                new Slot("smartCull", boolean.class, "Smart culling toggle"),
                new Slot("isFrustumCaptured", boolean.class, "Frustum capture state"),
                new Slot("isPanoramicMode", boolean.class, "Panorama mode"),
                new Slot("hudFov", float.class, "HUD field of view"));
        for (Slot slot : slots) {
            Field f;
            try {
                f = state.getField(slot.name());
            } catch (NoSuchFieldException e) {
                fail("CameraRenderState is missing field " + slot.name() + ". Available fields: "
                        + Arrays.toString(Arrays.stream(state.getFields())
                        .map(Field::getName).sorted().toArray())
                        + ". This field supplies " + slot.why() + "; review equivalent Camera access if it is removed");
                return;
            }
            assertEquals(slot.type, f.getType(),
                    "CameraRenderState." + slot.name() + " changed type (now "
                            + f.getType().getName() + "; expected " + slot.type.getName()
                            + "）。" + slot.why());
            assertTrue(Modifier.isPublic(f.getModifiers()),
                    "CameraRenderState." + slot.name() + " is no longer a public field; "
                            + "review mappings if the 26.3 data carrier changes to getters");
            publiclyBindable(f, "Cannot read " + slot.name() + "，" + slot.why());
        }

        // Camera's 26.3 surface consists of tick/update/extractRenderState and a few getters.
        Class<?> camera = cls("net.minecraft.client.Camera");
        Class<?> deltaTracker = cls("net.minecraft.client.DeltaTracker");
        publicInstanceMethod(camera, "extractRenderState", void.class,
                "extractRenderState exports the camera's render data.",
                state, deltaTracker);
        publicInstanceMethod(camera, "position", vec3, "Camera.position() provides direct floating-point position access.");
        publicInstanceMethod(camera, "blockPosition", blockPos, "Camera.blockPosition() provides direct block-coordinate access.");
        for (String name : List.of("xRot", "yRot", "yaw")) {
            assertEquals(float.class, method(camera, name).getReturnType(),
                    "Camera." + name + "() changed return type");
        }
        publicInstanceMethod(camera, "rotation", quaternionf, "Orientation quaternion.");
        publicInstanceMethod(camera, "entity", cls("net.minecraft.world.entity.Entity"),
                "Camera entity, normally the player in first-person mode.");
        publicInstanceMethod(camera, "isInitialized", boolean.class,
                "Check initialization before using a default zero camera position.");
        publicInstanceMethod(camera, "forwardVector", cls("org.joml.Vector3fc"), "Forward vector.");

        // Verify removed legacy camera APIs remain absent; their data moved to CameraRenderState.
        assertTrue(Arrays.stream(safeMethods(camera)).noneMatch(m -> m.getName().equals("getPosition")),
                "Camera now exposes getPosition(); review direct camera position access, "
                        + "and update mappings plus docs/world-surface.md.");
        assertTrue(Arrays.stream(safeMethods(camera)).noneMatch(m -> m.getName().equals("getLookVector")),
                "Camera now exposes getLookVector(); review the mapping.");
    }

    // 7. Render-side entry points.

    @Test
    @DisplayName("Render Side Bulk Sources")
    void renderSideBulkSources() {
        Class<?> levelRenderer = cls("net.minecraft.client.renderer.LevelRenderer");
        Class<?> viewArea = cls("net.minecraft.client.renderer.ViewArea");
        Class<?> renderSection = cls("net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection");
        Class<?> levelExtractor = cls("net.minecraft.client.renderer.extract.LevelExtractor");
        Class<?> clientLevel = cls("net.minecraft.client.multiplayer.ClientLevel");
        Class<?> camera = cls("net.minecraft.client.Camera");
        Class<?> deltaTracker = cls("net.minecraft.client.DeltaTracker");

        publicInstanceMethod(levelRenderer, "viewArea", viewArea,
                "viewArea identifies sections covered by the current view.");
        publicInstanceMethod(levelRenderer, "sectionRenderDispatcher",
                cls("net.minecraft.client.renderer.chunk.SectionRenderDispatcher"),
                "The section dispatcher owns compiled geometry.");
        Method visibleSections = publicInstanceMethod(levelRenderer, "visibleSections",
                it.unimi.dsi.fastutil.objects.ObjectArrayList.class,
                "visibleSections lists the sections selected for this frame, "
                        + "providing an input for incremental extraction.");
        publiclyBindable(visibleSections, "The visible-section list is unavailable for incremental extraction.");

        // ViewArea provides section-grid coordinates.
        for (String name : List.of("size", "minY", "maxY", "minSectionY", "maxSectionY",
                "sectionCount", "getViewDistance")) {
            assertEquals(int.class, method(viewArea, name).getReturnType(),
                    "ViewArea." + name + "() changed return type");
        }
        publicInstanceMethod(viewArea, "getCameraSectionPos", cls("net.minecraft.core.SectionPos"),
                "Camera section coordinates support relocation and frustum traversal.");
        publiclyBindable(method(viewArea, "getRenderSectionAt", cls("net.minecraft.core.BlockPos")),
                "Coordinate-based render-section lookup is not publicly callable.");

        // RenderSection represents a rendered section.
        assertNotNull(renderSection, "SectionRenderDispatcher$RenderSection must exist");
        // Section coordinates are packed long SectionPos values; render origins use block coordinates.
        assertEquals(long.class, method(renderSection, "getSectionNode").getReturnType(),
                "RenderSection.getSectionNode() must return long, a packed SectionPos"
                        + " decoded with SectionPos.x/y/z; review coordinate conversion if its type changes");
        publicInstanceMethod(renderSection, "getRenderOrigin", cls("net.minecraft.core.BlockPos"),
                "The render origin returns BlockPos at the section's minimum corner; "
                        + "the internal renderOrigin field separately uses MutableBlockPos.");
        publicInstanceMethod(renderSection, "getSectionMesh",
                cls("net.minecraft.client.renderer.chunk.SectionMesh"),
                "Access compiled section geometry for the host-mesh reuse path.");
        publicInstanceMethod(renderSection, "getNeighborSectionNode", long.class,
                "Packed neighboring section nodes support culling and visibility.",
                cls("net.minecraft.core.Direction"));
        publicInstanceField(renderSection, "index", int.class,
                "RenderSection.index is a dispatcher ring index, not section Y; "
                        + "do not use it as a world coordinate");

        // LevelExtractor extracts render state rather than generating voxel meshes.
        publicInstanceMethod(levelExtractor, "extract", void.class,
                "LevelExtractor.extract is the per-frame render-state entry point, "
                        + "which does not directly produce triangles.",
                deltaTracker, camera, float.class);
        publicInstanceMethod(levelExtractor, "setLevel", void.class,
                "Notify extraction when the world changes to avoid stale-world access.", clientLevel);
        publicInstanceMethod(levelExtractor, "countRenderedSections", int.class,
                "Rendered-section count for diagnostics.");
    }

    // 8. Mapping wiring.

    @Test
    @DisplayName("Scene Hookpoints Resolve On Real Client")
    void sceneHookpointsResolveOnRealClient() {
        MinecraftMapping mapping = new MinecraftMapping();

        List<MinecraftMapping.Hookpoint> scene = List.of(
                MinecraftMapping.Hookpoint.CLIENT_LEVEL,
                MinecraftMapping.Hookpoint.LEVEL_GET_BLOCK_STATE,
                MinecraftMapping.Hookpoint.LEVEL_IS_LOADED,
                MinecraftMapping.Hookpoint.BLOCK_POS,
                MinecraftMapping.Hookpoint.BLOCK_STATE_IS_AIR,
                MinecraftMapping.Hookpoint.BLOCK_STATE_IS_SOLID,
                MinecraftMapping.Hookpoint.CAMERA_RENDER_STATE);

        List<String> unresolved = new ArrayList<>();
        for (MinecraftMapping.Hookpoint hookpoint : scene) {
            var resolved = mapping.resolve(hookpoint, loader);
            if (resolved.isEmpty()) {
                unresolved.add(hookpoint.name());
                continue;
            }
            MinecraftMapping.ResolvedHook hook = resolved.get();
            assertTrue(hook.isBindablePresent(),
                    hookpoint + " resolved to " + hook.target() + " but its member is missing; "
                            + "the mapping is partially resolved: "
                            + "capability probing must reject it before runtime world access");
        }
        assertTrue(unresolved.isEmpty(),
                "Unresolved scene extraction mappings: " + unresolved + "; failure details: " + mapping.failures());

        // CLIENT_LEVEL binds the public Minecraft.level field.
        MinecraftMapping.ResolvedHook levelHook =
                mapping.resolve(MinecraftMapping.Hookpoint.CLIENT_LEVEL, loader).orElseThrow();
        assertTrue(levelHook.isFieldPresent(),
                "CLIENT_LEVEL must bind the Minecraft.level field");
        assertEquals("net.minecraft.client.multiplayer.ClientLevel",
                levelHook.findField().orElseThrow().getType().getName(),
                "Minecraft.level no longer has type ClientLevel");

        // The two block-state predicates need separate mappings on BlockStateBase.
        for (MinecraftMapping.Hookpoint hookpoint : List.of(
                MinecraftMapping.Hookpoint.BLOCK_STATE_IS_AIR,
                MinecraftMapping.Hookpoint.BLOCK_STATE_IS_SOLID)) {
            MinecraftMapping.ResolvedHook hook = mapping.resolve(hookpoint, loader).orElseThrow();
            Method m = hook.findMethod().orElseThrow(
                    () -> new AssertionError(hookpoint + " is not a method binding: " + hook.target()));
            assertEquals(boolean.class, m.getReturnType(),
                    hookpoint + " targets " + hook.target() + " which no longer returns boolean");
            assertEquals(0, m.getParameterCount(),
                    hookpoint + " now takes arguments; review required world and coordinate inputs");
        }

        // World block access must accept BlockPos and return BlockState.
        MinecraftMapping.ResolvedHook getBlockState =
                mapping.resolve(MinecraftMapping.Hookpoint.LEVEL_GET_BLOCK_STATE, loader).orElseThrow();
        Method m = getBlockState.findMethod().orElseThrow();
        assertEquals(1, m.getParameterCount(), "getBlockState parameter count changed");
        assertEquals("net.minecraft.core.BlockPos", m.getParameterTypes()[0].getName(),
                "getBlockState's first parameter is no longer BlockPos");
        assertEquals("net.minecraft.world.level.block.state.BlockState", m.getReturnType().getName(),
                "getBlockState no longer returns BlockState");

        // isLoaded(BlockPos) → boolean
        MinecraftMapping.ResolvedHook isLoaded =
                mapping.resolve(MinecraftMapping.Hookpoint.LEVEL_IS_LOADED, loader).orElseThrow();
        Method isLoadedMethod = isLoaded.findMethod().orElseThrow();
        assertEquals(boolean.class, isLoadedMethod.getReturnType());
        assertEquals("net.minecraft.core.BlockPos", isLoadedMethod.getParameterTypes()[0].getName(),
                "isLoaded must accept BlockPos for unloaded-chunk detection; "
                        + "otherwise extraction can produce invalid boundary geometry");

        // BlockPos constructor.
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");
        try {
            var ctor = blockPos.getConstructor(int.class, int.class, int.class);
            assertTrue(Modifier.isPublic(ctor.getModifiers()));
        } catch (NoSuchMethodException e) {
            fail("BlockPos must have a public (int,int,int) constructor "
                    + "to construct query coordinates");
        }

        // Print the seven scene mappings for manual inspection.
        StringBuilder resolved = new StringBuilder("[Real JAR] Scene mappings: ");
        for (MinecraftMapping.Hookpoint hookpoint : scene) {
            resolved.append(hookpoint.name()).append("→")
                    .append(mapping.resolve(hookpoint, loader).orElseThrow().target()).append("  ");
        }
        System.out.println(resolved);
    }

    // 9. Complete access chain.

    @Test
    @DisplayName("Whole Extraction Chain Is Publicly Bindable")
    void wholeExtractionChainIsPubliclyBindable() {
        Class<?> minecraft = cls("net.minecraft.client.Minecraft");
        Class<?> clientLevel = cls("net.minecraft.client.multiplayer.ClientLevel");
        Class<?> clientChunkCache = cls("net.minecraft.client.multiplayer.ClientChunkCache");
        Class<?> level = cls("net.minecraft.world.level.Level");
        Class<?> levelChunk = cls("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> chunkAccess = cls("net.minecraft.world.level.chunk.ChunkAccess");
        Class<?> section = cls("net.minecraft.world.level.chunk.LevelChunkSection");
        Class<?> container = cls("net.minecraft.world.level.chunk.PalettedContainer");
        Class<?> blockState = cls("net.minecraft.world.level.block.state.BlockState");
        Class<?> base = cls("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
        Class<?> blockPos = cls("net.minecraft.core.BlockPos");
        Class<?> chunkStatus = cls("net.minecraft.world.level.chunk.status.ChunkStatus");
        Class<?> entity = cls("net.minecraft.world.entity.Entity");
        Class<?> vec3 = cls("net.minecraft.world.phys.Vec3");

        // Hop 1: Minecraft.getInstance() returns Minecraft.
        Method getInstance = publicStaticMethod(minecraft, "getInstance", minecraft,
                "The singleton entry point begins the access chain.");
        publiclyBindable(getInstance, "Client instance access is unavailable.");

        // Hop 2: minecraft.level returns ClientLevel.
        Field levelField = publicInstanceField(minecraft, "level", clientLevel,
                "World access is the root of block extraction.");
        publiclyBindable(levelField, "World access failed.");
        receiverAccepts("Hop 2", getInstance.getReturnType(), levelField.getDeclaringClass());

        // Hop 3: ClientLevel.getChunkSource() returns ClientChunkCache.
        Method getChunkSource = declaredMethod(clientLevel, "getChunkSource", clientChunkCache);
        publiclyBindable(getChunkSource, "World-to-chunk-cache access failed.");
        receiverAccepts("Hop 3", levelField.getType(), getChunkSource.getDeclaringClass());

        // Hop 4: ClientChunkCache.getChunk(cx,cz,FULL,false) returns LevelChunk or null when unloaded.
        Method getChunk = declaredMethod(clientChunkCache, "getChunk", levelChunk,
                int.class, int.class, chunkStatus, boolean.class);
        publiclyBindable(getChunk, "Chunk lookup failed.");
        receiverAccepts("Hop 4", getChunkSource.getReturnType(), getChunk.getDeclaringClass());

        // Hop 5: inherited LevelChunk.getSections() returns LevelChunkSection[].
        Method getSections = method(chunkAccess, "getSections");
        publiclyBindable(getSections, "Chunk-to-section access failed.");
        receiverAccepts("Hop 5", getChunk.getReturnType(), getSections.getDeclaringClass());
        assertEquals(section.arrayType(), getSections.getReturnType(),
                "getSections() must return LevelChunkSection[]");

        // Hop 6: LevelChunkSection.getStates() returns PalettedContainer<BlockState>.
        Method getStates = method(section, "getStates");
        publiclyBindable(getStates, "Section-to-block-state-container access failed.");
        receiverAccepts("Hop 6", getSections.getReturnType().getComponentType(),
                getStates.getDeclaringClass());
        genericArgumentIs(getStates, blockState,
                "The section container must have BlockState as its element type.");

        // Hop 7: PalettedContainer.get(lx,ly,lz) returns erased Object with declared element type BlockState.
        Method get = method(container, "get", int.class, int.class, int.class);
        publiclyBindable(get, "Block-state lookup failed.");
        receiverAccepts("Hop 7", getStates.getReturnType(), get.getDeclaringClass());
        assertEquals(Object.class, get.getReturnType(),
                "PalettedContainer.get erases to Object; callers cast to BlockState");

        // Hop 8: BlockState.isAir(), isSolid() and getRenderShape().
        for (String name : List.of("isAir", "isSolid")) {
            Method m = method(base, name);
            publiclyBindable(m, "Block-state predicate is unavailable: " + name + "()。");
            receiverAccepts("Hop 8", blockState, m.getDeclaringClass());
        }
        Method getRenderShape = method(base, "getRenderShape");
        publiclyBindable(getRenderShape, "Render-shape lookup failed.");
        receiverAccepts("Hop 8", blockState, getRenderShape.getDeclaringClass());

        // Side path A: official face culling.
        Method shouldRenderFace = method(cls("net.minecraft.world.level.block.Block"),
                "shouldRenderFace", blockState, blockState, cls("net.minecraft.core.Direction"));
        publiclyBindable(shouldRenderFace, "Face-culling rules are not publicly callable.");

        // Side path B: unloaded-chunk detection.
        Method isLoaded = method(level, "isLoaded", blockPos);
        publiclyBindable(isLoaded, "Loaded-chunk checks are required to avoid invalid boundary geometry.");
        receiverAccepts("Side path B", method(levelChunk, "getLevel").getReturnType(),
                isLoaded.getDeclaringClass());

        // Side path C: player position and orientation through inherited Entity methods.
        Class<?> localPlayer = cls("net.minecraft.client.player.LocalPlayer");
        receiverAccepts("Side path C", localPlayer, entity);
        for (String name : List.of("getX", "getY", "getZ", "getEyePosition", "getXRot", "getYRot")) {
            Method m = Arrays.stream(entity.getMethods())
                    .filter(candidate -> candidate.getName().equals(name)
                            && candidate.getParameterCount() == 0)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Entity has no " + name + "()"));
            publiclyBindable(m, "Cannot access " + name + "()。");
        }
        assertEquals(vec3, method(entity, "getEyePosition").getReturnType(),
                "getEyePosition() changed return type; review camera coordinate conversion");

        // Require public access throughout; reflective access to private members is fragile across module boundaries.
        for (Method m : List.of(getInstance, getChunkSource, getChunk, getSections, getStates, get)) {
            assertTrue(Modifier.isPublic(m.getModifiers()),
                    m + " is not public; every access hop requires public visibility");
        }

        // Print a readable access chain for manual inspection.
        System.out.println("[Real JAR] Extraction chain: "
                + "Minecraft.getInstance() → .level(" + clientLevel.getSimpleName() + ") → "
                + ".getChunkSource()(" + clientChunkCache.getSimpleName() + ") → "
                + ".getChunk(int,int,ChunkStatus,boolean)(" + levelChunk.getSimpleName() + ") → "
                + ".getSections()(" + section.getSimpleName() + "[]) → "
                + ".getStates()(" + container.getSimpleName() + ") → .get(int,int,int)("
                + blockState.getSimpleName() + ") → isAir()/isSolid()/getRenderShape()");
    }
}
