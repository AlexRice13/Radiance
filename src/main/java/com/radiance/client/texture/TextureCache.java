package com.radiance.client.texture;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * Disk cache for processed auxiliary textures (specular, normal, flag).
 * Avoids re-reading PNGs from resource pack ZIPs, re-generating mipmaps,
 * and re-aligning on every game restart.
 */
public class TextureCache {

    private static final int MAX_OLD_CACHES = 2;
    private static final int CACHE_VERSION = 1;

    private static String packSignature;
    private static Path cacheBaseDir;
    private static Path currentCacheDir;
    private static boolean initialized = false;
    private static int cacheHits = 0;
    private static int cacheMisses = 0;

    public static void invalidate() {
        if (initialized && (cacheHits > 0 || cacheMisses > 0)) {
            printStats();
        }
        packSignature = null;
        currentCacheDir = null;
        initialized = false;
        cacheHits = 0;
        cacheMisses = 0;
    }

    private static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }

        try {
            Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
            cacheBaseDir = gameDir.resolve("radiance").resolve("cache").resolve("textures");
            Files.createDirectories(cacheBaseDir);

            packSignature = computePackSignature();
            currentCacheDir = cacheBaseDir.resolve(packSignature);

            boolean cacheExists = Files.isDirectory(currentCacheDir);
            Files.createDirectories(currentCacheDir);
            for (String type : new String[]{"specular", "normal", "flag"}) {
                Files.createDirectories(currentCacheDir.resolve(type));
            }

            initialized = true;

            if (cacheExists) {
                System.out.println(
                    "[Radiance TextureCache] Using existing cache: " + packSignature);
            } else {
                System.out.println(
                    "[Radiance TextureCache] Creating new cache: " + packSignature);
            }

            cleanupOldCaches();
        } catch (IOException e) {
            System.err.println(
                "[Radiance TextureCache] Failed to initialize: " + e.getMessage());
            initialized = false;
        }
    }

    private static String computePackSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append("v").append(CACHE_VERSION).append(";");

        try {
            ResourcePackManager packManager = MinecraftClient.getInstance()
                .getResourcePackManager();
            List<String> packNames = packManager.getEnabledProfiles().stream()
                .map(ResourcePackProfile::getId)
                .sorted()
                .collect(Collectors.toList());

            for (String name : packNames) {
                sb.append(name).append(";");
            }
        } catch (Exception e) {
            sb.append("unknown_packs;");
        }

        // Include file metadata for file-based resource packs
        try {
            Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
            Path packDir = gameDir.resolve("resourcepacks");
            if (Files.isDirectory(packDir)) {
                try (Stream<Path> stream = Files.list(packDir)) {
                    List<String> fileInfos = stream
                        .filter(
                            p -> Files.isRegularFile(p) && p.toString().endsWith(".zip"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(p -> {
                            try {
                                return p.getFileName().toString()
                                    + ":" + Files.size(p)
                                    + ":" + Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return p.getFileName().toString();
                            }
                        })
                        .collect(Collectors.toList());

                    for (String info : fileInfos) {
                        sb.append(info).append(";");
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Try to load a cached auxiliary texture.
     *
     * @return NativeImage if cache hit, null if cache miss
     */
    public static NativeImage loadCached(Identifier identifier, String type, int level,
        NativeImage.Format format, int expectedWidth, int expectedHeight) {
        ensureInitialized();
        if (!initialized) {
            return null;
        }

        try {
            String key = hashIdentifier(identifier);
            Path cacheFile = currentCacheDir.resolve(type)
                .resolve(key + "_L" + level + ".bin");

            if (!Files.exists(cacheFile)) {
                cacheMisses++;
                return null;
            }

            try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(cacheFile)))) {
                int version = dis.readInt();
                if (version != CACHE_VERSION) {
                    cacheMisses++;
                    return null;
                }
                int cachedWidth = dis.readInt();
                int cachedHeight = dis.readInt();
                int cachedChannels = dis.readInt();

                if (cachedWidth != expectedWidth || cachedHeight != expectedHeight
                    || cachedChannels != format.getChannelCount()) {
                    cacheMisses++;
                    return null;
                }

                int dataSize = cachedWidth * cachedHeight * cachedChannels;
                byte[] data = new byte[dataSize];
                dis.readFully(data);

                NativeImage image = new NativeImage(format, cachedWidth, cachedHeight,
                    false);
                long pointer =
                    ((com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt) (Object) image)
                        .radiance$getPointer();

                ByteBuffer buf = MemoryUtil.memByteBuffer(pointer, dataSize);
                buf.put(data);

                cacheHits++;
                return image;
            }
        } catch (IOException e) {
            cacheMisses++;
            return null;
        }
    }

    /**
     * Save a processed auxiliary texture to cache.
     */
    public static void saveToCache(Identifier identifier, String type, int level,
        NativeImage image) {
        if (!initialized) {
            return;
        }

        try {
            String key = hashIdentifier(identifier);
            Path cacheFile = currentCacheDir.resolve(type)
                .resolve(key + "_L" + level + ".bin");
            Path tmpFile = currentCacheDir.resolve(type)
                .resolve(key + "_L" + level + ".tmp");

            int width = image.getWidth();
            int height = image.getHeight();
            int channels = image.getFormat().getChannelCount();
            int dataSize = width * height * channels;

            long pointer =
                ((com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt) (Object) image)
                    .radiance$getPointer();
            byte[] data = new byte[dataSize];
            ByteBuffer buf = MemoryUtil.memByteBuffer(pointer, dataSize);
            buf.get(data);

            try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmpFile)))) {
                dos.writeInt(CACHE_VERSION);
                dos.writeInt(width);
                dos.writeInt(height);
                dos.writeInt(channels);
                dos.write(data);
            }

            // Atomic rename
            Files.move(tmpFile, cacheFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Silently fail - cache is optional
        }
    }

    private static String hashIdentifier(Identifier identifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(identifier.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return identifier.toString().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        }
    }

    private static void cleanupOldCaches() {
        try {
            if (cacheBaseDir == null || !Files.isDirectory(cacheBaseDir)) {
                return;
            }

            List<Path> cacheDirs;
            try (Stream<Path> stream = Files.list(cacheBaseDir)) {
                cacheDirs = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .collect(Collectors.toList());
            }

            // Keep current + MAX_OLD_CACHES
            if (cacheDirs.size() > MAX_OLD_CACHES + 1) {
                for (int i = 0; i < cacheDirs.size() - MAX_OLD_CACHES - 1; i++) {
                    Path oldCache = cacheDirs.get(i);
                    if (!oldCache.equals(currentCacheDir)) {
                        deleteDirectory(oldCache);
                        System.out.println(
                            "[Radiance TextureCache] Cleaned up old cache: "
                                + oldCache.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            // Ignore cleanup errors
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        /* ignore */
                    }
                });
        }
    }

    public static void printStats() {
        int total = cacheHits + cacheMisses;
        if (total > 0) {
            System.out.println(
                "[Radiance TextureCache] Stats: " + cacheHits + " hits, " + cacheMisses
                    + " misses (" + (cacheHits * 100 / total) + "% hit rate)");
        }
    }
}
