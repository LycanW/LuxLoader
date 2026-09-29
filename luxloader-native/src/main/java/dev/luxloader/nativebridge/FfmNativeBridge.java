package dev.luxloader.nativebridge;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.nativebridge.NativeBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NativeBridge implemented with JDK FFM. Plugins must calculate C structure padding/offsets from
 * headers and pack bitfields explicitly. Upcall pointers depend on Arena lifetime and callbacks may
 * run on arbitrary native threads; this bridge uses downcalls and leaves allocation callbacks to a C
 * shim. Supplies generic loading, symbol lookup, typed calls and memory access without vendor-specific
 * structure bindings.
 */
public final class FfmNativeBridge implements NativeBridge {

    private static final Map<String, String> LIBRARY_ALIASES = Map.of(
            // Allow names such as nvngx_dlss without platform suffixes.
            "nvngx_dlss", "nvngx_dlss",
            "nvngx_dlssg", "nvngx_dlssg",
            "nvngx_dlssd", "nvngx_dlssd",
            "sl.interposer", "sl.interposer");

    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup systemLookup = linker.defaultLookup();
    private final List<String> loadedLibraries = new ArrayList<>();
    private final List<String> extractedPaths = new ArrayList<>();
    private final Map<String, NativeLibraryImpl> libraries = new ConcurrentHashMap<>();
    private final List<Path> nativeSearchPaths = new ArrayList<>();
    private final boolean available;

    public FfmNativeBridge() {
        this(true);
    }

    /**
     * @param detectAvailability whether construction probes FFM; false forces isAvailable=true for tests
     * requiring native support
     */
    public FfmNativeBridge(boolean detectAvailability) {
        boolean ok = true;
        if (detectAvailability) {
            try {
                // Probe availability by resolving a widely available symbol.
                linker.defaultLookup().find("malloc");
            } catch (Throwable t) {
                ok = false;
            }
        }
        this.available = ok;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String implementationName() {
        return available ? "FFM (java.lang.foreign)" : "none";
    }

    /**
     * Adds a native search directory, including extracted JAR resources. Record the actual loaded path
     * because platform DLL lookup can select a same-named library from another directory.
     */
    public FfmNativeBridge addSearchPath(Path directory) {
        if (directory != null && Files.isDirectory(directory)) {
            nativeSearchPaths.add(directory.toAbsolutePath());
        }
        return this;
    }

    @Override
    public NativeLibrary load(String libraryName) {
        requireAvailable();
        if (libraryName == null || libraryName.isBlank()) {
            throw new NativeLoadException(tr("(empty)"), tr("Library name must not be empty"));
        }

        String resolvedName = LIBRARY_ALIASES.getOrDefault(libraryName, libraryName);
        NativeLibraryImpl cached = libraries.get(resolvedName);
        if (cached != null) {
            return cached;
        }

        // Try an explicitly supplied absolute path first.
        Path direct = Path.of(resolvedName);
        if (Files.isRegularFile(direct)) {
            return loadFromPath(direct);
        }

        // Then search registered directories.
        String platformName = platformFileName(resolvedName);
        for (Path dir : nativeSearchPaths) {
            Path candidate = dir.resolve(platformName);
            if (Files.isRegularFile(candidate)) {
                return loadFromPath(candidate);
            }
        }

        // Finally use system lookup for driver-provided libraries.
        try {
            System.loadLibrary(resolvedName);
            SymbolLookup lookup = SymbolLookup.loaderLookup();
            NativeLibraryImpl impl = new NativeLibraryImpl(resolvedName, Optional.empty(), lookup);
            libraries.put(resolvedName, impl);
            loadedLibraries.add(resolvedName);
            return impl;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            throw new NativeLoadException(resolvedName, explainLoadFailure(resolvedName, e), e);
        }
    }

    private NativeLibraryImpl loadFromPath(Path path) {
        String absolute = path.toAbsolutePath().toString();
        try {
            System.load(absolute);
            SymbolLookup lookup = SymbolLookup.loaderLookup();
            NativeLibraryImpl impl = new NativeLibraryImpl(
                    path.getFileName().toString(), Optional.of(absolute), lookup);
            libraries.put(impl.name(), impl);
            loadedLibraries.add(impl.name());
            return impl;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            throw new NativeLoadException(absolute, explainLoadFailure(absolute, e), e);
        }
    }

    /** Converts native load failures into actionable guidance instead of an uninformative UnsatisfiedLinkError. */
    private String explainLoadFailure(String name, Throwable cause) {
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        StringBuilder sb = new StringBuilder(tr("Failed to load native library: ")).append(name);
        if (message.contains("Can't find dependent libraries")) {
            sb.append(tr(". Cause: a dependency is missing, such as the vendor SDK runtime ")
                    + tr("(for example MSVC) or a companion DLL. ")
                    + tr("Ensure all required dependencies are installed beside the library."));
        } else if (message.contains("not found") || message.contains("no such file")) {
            sb.append(tr(". Cause: library not found. Update the GPU driver if it supplies this library; ")
                    + tr("for bundled libraries, verify extraction and platform compatibility."));
        } else if (message.contains("wrong ELF class") || message.contains("%1 is not a valid Win32")) {
            sb.append(tr(". Cause: architecture mismatch (32-bit / 64-bit). ")
                    + tr("Minecraft 26.3 uses a 64-bit JVM and requires 64-bit native libraries."));
        } else {
            sb.append(tr(". Original error: ")).append(message);
        }
        return sb.toString();
    }

    @Override
    public boolean exists(String libraryName) {
        if (libraryName == null || libraryName.isBlank()) {
            return false;
        }
        if (libraries.containsKey(libraryName)) {
            return true;
        }
        // Check search directories first.
        String platformName = platformFileName(libraryName);
        for (Path dir : nativeSearchPaths) {
            if (Files.isRegularFile(dir.resolve(platformName))) {
                return true;
            }
        }
        if (Files.isRegularFile(Path.of(libraryName))) {
            return true;
        }
        // Search java.library.path by platform filename without System.loadLibrary. exists() must not map a library, run initialization or create untracked loaded state. This conservative search cannot reproduce every OS lookup location; false means not found in known directories, not guaranteed unloadable.
        String libraryPath = System.getProperty("java.library.path", "");
        for (String entry : libraryPath.split(java.util.regex.Pattern.quote(
                java.io.File.pathSeparator))) {
            if (!entry.isBlank() && Files.isRegularFile(Path.of(entry, platformName))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public dev.luxloader.api.nativebridge.NativeBridge.Arena arena(String purpose) {
        requireAvailable();
        return new ArenaImpl(purpose);
    }

    @Override
    public String platformTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osTag = os.contains("win") ? "windows"
                : os.contains("mac") ? "macos"
                : os.contains("linux") ? "linux" : "unknown";
        String archTag = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64"
                : (arch.contains("64")) ? "x64" : "x86";
        return osTag + "-" + archTag;
    }

    @Override
    public List<String> loadedLibraries() {
        return List.copyOf(loadedLibraries);
    }

    @Override
    public List<String> extractedPaths() {
        return List.copyOf(extractedPaths);
    }

    /** Registers a directory after extracting bundled native libraries. */
    public void recordExtractedPath(Path path) {
        if (path != null) {
            extractedPaths.add(path.toString());
        }
    }

    /** Platform-specific library filename. */
    static String platformFileName(String libraryName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return libraryName.endsWith(".dll") ? libraryName : libraryName + ".dll";
        }
        if (os.contains("mac")) {
            return libraryName.endsWith(".dylib") ? libraryName : "lib" + libraryName + ".dylib";
        }
        return libraryName.endsWith(".so") ? libraryName : "lib" + libraryName + ".so";
    }

    private void requireAvailable() {
        if (!available) {
            throw new UnsupportedOperationException(
                    tr("FFM unavailable (restricted environment or older JDK). ")
                            + tr("Use JDK 22+ and ensure security policy permits native access. ")
                            + tr("Plugins should check NativeBridge#isAvailable and use a fallback."));
        }
    }

    // Library implementation.

    private final class NativeLibraryImpl implements NativeLibrary {

        private final String name;
        private final Optional<String> path;
        private final SymbolLookup lookup;
        /**
         * Downcall cache key: function name plus the complete FunctionDescriptor. Argument types and order
         * participate in descriptor value equality.
         */
        private record HandleKey(String functionName, FunctionDescriptor descriptor) {
        }

        private final Map<HandleKey, MethodHandle> handleCache = new ConcurrentHashMap<>();

        NativeLibraryImpl(String name, Optional<String> path, SymbolLookup lookup) {
            this.name = name;
            this.path = path;
            this.lookup = lookup;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<String> path() {
            return path;
        }

        @Override
        public long lookup(String functionName) {
            return lookup.find(functionName)
                    .map(MemorySegment::address)
                    .orElseThrow(() -> new NativeLoadException(name,
                            tr("Library symbol not found: ") + functionName
                                    + tr(". The library version may differ from the expected API. ")
                                    + tr("Check the vendor SDK version.")));
        }

        @Override
        public int callInt(String functionName, Object... args) {
            MethodHandle handle = handle(functionName, int.class, args);
            try {
                return (int) handle.invokeWithArguments(prepare(args));
            } catch (Throwable t) {
                throw wrap(functionName, t);
            }
        }

        @Override
        public long callPointer(String functionName, Object... args) {
            // Pointer results are exposed to callers as long addresses.
            MethodHandle handle = handle(functionName, long.class, args);
            try {
                return (long) handle.invokeWithArguments(prepare(args));
            } catch (Throwable t) {
                throw wrap(functionName, t);
            }
        }

        @Override
        public float callFloat(String functionName, Object... args) {
            MethodHandle handle = handle(functionName, float.class, args);
            try {
                return (float) handle.invokeWithArguments(prepare(args));
            } catch (Throwable t) {
                throw wrap(functionName, t);
            }
        }

        @Override
        public void callVoid(String functionName, Object... args) {
            MethodHandle handle = handle(functionName, void.class, args);
            try {
                handle.invokeWithArguments(prepare(args));
            } catch (Throwable t) {
                throw wrap(functionName, t);
            }
        }

        @Override
        public long addressOf(String functionName) {
            return lookup(functionName);
        }

        @Override
        public void close() {
            handleCache.clear();
        }

        /**
         * Derives a function descriptor from return type and supported arguments: long/MemoryHandle pointers,
         * int, float and boolean. Returns int, long address, float or void. Pass structures through pointers.
         */
        private MethodHandle handle(String functionName, Class<?> returnType, Object... args) {
            // Compute the descriptor before the cache key so argument types are included; see HandleKey.
            java.lang.foreign.MemoryLayout returnLayout = switch (returnType.getSimpleName()) {
                case "int" -> ValueLayout.JAVA_INT;
                case "float" -> ValueLayout.JAVA_FLOAT;
                case "long" -> ValueLayout.JAVA_LONG;
                case "void" -> null;
                default -> throw new NativeLoadException(name,
                        tr("Unsupported return type: ") + returnType + tr(" (function ") + functionName + "）");
            };

            List<java.lang.foreign.MemoryLayout> layouts = new ArrayList<>(args.length);
            for (Object arg : args) {
                layouts.add(layoutOf(arg));
            }

            FunctionDescriptor descriptor = returnLayout == null
                    ? FunctionDescriptor.ofVoid(layouts.toArray(new java.lang.foreign.MemoryLayout[0]))
                    : FunctionDescriptor.of(returnLayout, layouts.toArray(new java.lang.foreign.MemoryLayout[0]));

            // Include full argument types, not just arity, in the cache key. Optional pointers may be 0L when null and MemoryHandle otherwise; reusing a handle for the other signature caused a reproducible ClassCastException in the strlen test.
            HandleKey key = new HandleKey(functionName, descriptor);
            MethodHandle cached = handleCache.get(key);
            if (cached != null) {
                return cached;
            }

            MemorySegment symbol = lookup.find(functionName)
                    .orElseThrow(() -> new NativeLoadException(name, tr("Library symbol not found: ") + functionName));

            MethodHandle handle = linker.downcallHandle(symbol, descriptor);
            handleCache.put(key, handle);
            return handle;
        }

        /** Converts Java arguments to FFM-compatible values. */
        private Object[] prepare(Object... args) {
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof MemoryHandleImpl impl) {
                    out[i] = impl.segment();
                } else if (arg instanceof Boolean b) {
                    // This bridge convention passes booleans as C int values.
                    out[i] = b ? 1 : 0;
                } else {
                    out[i] = arg;
                }
            }
            return out;
        }

        private java.lang.foreign.MemoryLayout layoutOf(Object arg) {
            if (arg instanceof MemoryHandleImpl) {
                return ValueLayout.ADDRESS;
            }
            if (arg instanceof Boolean) {
                return ValueLayout.JAVA_INT;
            }
            if (arg instanceof Integer) {
                return ValueLayout.JAVA_INT;
            }
            if (arg instanceof Long) {
                return ValueLayout.JAVA_LONG;
            }
            if (arg instanceof Float) {
                return ValueLayout.JAVA_FLOAT;
            }
            if (arg instanceof Double) {
                return ValueLayout.JAVA_DOUBLE;
            }
            throw new NativeLoadException(name,
                    tr("Unsupported argument type: ") + (arg == null ? "null" : arg.getClass().getName())
                            + tr(". Supported: MemoryHandle / boolean / int / long / float / double."));
        }

        private NativeLoadException wrap(String functionName, Throwable t) {
            return new NativeLoadException(name,
                    tr("Calling ") + functionName + tr(" failed: ") + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage()), t);
        }
    }

    // Arenas and memory handles.

    private static final class ArenaImpl implements dev.luxloader.api.nativebridge.NativeBridge.Arena {

        private final java.lang.foreign.Arena delegate;
        private final List<MemoryHandleImpl> allocated = new ArrayList<>();
        private long allocatedBytes;

        ArenaImpl(String purpose) {
            this.delegate = java.lang.foreign.Arena.ofShared();
        }

        @Override
        public dev.luxloader.api.nativebridge.NativeBridge.MemoryHandle allocate(long bytes) {
            MemorySegment segment = delegate.allocate(bytes);
            return track(segment);
        }

        @Override
        public dev.luxloader.api.nativebridge.NativeBridge.MemoryHandle allocateAligned(long bytes, long alignment) {
            if (alignment <= 0) {
                throw new IllegalArgumentException(tr("Alignment must be positive"));
            }
            MemorySegment segment = delegate.allocate(bytes, alignment);
            return track(segment);
        }

        @Override
        public dev.luxloader.api.nativebridge.NativeBridge.MemoryHandle allocateUtf8(String text) {
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            byte[] bytes = text.getBytes(utf8);
            // Append NUL for a C string.
            MemorySegment segment = delegate.allocate(bytes.length + 1);
            MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
            segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
            return track(segment);
        }

        @Override
        public long allocatedBytes() {
            return allocatedBytes;
        }

        @Override
        public void close() {
            allocated.clear();
            delegate.close();
        }

        private MemoryHandleImpl track(MemorySegment segment) {
            MemoryHandleImpl handle = new MemoryHandleImpl(segment);
            allocated.add(handle);
            allocatedBytes += segment.byteSize();
            return handle;
        }
    }

    private static final class MemoryHandleImpl implements dev.luxloader.api.nativebridge.NativeBridge.MemoryHandle {

        private final MemorySegment segment;

        MemoryHandleImpl(MemorySegment segment) {
            this.segment = segment;
        }

        MemorySegment segment() {
            return segment;
        }

        @Override
        public long address() {
            return segment.address();
        }

        @Override
        public long size() {
            return segment.byteSize();
        }

        @Override
        public int getInt(long offset) {
            return segment.get(ValueLayout.JAVA_INT, offset);
        }

        @Override
        public void putInt(long offset, int value) {
            segment.set(ValueLayout.JAVA_INT, offset, value);
        }

        @Override
        public long getLong(long offset) {
            return segment.get(ValueLayout.JAVA_LONG, offset);
        }

        @Override
        public void putLong(long offset, long value) {
            segment.set(ValueLayout.JAVA_LONG, offset, value);
        }

        @Override
        public float getFloat(long offset) {
            return segment.get(ValueLayout.JAVA_FLOAT, offset);
        }

        @Override
        public void putFloat(long offset, float value) {
            segment.set(ValueLayout.JAVA_FLOAT, offset, value);
        }

        @Override
        public double getDouble(long offset) {
            return segment.get(ValueLayout.JAVA_DOUBLE, offset);
        }

        @Override
        public void putDouble(long offset, double value) {
            segment.set(ValueLayout.JAVA_DOUBLE, offset, value);
        }

        @Override
        public void putBytes(long offset, byte[] data) {
            MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, offset, data.length);
        }

        @Override
        public byte[] getBytes(long offset, int length) {
            byte[] out = new byte[length];
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, out, 0, length);
            return out;
        }

        @Override
        public void putPointer(long offset, long value) {
            segment.set(ValueLayout.ADDRESS, offset, MemorySegment.ofAddress(value));
        }

        @Override
        public ByteBuffer asByteBuffer() {
            return segment.asByteBuffer();
        }
    }
}
