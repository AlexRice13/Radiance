package com.radiance.client.proxy.vulkan;

import static com.radiance.client.constant.VulkanConstants.VkBufferUsageFlagBits.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static com.radiance.client.constant.VulkanConstants.VkBufferUsageFlagBits.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memSet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.radiance.client.constant.Constants;
import com.radiance.client.option.Options;
import com.radiance.client.texture.TextureTracker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.world.ClientWorld;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public class BufferProxy {

    private static final int LOCAL_LIGHT_MAX_COUNT = 96;
    private static final int LOCAL_LIGHT_SCAN_RADIUS_XZ = 24;
    private static final int LOCAL_LIGHT_SCAN_RADIUS_Y = 16;
    private static final int LOCAL_LIGHT_HEADER_SIZE = 16;
    private static final int LOCAL_LIGHT_STRIDE = 32;
    private static final int LOCAL_LIGHT_UPDATE_INTERVAL_TICKS = 4;
    private static final int LOCAL_LIGHT_RESCAN_DISTANCE = 2;
    private static long lastLocalLightUpdateTick = Long.MIN_VALUE;
    private static int lastLocalLightCameraBlockX = Integer.MIN_VALUE;
    private static int lastLocalLightCameraBlockY = Integer.MIN_VALUE;
    private static int lastLocalLightCameraBlockZ = Integer.MIN_VALUE;
    private static String lastLocalLightDimensionKey = "";

    public static native int allocateBuffer();

    public static native void initializeBuffer(int id, int size, int usageFlags);

    public static native void buildIndexBuffer(int id, int type, int drawMode, int vertexCount,
        int expectedIndexCount);

    public static native void queueUpload(long ptr, int dstId);

    public static BufferInfo getBufferInfo(ByteBuffer buf) {
        ByteBuffer b = buf.slice();

        assert b.isDirect();

        long addr = memAddress(b);
        int size = b.remaining();
        return new BufferInfo(buf, addr, size);
    }

    private static void queueUpload(ByteBuffer buf, int expectedSize, int dstId) {
        BufferInfo bufferInfo = getBufferInfo(buf);
        assert bufferInfo.size == expectedSize;
        queueUpload(bufferInfo.addr, dstId);
    }

    public static native void performQueuedUpload();

    public static VertexIndexBufferHandle createAndUploadVertexIndexBuffer(
        BuiltBuffer builtBuffer) {
        BuiltBuffer.DrawParameters drawParameters = builtBuffer.getDrawParameters();
        assert builtBuffer.getDrawParameters().mode() == VertexFormat.DrawMode.QUADS;

        int vertexSize = drawParameters.vertexCount() * drawParameters.format().getVertexSizeByte();
        int vertexId = allocateBuffer();
        initializeBuffer(vertexId, vertexSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT.getValue());
        queueUpload(builtBuffer.getBuffer(), vertexSize, vertexId);

        int indexSize = drawParameters.indexCount() * drawParameters.indexType().size;
        int indexId = allocateBuffer();
        initializeBuffer(indexId, indexSize, VK_BUFFER_USAGE_INDEX_BUFFER_BIT.getValue());
        if (builtBuffer.getSortedBuffer() != null) {
            queueUpload(builtBuffer.getSortedBuffer(), indexSize, indexId);
        } else {
            int type = Constants.IndexTypes.getValue(drawParameters.indexType());
            int drawMode = Constants.DrawModes.getValue(drawParameters.mode());
            buildIndexBuffer(indexId, type, drawMode, drawParameters.vertexCount(),
                drawParameters.indexCount());
        }

        return new VertexIndexBufferHandle(vertexId, indexId);
    }

    public static native void updateOverlayDrawUniform(long ptr);

    public static void updateOverlayDrawUniform() {
        try (MemoryStack stack = stackPush()) {
            int size = 336;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            int baseAddr = 0;

            for (int i = 0; i < 12; i++) {
                int texture = RenderSystem.getShaderTexture(i);
                bb.putInt(baseAddr, texture);
                baseAddr += Integer.BYTES;
            }

            Matrix4f modelViewMat = RenderSystem.getModelViewMatrix();
            modelViewMat.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            Matrix4f projectionMatrix = RenderSystem.getProjectionMatrix();
            projectionMatrix.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            float[] shaderColor = RenderSystem.getShaderColor();
            for (int i = 0; i < 4; i++) {
                bb.putFloat(baseAddr, shaderColor[i]);
                baseAddr += Float.BYTES;
            }

            float shaderGlintAlpha = RenderSystem.getShaderGlintAlpha();
            bb.putFloat(baseAddr, shaderGlintAlpha);
            baseAddr += Float.BYTES;

            Fog fog = RenderSystem.getShaderFog();
            float fogStart = fog.start();
            bb.putFloat(baseAddr, fogStart);
            baseAddr += Float.BYTES;

            float fogEnd = fog.end();
            bb.putFloat(baseAddr, fogEnd);
            baseAddr += Float.BYTES;

            int fogShape = fog.shape().getId();
            bb.putInt(baseAddr, fogShape);
            baseAddr += Integer.BYTES;

            float[] fogColor = {fog.red(), fog.green(), fog.blue(), fog.alpha()};
            for (int i = 0; i < 4; i++) {
                bb.putFloat(baseAddr, fogColor[i]);
                baseAddr += Float.BYTES;
            }

            Matrix4f textureMat = RenderSystem.getTextureMatrix();
            textureMat.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            float gameTime = RenderSystem.getShaderGameTime();
            bb.putFloat(baseAddr, gameTime);
            baseAddr += Float.BYTES;

            float lineWidth = RenderSystem.getShaderLineWidth();
            bb.putFloat(baseAddr, lineWidth);
            baseAddr += Float.BYTES;

            float framebufferWidth = MinecraftClient.getInstance().getWindow()
                .getFramebufferWidth();
            bb.putFloat(baseAddr, framebufferWidth);
            baseAddr += Float.BYTES;

            float framebufferHeight = MinecraftClient.getInstance().getWindow()
                .getFramebufferHeight();
            bb.putFloat(baseAddr, framebufferHeight);
            baseAddr += Float.BYTES;

            Vector3f shaderLightDirection0 = RenderSystem.shaderLightDirections[0];
            shaderLightDirection0.get(baseAddr, bb);
            baseAddr += Float.BYTES * 4;

            Vector3f shaderLightDirection1 = RenderSystem.shaderLightDirections[1];
            shaderLightDirection1.get(baseAddr, bb);

            updateOverlayDrawUniform(addr);
        }
    }

    public static native void updateOverlayPostUniform(long ptr);

    public static void updateOverlayPostUniform(float radius) {
        try (MemoryStack stack = stackPush()) {
            int size = 96;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            int baseAddr = 0;

            Matrix4f projectionMatrix = new Matrix4f();
            projectionMatrix.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            for (int i = 0; i < 2; i++) {
                baseAddr += Float.BYTES;
            }

            for (int i = 0; i < 2; i++) {
                baseAddr += Float.BYTES;
            }

            float[] blurDir = {1.0f, 1.0f};
            for (int i = 0; i < 2; i++) {
                bb.putFloat(baseAddr, blurDir[i]);
                baseAddr += Float.BYTES;
            }

            bb.putFloat(baseAddr, radius);
            baseAddr += Float.BYTES;

            float radiusMultiplier = 1.0f;
            bb.putFloat(baseAddr, radiusMultiplier);

            updateOverlayPostUniform(addr);
        }
    }

    public static native void updateWorldUniform(long ptr);

    public static void updateWorldUniform(Camera camera, Matrix4f viewMatrix,
        Matrix4f effectedViewMatrix, Matrix4f projectionMatrix, int overlayTextureID, Fog fog,
        ClientWorld world, int endSkyTextureID, int endPortalTextureID) {
        try (MemoryStack stack = stackPush()) {
            int size = 560;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            int baseAddr = 0;

            viewMatrix.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            effectedViewMatrix.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            projectionMatrix.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;

            baseAddr += Float.BYTES * 16 * 3; // skip the inverse
            baseAddr += Float.BYTES * 2; // skip the jitter

            float gameTime = RenderSystem.getShaderGameTime();
            bb.putFloat(baseAddr, gameTime);
            baseAddr += Float.BYTES;

            baseAddr += Integer.BYTES; // skip seed

            RenderPhase.setupGlintTexturing(0.16F);
            Matrix4f textureMat = RenderSystem.getTextureMatrix();
            textureMat.get(baseAddr, bb);
            baseAddr += Float.BYTES * 16;
            RenderSystem.resetTextureMatrix();

            bb.putInt(baseAddr, overlayTextureID);
            baseAddr += Integer.BYTES;
            bb.putInt(baseAddr, camera.isThirdPerson() ? 0 : 1);
            baseAddr += Integer.BYTES;
            bb.putFloat(baseAddr, fog.start());
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, fog.end());
            baseAddr += Float.BYTES;

            bb.putFloat(baseAddr, fog.red());
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, fog.green());
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, fog.blue());
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, fog.alpha());
            baseAddr += Float.BYTES;

            bb.putInt(baseAddr, fog.shape().getId());
            baseAddr += Integer.BYTES;
            bb.putInt(baseAddr, world.getDimensionEffects().getSkyType().ordinal());
            baseAddr += Integer.BYTES;

            baseAddr += Float.BYTES; // rayBounces
            baseAddr += Float.BYTES; // pad

            baseAddr += Double.BYTES; // cameraPos
            baseAddr += Double.BYTES; // cameraPos
            baseAddr += Double.BYTES; // cameraPos
            baseAddr += Double.BYTES; // cameraPos

            bb.putInt(baseAddr, endSkyTextureID);
            baseAddr += Integer.BYTES;
            bb.putInt(baseAddr, endPortalTextureID);
            baseAddr += Integer.BYTES;
            baseAddr += Integer.BYTES;
            baseAddr += Integer.BYTES;

            updateWorldUniform(addr);
        }
    }

    public static native void updateSkyUniform(long ptr);

    public static void updateSkyUniform(float baseColorR, float baseColorG, float baseColorB,
        float horizontalColorR, float horizontalColorG, float horizontalColorB,
        float horizontalColorA, Vector3f sunDirection, int skyType, boolean sunRisingOrSetting,
        boolean skyDark, boolean hasBlindnessOrDarkness, int submersionType, int moonPhase,
        float rainGradient, int sunTextureID, int moonTextureID) {
        try (MemoryStack stack = stackPush()) {
            int size = 176;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            int baseAddr = 0;

            bb.putFloat(baseAddr, baseColorR);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, baseColorG);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, baseColorB);
            baseAddr += Float.BYTES;
            bb.putInt(baseAddr, skyType);
            baseAddr += Integer.BYTES;

            bb.putFloat(baseAddr, horizontalColorR);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, horizontalColorG);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, horizontalColorB);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, horizontalColorA);
            baseAddr += Float.BYTES;

            bb.putFloat(baseAddr, sunDirection.x);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, sunDirection.y);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, sunDirection.z);
            baseAddr += Float.BYTES;
            bb.putInt(baseAddr, sunRisingOrSetting ? 1 : 0);
            baseAddr += Integer.BYTES;

            bb.putInt(baseAddr, skyDark ? 1 : 0);
            baseAddr += Integer.BYTES;
            bb.putInt(baseAddr, hasBlindnessOrDarkness ? 1 : 0);
            baseAddr += Integer.BYTES;
            bb.putInt(baseAddr, submersionType);
            baseAddr += Integer.BYTES;
            bb.putInt(baseAddr, moonPhase);
            baseAddr += Integer.BYTES; // moonPhase

            bb.putFloat(baseAddr, rainGradient);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, Options.cloudDensityGradient / 100.0f);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, Options.cloudOpacity / 100.0f);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, Options.cloudAnisotropy / 100.0f);
            baseAddr += Float.BYTES;

            // AtmosphereParams
            baseAddr += Float.BYTES * 4 * 3; // skip

            baseAddr += Float.BYTES * 3; // sunRadiance
            bb.putInt(baseAddr, sunTextureID);
            baseAddr += Integer.BYTES; // sunTextureID

            baseAddr += Float.BYTES * 3; // moonRadiance
            bb.putInt(baseAddr, moonTextureID);
            baseAddr += Integer.BYTES; // moonTextureID

            bb.putFloat(baseAddr, Options.sunSize * 0.001f); // sunAngularRadius in radians
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, cloudWindOffsetX); // cloudWindOffsetX
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, cloudWindOffsetZ); // cloudWindOffsetZ
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, Options.cloudEdgeSoftness / 100.0f); // cloudEdgeSoftness
            baseAddr += Float.BYTES;

            updateSkyUniform(addr);
        }
    }

    public static native void updateMapping(long ptr);

    public static void updateMapping() {
        try (MemoryStack stack = stackPush()) {
            final int elementCount = 4096;
            int size = elementCount * Integer.BYTES * 3;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            memSet(addr, -1, size);
            IntBuffer intView = bb.asIntBuffer();

            for (Map.Entry<Integer, Integer> specularEntry : TextureTracker.GLID2SpecularGLID.entrySet()) {
                int sourceID = specularEntry.getKey();
                int targetID = specularEntry.getValue();
                if (sourceID >= 0 && sourceID < elementCount) {
                    intView.put(sourceID * 3, targetID);
                } else {
                    throw new RuntimeException(
                        "Specular mapping sourceID " + sourceID + " out of index [0, " + (
                            elementCount - 1) + "]");
                }
            }

            for (Map.Entry<Integer, Integer> normalEntry : TextureTracker.GLID2NormalGLID.entrySet()) {
                int sourceID = normalEntry.getKey();
                int targetID = normalEntry.getValue();
                if (sourceID >= 0 && sourceID < elementCount) {
                    intView.put(sourceID * 3 + 1, targetID);
                } else {
                    throw new RuntimeException(
                        "Normal mapping sourceID " + sourceID + " out of index [0, " + (elementCount
                            - 1) + "]");
                }
            }

            for (Map.Entry<Integer, Integer> flagEntry : TextureTracker.GLID2FlagGLID.entrySet()) {
                int sourceID = flagEntry.getKey();
                int targetID = flagEntry.getValue();
                if (sourceID >= 0 && sourceID < elementCount) {
                    intView.put(sourceID * 3 + 2, targetID);
                } else {
                    throw new RuntimeException(
                        "Flag mapping sourceID " + sourceID + " out of index [0, " + (elementCount
                            - 1) + "]");
                }
            }

            updateMapping(addr);
        }
    }

    public static native void updateLightMapUniform(long ptr);

    public static native void updateLocalLights(long ptr, int size);

    public static native void updateCloudCoverage(long ptr, int width, int height);

    // Static fields set by CloudRendererMixins for wind offset
    public static volatile float cloudWindOffsetX = 0.0f;
    public static volatile float cloudWindOffsetZ = 3.96f;

    public static void updateLightMapUniform(float ambientLightFactor, float skyFactor,
        float blockFactor, boolean useBrightLightmap, Vector3f skyLightColor,
        float nightVisionFactor, float darknessScale, float darkenWorldFactor,
        float brightnessFactor) {
        try (MemoryStack stack = stackPush()) {
            int size = 48;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            int baseAddr = 0;

            bb.putFloat(baseAddr, ambientLightFactor);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, skyFactor);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, blockFactor);
            baseAddr += Float.BYTES;
            bb.putInt(baseAddr, useBrightLightmap ? 1 : 0);
            baseAddr += Integer.BYTES;

            bb.putFloat(baseAddr, skyLightColor.x);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, skyLightColor.y);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, skyLightColor.z);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, nightVisionFactor);
            baseAddr += Float.BYTES;

            bb.putFloat(baseAddr, darknessScale);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, darkenWorldFactor);
            baseAddr += Float.BYTES;
            bb.putFloat(baseAddr, brightnessFactor);
            baseAddr += Float.BYTES;
            baseAddr += Integer.BYTES; // pad0

            updateLightMapUniform(addr);
        }
    }

    public static void updateLocalLights(ClientWorld world, Camera camera) {
        if (world != null && camera != null) {
            Vec3d cameraPos = camera.getPos();
            int cameraBlockX = net.minecraft.util.math.MathHelper.floor(cameraPos.x);
            int cameraBlockY = net.minecraft.util.math.MathHelper.floor(cameraPos.y);
            int cameraBlockZ = net.minecraft.util.math.MathHelper.floor(cameraPos.z);
            long worldTime = world.getTime();
            String dimensionKey = world.getRegistryKey().getValue().toString();
            boolean dimensionChanged = !dimensionKey.equals(lastLocalLightDimensionKey);
            boolean movedEnough = Math.abs(cameraBlockX - lastLocalLightCameraBlockX) >= LOCAL_LIGHT_RESCAN_DISTANCE
                || Math.abs(cameraBlockY - lastLocalLightCameraBlockY) >= LOCAL_LIGHT_RESCAN_DISTANCE
                || Math.abs(cameraBlockZ - lastLocalLightCameraBlockZ) >= LOCAL_LIGHT_RESCAN_DISTANCE;
            if (!dimensionChanged && !movedEnough
                && worldTime - lastLocalLightUpdateTick < LOCAL_LIGHT_UPDATE_INTERVAL_TICKS) {
                return;
            }
        }

        int bufferSize = LOCAL_LIGHT_HEADER_SIZE + LOCAL_LIGHT_MAX_COUNT * LOCAL_LIGHT_STRIDE;
        ByteBuffer bb = MemoryUtil.memAlloc(bufferSize);
        try {
            bb.clear();
            if (world == null || camera == null) {
                lastLocalLightUpdateTick = Long.MIN_VALUE;
                lastLocalLightCameraBlockX = Integer.MIN_VALUE;
                lastLocalLightCameraBlockY = Integer.MIN_VALUE;
                lastLocalLightCameraBlockZ = Integer.MIN_VALUE;
                lastLocalLightDimensionKey = "";
                bb.putInt(0, 0);
                bb.putInt(4, LOCAL_LIGHT_MAX_COUNT);
                updateLocalLights(MemoryUtil.memAddress(bb), bufferSize);
                return;
            }

            Vec3d cameraPos = camera.getPos();
            int cameraBlockX = net.minecraft.util.math.MathHelper.floor(cameraPos.x);
            int cameraBlockY = net.minecraft.util.math.MathHelper.floor(cameraPos.y);
            int cameraBlockZ = net.minecraft.util.math.MathHelper.floor(cameraPos.z);
            int minY = Math.max(world.getBottomY(), cameraBlockY - LOCAL_LIGHT_SCAN_RADIUS_Y);
            int maxY = Math.min(world.getTopYInclusive(), cameraBlockY + LOCAL_LIGHT_SCAN_RADIUS_Y);

            List<LocalLightCandidate> candidates = new ArrayList<>();
            BlockPos.Mutable mutablePos = new BlockPos.Mutable();
            for (int y = minY; y <= maxY; y++) {
                for (int z = cameraBlockZ - LOCAL_LIGHT_SCAN_RADIUS_XZ;
                     z <= cameraBlockZ + LOCAL_LIGHT_SCAN_RADIUS_XZ; z++) {
                    for (int x = cameraBlockX - LOCAL_LIGHT_SCAN_RADIUS_XZ;
                         x <= cameraBlockX + LOCAL_LIGHT_SCAN_RADIUS_XZ; x++) {
                        mutablePos.set(x, y, z);
                        BlockState state = world.getBlockState(mutablePos);
                        int luminance = state.getLuminance();
                        if (luminance <= 0) {
                            continue;
                        }

                        float relX = (float) (x + 0.5 - cameraPos.x);
                        float relY = (float) (y + 0.5 - cameraPos.y);
                        float relZ = (float) (z + 0.5 - cameraPos.z);
                        float distanceSq = relX * relX + relY * relY + relZ * relZ;
                        if (distanceSq > LOCAL_LIGHT_SCAN_RADIUS_XZ * LOCAL_LIGHT_SCAN_RADIUS_XZ * 4.0f) {
                            continue;
                        }

                        Vector3f color = approximateLocalLightColor(state);
                        float intensity = Math.max(0.05f, luminance / 15.0f);
                        float radius = 2.5f + luminance * 0.9f;
                        float score = intensity / (1.0f + distanceSq * 0.02f);
                        candidates.add(new LocalLightCandidate(score, relX, relY, relZ, radius, color.x, color.y,
                            color.z, intensity));
                    }
                }
            }

            candidates.sort(Comparator.comparingDouble(LocalLightCandidate::score).reversed());
            int lightCount = Math.min(candidates.size(), LOCAL_LIGHT_MAX_COUNT);
            bb.putInt(0, lightCount);
            bb.putInt(4, LOCAL_LIGHT_MAX_COUNT);

            int baseAddr = LOCAL_LIGHT_HEADER_SIZE;
            for (int i = 0; i < lightCount; i++) {
                LocalLightCandidate light = candidates.get(i);
                bb.putFloat(baseAddr, light.x());
                bb.putFloat(baseAddr + 4, light.y());
                bb.putFloat(baseAddr + 8, light.z());
                bb.putFloat(baseAddr + 12, light.radius());
                bb.putFloat(baseAddr + 16, light.r());
                bb.putFloat(baseAddr + 20, light.g());
                bb.putFloat(baseAddr + 24, light.b());
                bb.putFloat(baseAddr + 28, light.intensity());
                baseAddr += LOCAL_LIGHT_STRIDE;
            }

            updateLocalLights(MemoryUtil.memAddress(bb), bufferSize);
            lastLocalLightUpdateTick = world.getTime();
            lastLocalLightCameraBlockX = cameraBlockX;
            lastLocalLightCameraBlockY = cameraBlockY;
            lastLocalLightCameraBlockZ = cameraBlockZ;
            lastLocalLightDimensionKey = world.getRegistryKey().getValue().toString();
        } finally {
            MemoryUtil.memFree(bb);
        }
    }

    private static Vector3f approximateLocalLightColor(BlockState state) {
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (path.contains("soul")) {
            return new Vector3f(0.35f, 0.8f, 1.35f);
        }
        if (path.contains("lava") || path.contains("magma")) {
            return new Vector3f(1.4f, 0.6f, 0.2f);
        }
        if (path.contains("redstone")) {
            return new Vector3f(1.25f, 0.2f, 0.12f);
        }
        if (path.contains("sea_lantern") || path.contains("beacon") || path.contains("end_rod")) {
            return new Vector3f(0.85f, 1.0f, 1.2f);
        }
        if (path.contains("verdant")) {
            return new Vector3f(0.65f, 1.1f, 0.75f);
        }
        if (path.contains("pearlescent")) {
            return new Vector3f(1.05f, 0.8f, 1.1f);
        }
        if (path.contains("ochre")) {
            return new Vector3f(1.2f, 0.82f, 0.45f);
        }
        if (path.contains("torch") || path.contains("lantern") || path.contains("campfire")
            || path.contains("candle") || path.contains("glowstone") || path.contains("shroomlight")
            || path.contains("jack_o_lantern")) {
            return new Vector3f(1.2f, 0.86f, 0.55f);
        }
        return new Vector3f(1.0f, 0.92f, 0.8f);
    }

    private record LocalLightCandidate(double score, float x, float y, float z, float radius, float r, float g,
                                       float b, float intensity) {
    }

    public record BufferInfo(ByteBuffer buf, long addr, int size) {

    }

    public static class VertexIndexBufferHandle {

        public int vertexId;
        public int indexId;

        public VertexIndexBufferHandle(int vertexId, int indexId) {
            this.vertexId = vertexId;
            this.indexId = indexId;
        }
    }
}
