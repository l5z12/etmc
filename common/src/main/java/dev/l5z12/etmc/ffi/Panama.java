package dev.l5z12.etmc.ffi;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A thin, reflection-based facade over the {@code java.lang.foreign} (Project Panama / FFM) API.
 *
 * <p>Why reflection? {@code java.lang.foreign} is a <em>preview</em> API in Java 21 and only
 * becomes final in Java 22. Referencing it directly forces {@code --enable-preview}, which marks
 * the class files as preview and version-locks the jar to exactly Java 21. Minecraft mods must run
 * on a range of runtimes (Java 21 through 26+), so we reach the API reflectively instead: the
 * compiled bytecode never mentions a preview type, yet at runtime the calls work on every version
 * (verified on Zulu 21 and 25, with and without {@code --enable-preview}).
 *
 * <p>{@link MethodHandle} and {@link ByteBuffer} are <em>not</em> preview, so once we obtain a
 * downcall handle we invoke it normally, and once we have a {@code MemorySegment}'s {@link ByteBuffer}
 * we read/write through it directly. The only opaque values we juggle as {@link Object} are
 * {@code Arena} and {@code MemorySegment}.
 */
public final class Panama {

    /** Scalar kinds we map onto FFM value layouts. */
    public enum Kind { INT, SHORT, LONG, BYTE, PTR, VOID }

    private static final boolean AVAILABLE;
    private static final Throwable INIT_ERROR;

    // Linker + the global lookup arena.
    private static Object linker;
    private static Object globalArena;

    // Reflected classes.
    private static Class<?> cLinker;
    private static Class<?> cSymbolLookup;
    private static Class<?> cArena;
    private static Class<?> cFunctionDescriptor;
    private static Class<?> cMemoryLayout;
    private static Class<?> cMemorySegment;
    private static Class<?> cValueLayout;
    private static Class<?> cLinkerOption;
    private static Object emptyOptions; // Linker.Option[0]

    // Reflected methods.
    private static Method mLibraryLookup;   // SymbolLookup.libraryLookup(Path, Arena)
    private static Method mFind;            // SymbolLookup.find(String) -> Optional<MemorySegment>
    private static Method mDowncallHandle;  // Linker.downcallHandle(MemorySegment, FunctionDescriptor, Option[])
    private static Method mArenaOfConfined; // Arena.ofConfined()
    private static Method mArenaAllocate;   // Arena.allocate(long)
    private static Method mArenaClose;      // Arena.close()
    private static Method mFdOf;            // FunctionDescriptor.of(MemoryLayout, MemoryLayout[])
    private static Method mFdOfVoid;        // FunctionDescriptor.ofVoid(MemoryLayout[])
    private static Method mSegOfAddress;    // MemorySegment.ofAddress(long)
    private static Method mSegReinterpret;  // MemorySegment.reinterpret(long)
    private static Method mSegAsByteBuffer; // MemorySegment.asByteBuffer()
    private static Method mSegAddress;      // MemorySegment.address()

    // Value layouts.
    private static Object lInt;
    private static Object lShort;
    private static Object lLong;
    private static Object lByte;
    private static Object lAddress;

    /** A NULL pointer segment. */
    public static Object NULL;

    static {
        boolean ok = false;
        Throwable err = null;
        try {
            cLinker = Class.forName("java.lang.foreign.Linker");
            cSymbolLookup = Class.forName("java.lang.foreign.SymbolLookup");
            cArena = Class.forName("java.lang.foreign.Arena");
            cFunctionDescriptor = Class.forName("java.lang.foreign.FunctionDescriptor");
            cMemoryLayout = Class.forName("java.lang.foreign.MemoryLayout");
            cMemorySegment = Class.forName("java.lang.foreign.MemorySegment");
            cValueLayout = Class.forName("java.lang.foreign.ValueLayout");
            cLinkerOption = Class.forName("java.lang.foreign.Linker$Option");

            linker = cLinker.getMethod("nativeLinker").invoke(null);
            globalArena = cArena.getMethod("global").invoke(null);

            mLibraryLookup = cSymbolLookup.getMethod("libraryLookup", Path.class, cArena);
            mFind = cSymbolLookup.getMethod("find", String.class);

            Object optsArray = Array.newInstance(cLinkerOption, 0);
            emptyOptions = optsArray;
            mDowncallHandle = cLinker.getMethod("downcallHandle", cMemorySegment, cFunctionDescriptor, optsArray.getClass());

            mArenaOfConfined = cArena.getMethod("ofConfined");
            mArenaAllocate = cArena.getMethod("allocate", long.class);
            mArenaClose = cArena.getMethod("close");

            Object layoutArray = Array.newInstance(cMemoryLayout, 0);
            mFdOf = cFunctionDescriptor.getMethod("of", cMemoryLayout, layoutArray.getClass());
            mFdOfVoid = cFunctionDescriptor.getMethod("ofVoid", layoutArray.getClass());

            mSegOfAddress = cMemorySegment.getMethod("ofAddress", long.class);
            mSegReinterpret = cMemorySegment.getMethod("reinterpret", long.class);
            mSegAsByteBuffer = cMemorySegment.getMethod("asByteBuffer");
            mSegAddress = cMemorySegment.getMethod("address");

            lInt = field(cValueLayout, "JAVA_INT");
            lShort = field(cValueLayout, "JAVA_SHORT");
            lLong = field(cValueLayout, "JAVA_LONG");
            lByte = field(cValueLayout, "JAVA_BYTE");
            lAddress = field(cValueLayout, "ADDRESS");
            NULL = field(cMemorySegment, "NULL");

            ok = true;
        } catch (Throwable t) {
            err = t;
        }
        AVAILABLE = ok;
        INIT_ERROR = err;
    }

    private Panama() {}

    private static Object field(Class<?> c, String name) throws Exception {
        Field f = c.getField(name);
        return f.get(null);
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static Throwable initError() {
        return INIT_ERROR;
    }

    private static void ensure() {
        if (!AVAILABLE) {
            throw new IllegalStateException("java.lang.foreign (FFM) is unavailable on this JVM", INIT_ERROR);
        }
    }

    // ------------------------------------------------------------------ library + handles

    /**
     * Loads a native library by absolute path into the global arena (kept mapped for the JVM
     * lifetime) and returns a lookup object.
     */
    public static Object loadLibrary(Path path) {
        ensure();
        try {
            return mLibraryLookup.invoke(null, path, globalArena);
        } catch (Exception e) {
            throw new RuntimeException("libraryLookup failed for " + path, unwrap(e));
        }
    }

    /** Resolves {@code symbol} in {@code lookup} and binds a downcall handle with the given signature. */
    @SuppressWarnings("unchecked")
    public static MethodHandle downcall(Object lookup, String symbol, Kind ret, Kind... args) {
        ensure();
        try {
            Optional<Object> found = (Optional<Object>) mFind.invoke(lookup, symbol);
            if (found.isEmpty()) {
                throw new RuntimeException("native symbol not found: " + symbol);
            }
            Object addr = found.get();
            Object[] argLayouts = (Object[]) Array.newInstance(cMemoryLayout, args.length);
            for (int i = 0; i < args.length; i++) {
                argLayouts[i] = layout(args[i]);
            }
            Object desc;
            if (ret == Kind.VOID) {
                desc = mFdOfVoid.invoke(null, (Object) argLayouts);
            } else {
                desc = mFdOf.invoke(null, layout(ret), argLayouts);
            }
            return (MethodHandle) mDowncallHandle.invoke(linker, addr, desc, emptyOptions);
        } catch (Exception e) {
            throw new RuntimeException("downcall bind failed for " + symbol, unwrap(e));
        }
    }

    private static Object layout(Kind k) {
        return switch (k) {
            case INT -> lInt;
            case SHORT -> lShort;
            case LONG -> lLong;
            case BYTE -> lByte;
            case PTR -> lAddress;
            case VOID -> throw new IllegalArgumentException("VOID is not a value layout");
        };
    }

    // ------------------------------------------------------------------ arenas + memory

    /** Creates a confined arena (single-thread; allocate/access/close from the same thread). */
    public static Object newArena() {
        ensure();
        try {
            return mArenaOfConfined.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Arena.ofConfined failed", unwrap(e));
        }
    }

    public static void closeArena(Object arena) {
        if (arena == null) return;
        try {
            mArenaClose.invoke(arena);
        } catch (Exception e) {
            // best-effort
        }
    }

    /** Allocates {@code size} bytes of native memory in {@code arena}. */
    public static Object alloc(Object arena, long size) {
        try {
            return mArenaAllocate.invoke(arena, size);
        } catch (Exception e) {
            throw new RuntimeException("arena.allocate failed", unwrap(e));
        }
    }

    /** Allocates and writes a NUL-terminated UTF-8 C string, returning the segment. */
    public static Object cString(Object arena, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        Object seg = alloc(arena, bytes.length + 1L);
        ByteBuffer bb = buffer(seg);
        bb.put(bytes);
        bb.put((byte) 0);
        return seg;
    }

    /** Returns a native-order {@link ByteBuffer} view of a segment. */
    public static ByteBuffer buffer(Object segment) {
        try {
            ByteBuffer bb = (ByteBuffer) mSegAsByteBuffer.invoke(segment);
            return bb.order(ByteOrder.nativeOrder());
        } catch (Exception e) {
            throw new RuntimeException("asByteBuffer failed", unwrap(e));
        }
    }

    /** Wraps a raw address as a zero-length native segment. */
    public static Object segmentOfAddress(long address) {
        try {
            return mSegOfAddress.invoke(null, address);
        } catch (Exception e) {
            throw new RuntimeException("ofAddress failed", unwrap(e));
        }
    }

    /** Reinterprets a segment to a new byte size so it can be read. */
    public static Object reinterpret(Object segment, long newSize) {
        try {
            return mSegReinterpret.invoke(segment, newSize);
        } catch (Exception e) {
            throw new RuntimeException("reinterpret failed", unwrap(e));
        }
    }

    public static long addressOf(Object segment) {
        try {
            return (long) mSegAddress.invoke(segment);
        } catch (Exception e) {
            throw new RuntimeException("address() failed", unwrap(e));
        }
    }

    public static boolean isNull(Object segment) {
        return segment == null || addressOf(segment) == 0L;
    }

    /**
     * Reads a NUL-terminated UTF-8 C string at the given raw address. Returns null for a null
     * address. {@code cap} bounds how far we scan for the terminator.
     */
    public static String readCString(long address, int cap) {
        if (address == 0L) return null;
        Object seg = reinterpret(segmentOfAddress(address), cap);
        ByteBuffer bb = buffer(seg);
        int len = 0;
        while (len < bb.capacity() && bb.get(len) != 0) {
            len++;
        }
        byte[] data = new byte[len];
        bb.get(0, data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static Throwable unwrap(Exception e) {
        Throwable c = e.getCause();
        return c != null ? c : e;
    }
}
