package dev.lavaesp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.LavaFluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lava ESP: highlights lava blocks at or above y 64 in every loaded chunk around the player.
 *
 * <p>Scanning: one pass every 1000 ms on a daemon thread named {@code "Lava scanner"}, a 10 s
 * watchdog, and a generation counter so a stale pass cannot overwrite a newer one.
 *
 * <p>Region: every loaded chunk around the player (no x/z limit) from y 64 up to y 200.
 *
 * <p>Shading: a block whose six neighbours are all lava is "buried" and is drawn paler, so the
 * interior of a lava pool does not wash out the screen; any block with at least one non-lava
 * neighbour keeps the configured colour. Both are still drawn - nothing is skipped.
 *
 * <p>Rendering uses vanilla's debug-shape API ({@link Gizmos}); {@code setAlwaysOnTop()} makes the
 * boxes visible through terrain.
 */
public final class LavaEspMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("LavaESP");

    private static final int MIN_SCAN_Y = 64;
    private static final int MAX_SCAN_Y = 200;
    /** upper bound on the chunk radius, so a 32-chunk render distance cannot melt the CPU */
    private static final int MAX_CHUNK_RADIUS = 32;
    /** alpha multiplier for blocks that are completely surrounded by lava */
    private static final double BURIED_ALPHA_FACTOR = 0.10;

    private static final long SCAN_TIMEOUT_MS = 10_000L;
    private static final double SCAN_INTERVAL_MS = 1000.0;

    private static LavaEspMod instance;

    /** lava blocks in range -> {@code true} when all six neighbours are lava as well */
    private final Map<BlockPos, Boolean> lavaFaces = new ConcurrentHashMap<>();

    private volatile boolean scanning;
    private volatile long scanStart;
    private volatile int generation;
    private volatile Thread scanThread;
    private final AtomicInteger generationCounter = new AtomicInteger();
    private final Timer timer = new Timer();
    private ClientLevel lastLevel;

    /** diagnostics for the last completed scan: how many chunks were actually loaded */
    private volatile int lastChunksLoaded;
    private volatile int lastChunksTotal;
    /** x/y/z bounds of the last result set, used to explain where the highlight stops */
    private volatile int[] lastBounds = new int[0];

    private volatile boolean reportRequested;
    private volatile int pendingReport = -1;
    private long lastRenderLog;

    public static LavaEspMod get() {
        return instance;
    }

    public int trackedBlocks() {
        return lavaFaces.size();
    }

    /** drop the cache and force a rescan on the next tick (used by /lavaesp) */
    public void invalidate() {
        reset();
    }

    /** ask for the next finished scan to be reported back in chat */
    public void requestReport() {
        this.reportRequested = true;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LavaEspConfig.load();
        LOGGER.info("Lava ESP ready (build b1.5.0, enabled={}, color=#{}) - every loaded chunk, y {}..{}",
                LavaEspConfig.enabled, String.format("%08X", LavaEspConfig.color), MIN_SCAN_Y, MAX_SCAN_Y);

        LavaEspCommand.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> this.reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(this::render);
    }

    // ------------------------------------------------------------------ tick

    private void tick(Minecraft mc) {
        int report = this.pendingReport;
        if (report >= 0) {
            this.pendingReport = -1;
            if (mc.player != null) {
                String msg = report > 0
                        ? "marked " + report + " lava blocks"
                        : "no lava between y=" + MIN_SCAN_Y + " and y=" + MAX_SCAN_Y
                                + " in the loaded chunks around you";
                mc.player.sendSystemMessage(Component.literal("[LavaESP] " + msg));
            }
        }

        if (!LavaEspConfig.enabled) {
            if (!lavaFaces.isEmpty()) {
                reset();
            }
            lastLevel = null;
            return;
        }
        ClientLevel level = mc.level;
        if (level == null) {
            if (!lavaFaces.isEmpty()) {
                reset();
            }
            lastLevel = null;
            return;
        }
        if (level != lastLevel) {
            reset();
            lastLevel = level;
        }

        if (this.scanning) {
            Thread thread = this.scanThread;
            boolean timedOut = System.currentTimeMillis() - this.scanStart >= SCAN_TIMEOUT_MS;
            boolean threadDead = thread != null && !thread.isAlive();
            if (timedOut || threadDead) {
                if (timedOut) {
                    LOGGER.warn("LavaESP scan timed out, dropping current scan.");
                }
                this.stopScan();
                this.timer.reset();
            }
        } else if (this.timer.hasReached(SCAN_INTERVAL_MS)) {
            this.timer.reset();
            this.startScan(level);
        }
    }

    private void startScan(ClientLevel level) {
        int current = this.generationCounter.incrementAndGet();
        this.generation = current;
        this.scanning = true;
        this.scanStart = System.currentTimeMillis();

        Minecraft mc = Minecraft.getInstance();
        BlockPos center = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
        int radius = Math.min(mc.options.renderDistance().get(), MAX_CHUNK_RADIUS);

        LOGGER.info("[LavaESP] scan #{} start, center=({},{},{}), chunks=+/-{}",
                current, center.getX(), center.getY(), center.getZ(), radius);

        Thread thread = new Thread(() -> {
            try {
                HashMap<BlockPos, Boolean> result = this.scan(level, center, radius);
                this.publish(current, result, null);
            } catch (Throwable t) {
                this.publish(current, null, t);
            }
        }, "Lava scanner");
        thread.setDaemon(true);
        this.scanThread = thread;

        try {
            thread.start();
        } catch (Throwable t) {
            this.publish(current, null, t);
        }
    }

    /** walk every loaded chunk around the centre, section by section, keeping lava at 64 <= y <= 200 */
    private HashMap<BlockPos, Boolean> scan(Level level, BlockPos center, int radius) {
        HashMap<BlockPos, Boolean> result = new HashMap<>();
        int minSectionY = level.getMinY();
        int maxY = Math.min(level.getMaxY(), MAX_SCAN_Y);
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int loaded = 0;
        int total = 0;
        int minX = Integer.MAX_VALUE;
        int maxFoundX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxFoundZ = Integer.MIN_VALUE;
        int minFoundY = Integer.MAX_VALUE;
        int maxFoundY = Integer.MIN_VALUE;

        for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
            for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
                if (Thread.currentThread().isInterrupted()) {
                    return result;
                }
                total++;
                LevelChunk chunk = (LevelChunk) level.getChunkSource()
                        .getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                loaded++;
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int baseY = minSectionY + (index << 4);
                    if (baseY + 15 < MIN_SCAN_Y || baseY > maxY) {
                        continue;
                    }
                    if (!section.maybeHas(state -> state.getFluidState().getType() instanceof LavaFluid)) {
                        continue;
                    }
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < MIN_SCAN_Y || y > maxY) {
                            continue;
                        }
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (!(section.getBlockState(lx, ly, lz).getFluidState()
                                        .getType() instanceof LavaFluid)) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos((cx << 4) + lx, y, (cz << 4) + lz);
                                result.put(pos, isBuried(level, pos));
                                if (pos.getX() < minX) { minX = pos.getX(); }
                                if (pos.getX() > maxFoundX) { maxFoundX = pos.getX(); }
                                if (pos.getZ() < minZ) { minZ = pos.getZ(); }
                                if (pos.getZ() > maxFoundZ) { maxFoundZ = pos.getZ(); }
                                if (pos.getY() < minFoundY) { minFoundY = pos.getY(); }
                                if (pos.getY() > maxFoundY) { maxFoundY = pos.getY(); }
                            }
                        }
                    }
                }
            }
        }
        this.lastChunksLoaded = loaded;
        this.lastChunksTotal = total;
        this.lastBounds = result.isEmpty()
                ? new int[0]
                : new int[]{minX, maxFoundX, minFoundY, maxFoundY, minZ, maxFoundZ};
        return result;
    }

    /** true when every one of the six neighbours is lava, i.e. the block is inside a lava volume */
    private static boolean isBuried(Level level, BlockPos pos) {
        return isLava(level, pos.north()) && isLava(level, pos.south())
                && isLava(level, pos.west()) && isLava(level, pos.east())
                && isLava(level, pos.above()) && isLava(level, pos.below());
    }

    private static boolean isLava(Level level, BlockPos pos) {
        return level.getFluidState(pos).getType() instanceof LavaFluid;
    }

    private void publish(int id, Map<BlockPos, Boolean> result, Throwable error) {
        if (id != this.generation) {
            return;
        }
        if (error != null) {
            LOGGER.error("LavaESP scan failed", error);
        } else if (result != null) {
            this.lavaFaces.clear();
            this.lavaFaces.putAll(result);
            long buried = result.values().stream().filter(Boolean::booleanValue).count();
            int[] b = this.lastBounds;
            String box = b.length == 0
                    ? "none"
                    : String.format("x %d..%d, y %d..%d, z %d..%d", b[0], b[1], b[2], b[3], b[4], b[5]);
            LOGGER.info("[LavaESP] scan #{} done in {} ms, {} blocks ({} buried), chunks loaded {}/{}, bounds {}",
                    id, System.currentTimeMillis() - this.scanStart, result.size(), buried,
                    this.lastChunksLoaded, this.lastChunksTotal, box);
            if (this.reportRequested) {
                this.reportRequested = false;
                this.pendingReport = result.size();
            }
        }
        this.scanning = false;
        this.scanStart = 0L;
        this.scanThread = null;
    }

    private void stopScan() {
        Thread thread = this.scanThread;
        this.generation = this.generationCounter.incrementAndGet();
        this.scanning = false;
        this.scanStart = 0L;
        this.scanThread = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    private void reset() {
        this.stopScan();
        this.lavaFaces.clear();
        this.timer.reset();
    }

    // ---------------------------------------------------------------- render

    /**
     * Registers on {@code BEFORE_GIZMOS}: Fabric fires it right before vanilla calls
     * {@code LevelRenderer.finalizeGizmoCollection()}, so anything added through {@link Gizmos} here
     * is finalised and submitted with the vanilla debug shapes - including the always-on-top set,
     * which is what makes the boxes visible through terrain.
     */
    private void render(LevelRenderContext ctx) {
        if (this.lavaFaces.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        if (System.currentTimeMillis() - this.lastRenderLog > 5000L) {
            this.lastRenderLog = System.currentTimeMillis();
            LOGGER.info("[LavaESP] gizmos for {} lava blocks", this.lavaFaces.size());
        }

        int base = LavaEspConfig.color;
        int baseAlpha = (base >>> 24) & 0xFF;
        int buriedAlpha = (int) Math.min(255.0, baseAlpha * BURIED_ALPHA_FACTOR);
        double fadeStart = LavaEspConfig.fadeStart;
        double fadeSq = fadeStart * fadeStart;
        boolean fade = LavaEspConfig.fade;

        try (Gizmos.TemporaryCollection collector = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            for (Map.Entry<BlockPos, Boolean> entry : this.lavaFaces.entrySet()) {
                BlockPos pos = entry.getKey();
                int alpha = Boolean.TRUE.equals(entry.getValue()) ? buriedAlpha : baseAlpha;
                if (fade) {
                    double distanceSq = mc.player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                    if (distanceSq < fadeSq) {
                        double factor = distanceSq / fadeSq;
                        alpha = (int) Math.min(Math.max(0.0, (double) alpha * factor), 255.0);
                    }
                }
                int color = (alpha << 24) | (base & 0x00FFFFFF);
                Gizmos.cuboid(pos, GizmoStyle.fill(color)).setAlwaysOnTop();
            }
        }
    }

    // ------------------------------------------------------------------- misc

    /** millisecond stopwatch used to pace the scan interval */
    private static final class Timer {
        private long last = System.currentTimeMillis();

        void reset() {
            this.last = System.currentTimeMillis();
        }

        boolean hasReached(double millis) {
            return (double) (System.currentTimeMillis() - this.last) >= millis;
        }
    }
}
