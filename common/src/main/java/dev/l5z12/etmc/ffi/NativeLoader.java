package dev.l5z12.etmc.ffi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Locates and extracts the bundled EasyTier FFI native library so it can be opened by {@link Panama}.
 *
 * <p>The library is shipped as a classpath resource under {@code /natives/<os>-<arch>/<lib>} and
 * extracted to a per-version cache directory. We extract (rather than load straight from the jar)
 * because {@code SymbolLookup.libraryLookup} needs a real filesystem path.
 *
 * <p>On platforms whose native statically imports a companion library (Windows: {@code Packet.dll},
 * imported by {@code easytier_ffi.dll}), that companion is bundled the same way, staged beside the
 * native, and pre-loaded here so the import resolves without a system install — see
 * {@link #coLocatedDeps()}.
 */
public final class NativeLoader {

    private static final System.Logger LOG = System.getLogger("etmc.NativeLoader");

    private NativeLoader() {}

    /** Result of resolving the platform native library. */
    public record Native(Path path, String resource) {}

    /** The {@code <os>-<arch>} resource directory for this platform, e.g. {@code windows-x86_64}. */
    public static String osArchTag() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String archTag;
        if (arch.contains("aarch64") || arch.contains("arm64")) archTag = "aarch64";
        else if (arch.contains("64")) archTag = "x86_64";
        else archTag = arch;

        return osTag() + "-" + archTag;
    }

    /** The platform's shared-library file name for the EasyTier FFI cdylib. */
    public static String libFileName() {
        return switch (osTag()) {
            case "windows" -> "easytier_ffi.dll";
            case "macos" -> "libeasytier_ffi.dylib";
            default -> "libeasytier_ffi.so";
        };
    }

    private static String osTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        return "linux";
    }

    /**
     * Extracts the bundled native library to a cache directory and returns its path.
     *
     * @param cacheRoot base directory for extracted natives (e.g. the mod config dir)
     * @throws IOException if the resource is missing or cannot be written
     */
    public static Native extract(Path cacheRoot) throws IOException {
        String tag = osArchTag();
        String file = libFileName();
        String resource = "/natives/" + tag + "/" + file;

        byte[] data = readResource(resource);
        if (data == null) {
            throw new IOException("bundled native library not found on classpath: " + resource
                    + " (build the native lib and run :copyNatives)");
        }

        String digest = shortHash(data);
        Path dir = cacheRoot.resolve("natives").resolve(tag);
        Files.createDirectories(dir);
        // Include a content hash so a changed lib doesn't collide with a locked old copy.
        Path target = dir.resolve(digest + "-" + file);

        if (!Files.exists(target) || Files.size(target) != data.length) {
            writeAtomically(dir, target, data);
        }

        // Stage and pre-load any companion library the native statically imports (see
        // stageAndPreloadDeps). Must happen before EasyTier.load() opens the native itself.
        stageAndPreloadDeps(dir, tag);

        return new Native(target, resource);
    }

    /**
     * Companion libraries that {@link #libFileName()} <em>statically imports</em> and that we bundle
     * beside it under the same {@code /natives/<os>-<arch>/} resource dir.
     *
     * <p>On Windows, {@code easytier_ffi.dll} (built with {@code --features easytier/full}) has a
     * load-time import of Npcap's {@code Packet.dll}. etmc always runs EasyTier in {@code no_tun}
     * mode, so those functions are never actually called — but the import must still resolve or the
     * OS loader fails the whole library before any etmc code runs. That is the "please provide a
     * Packet.dll" failure. We ship a tiny shim exporting exactly those symbols (see
     * {@code tools/gen_packet_shim.py}) so no Npcap install is required.
     */
    private static String[] coLocatedDeps() {
        return "windows".equals(osTag()) ? new String[] {"Packet.dll"} : new String[0];
    }

    /**
     * Extracts each companion dependency next to the native and pre-loads it by full path, so the
     * native's static import binds to the already-loaded module. Best-effort: a machine that already
     * has the real library (e.g. an installed Npcap) keeps using it — the real module wins by base
     * name — and a pre-load failure is logged rather than fatal, letting the native load surface the
     * real error if the symbol is genuinely unresolved.
     */
    private static void stageAndPreloadDeps(Path dir, String tag) {
        for (String dep : coLocatedDeps()) {
            String resource = "/natives/" + tag + "/" + dep;
            byte[] data;
            try {
                data = readResource(resource);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "could not read bundled dependency " + resource, e);
                continue;
            }
            if (data == null) continue;  // not bundled for this platform — nothing to stage

            // The file must keep its exact name: the loader binds the native's import by base name.
            Path target = dir.resolve(dep);
            try {
                if (!Files.exists(target) || Files.size(target) != data.length) {
                    writeAtomically(dir, target, data);
                }
            } catch (IOException e) {
                // A locked existing copy (already loaded from a previous launch) is the same shim and
                // still usable; only give up if there is genuinely no file to pre-load.
                if (!Files.exists(target)) {
                    LOG.log(System.Logger.Level.WARNING, "could not stage bundled dependency " + target, e);
                    continue;
                }
            }

            try {
                System.load(target.toAbsolutePath().toString());
            } catch (UnsatisfiedLinkError alreadyLoaded) {
                // Already present in the process (real Npcap, or a prior load) — the import resolves.
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "could not pre-load bundled dependency " + target
                        + "; the native may fail to load without a system-provided " + dep, t);
            }
        }
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** Writes {@code data} to {@code target} via a scratch file in {@code dir}, moved into place. */
    private static void writeAtomically(Path dir, Path target, byte[] data) throws IOException {
        // Write beside the target and move into place, so a half-written library is never loaded.
        Path tmp = Files.createTempFile(dir, "et-", ".tmp");
        boolean moved = false;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(data);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            // A failed move (e.g. Windows keeps the old copy locked while it is loaded) would
            // otherwise leave the scratch file behind on every launch.
            if (!moved) deleteQuietly(tmp);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static String shortHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(Character.forDigit((h[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(h[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }
}
