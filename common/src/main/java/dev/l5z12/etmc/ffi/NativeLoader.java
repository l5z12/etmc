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
 */
public final class NativeLoader {

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

        byte[] data;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("bundled native library not found on classpath: " + resource
                        + " (build the native lib and run :copyNatives)");
            }
            data = in.readAllBytes();
        }

        String digest = shortHash(data);
        Path dir = cacheRoot.resolve("natives").resolve(tag);
        Files.createDirectories(dir);
        // Include a content hash so a changed lib doesn't collide with a locked old copy.
        Path target = dir.resolve(digest + "-" + file);

        if (!Files.exists(target) || Files.size(target) != data.length) {
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
        return new Native(target, resource);
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
