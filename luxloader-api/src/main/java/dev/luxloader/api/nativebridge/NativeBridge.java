package dev.luxloader.api.nativebridge;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Native loading and calls through JDK FFM ({@code java.lang.foreign}). Provides library loading,
 * symbol resolution, memory allocation and downcalls for vendor SDK integrations while keeping
 * concrete SDK functions and structures in plugins. <p>FFM requires JDK 22+ and native access. When
 * {@link #isAvailable()} is false, other methods throw {@link UnsupportedOperationException}; plugins
 * must select a fallback. Libraries may be supplied in plugin JAR directories declared with {@code
 * HostServices#registerNativeLibraryPath(String)} and extracted by the loader, or loaded from the
 * system.
 */
public interface NativeBridge {

    /** Whether FFM is available. */
    boolean isAvailable();

    /** Implementation name, e.g. FFM, JNI or none, for diagnostics. */
    String implementationName();

    /**
     * Loads a native library.
     * @param libraryName name or absolute path; missing suffixes are supplied for the platform (.dll on
     * Windows, .so on Linux)
     * @return library handle
     * @throws NativeLoadException on load failure, including missing dependencies, architecture mismatch
     * or security software interference
     */
    NativeLibrary load(String libraryName);

    /** Try loading and return empty on failure, allowing feature fallback. */
    default Optional<NativeLibrary> tryLoad(String libraryName) {
        try {
            return Optional.of(load(libraryName));
        } catch (NativeLoadException e) {
            return Optional.empty();
        }
    }

    /**
     * Probe whether a native library exists without loading it. Distinguish missing driver libraries from
     * other load failures.
     */
    boolean exists(String libraryName);

    /** Allocate native memory; the caller releases it with Arena.close(). */
    Arena arena(String purpose);

    /** Platform identifier, e.g. windows-x64, selecting bundled native resources. */
    String platformTag();

    /** Successfully loaded library names for diagnostics. */
    List<String> loadedLibraries();

    /** Extracted temporary library paths for diagnostics and manual troubleshooting. */
    List<String> extractedPaths();

    /** Native library handle. */
    interface NativeLibrary extends AutoCloseable {

        /** Library name without its path. */
        String name();

        /** Absolute loaded path when known. */
        Optional<String> path();

        /**
         * Resolves a function address.
         * @param functionName symbol, e.g. {@code xessVKCreateContext}
         * @return native address
         * @throws NativeLoadException when the symbol is absent, often indicating a version mismatch
         */
        long lookup(String functionName);

        /** Whether a function symbol exists. */
        default boolean has(String functionName) {
            try {
                return lookup(functionName) != 0L;
            } catch (NativeLoadException e) {
                return false;
            }
        }

        /**
         * Calls an integer-returning function, as used by SDK result enums such as XessResult and
         * FfxErrorCode.
         * @param functionName symbol
         * @param args ordered arguments: long pointers/handles, int, float, boolean or MemoryHandle
         */
        int callInt(String functionName, Object... args);

        /** Invoke a function returning a 64-bit pointer. */
        long callPointer(String functionName, Object... args);

        /** Invoke a function returning a floating-point value. */
        float callFloat(String functionName, Object... args);

        /** Invoke a function without a return value. */
        void callVoid(String functionName, Object... args);

        /**
         * Resolves a function pointer for passing callbacks to a vendor SDK.
         * @param functionName target symbol
         * @return address usable as a long argument
         */
        long addressOf(String functionName);

        /** Unload; explicit unloading is usually unnecessary before process exit. */
        @Override
        void close();
    }

    /**
     * Scope for native allocations. <pre>{@code try (Arena arena = bridge.arena("xess-init")) {
     * MemoryHandle params = arena.allocate(64); params.putInt(0, 1); int result =
     * lib.callInt("xessVKInit", deviceHandle, params.address()); } }</pre>
     */
    interface Arena extends AutoCloseable {

        /**
         * Allocate zero-initialized memory.
         * @param bytes allocation size in bytes
         */
        MemoryHandle allocate(long bytes);

        /** Allocate at an explicit byte alignment, such as the 16/64-byte alignment required by vendor structures. */
        MemoryHandle allocateAligned(long bytes, long alignment);

        /** Allocate a NUL-terminated UTF-8 string for library paths or device names. */
        MemoryHandle allocateUtf8(String text);

        /** Total bytes allocated in this scope for diagnostics. */
        long allocatedBytes();

        @Override
        void close();
    }

    /**
     * Native memory with primitive accessors and an address. Plugins derive complex structure offsets from
     * vendor headers; the generic loader does not generate vendor-specific bindings.
     */
    interface MemoryHandle {

        /** Starting address. */
        long address();

        /** Byte size. */
        long size();

        int getInt(long offset);

        void putInt(long offset, int value);

        long getLong(long offset);

        void putLong(long offset, long value);

        float getFloat(long offset);

        void putFloat(long offset, float value);

        double getDouble(long offset);

        void putDouble(long offset, double value);

        /** Copy bytes, for example Vulkan sType/pNext structure chains. */
        void putBytes(long offset, byte[] data);

        byte[] getBytes(long offset, int length);

        /** Write a native pointer array such as Vulkan ppEnabledExtensionNames. */
        void putPointer(long offset, long value);

        /** Read or write through NIO for LWJGL MemoryStack/ByteBuffer interoperability. */
        ByteBuffer asByteBuffer();
    }

    /** Native loading failure with actionable details about missing requirements and causes. */
    class NativeLoadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String libraryName;

        public NativeLoadException(String libraryName, String message, Throwable cause) {
            super(message, cause);
            this.libraryName = libraryName;
        }

        public NativeLoadException(String libraryName, String message) {
            this(libraryName, message, null);
        }

        public String libraryName() {
            return libraryName;
        }
    }
}
