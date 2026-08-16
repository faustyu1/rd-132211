package com.mojang.rubydung.level;

import com.mojang.rubydung.HitResult;
import com.mojang.rubydung.Player;
import com.mojang.rubydung.Textures;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.GpuProfiler;
import com.mojang.rubydung.render.vk.Pipelines;
import com.mojang.rubydung.render.vk.VkTexture;

public final class LevelRenderer implements LevelListener {
    private static final int CHUNK_SIZE = WorldChunk.SIZE;

    private final Level level;
    private final Tesselator t = new Tesselator();
    private VkTexture terrain;

    // visible chunk list, computed once on the opaque pass and reused for translucent
    private final java.util.List<WorldChunk> visible = new java.util.ArrayList<>();
    // loaded-but-hidden chunks that still owe the renderer a mesh, nearest first
    private final java.util.List<WorldChunk> offscreen = new java.util.ArrayList<>();
    private Frustum frustum;
    private int visStamp = 0;
    private float viewDistance = 512f;

    // flood-fill queue (section granularity), grown once and reused
    private WorldChunk[] qChunk = new WorldChunk[4096];
    private int[] qSy = new int[4096];
    private int[] qFrom = new int[4096];
    private int[] qMask = new int[4096];
    private int[] qDirs = new int[4096];
    private int qHead, qTail;

    public LevelRenderer(Level level) {
        this.level = level;
        level.addListener(this);
    }

    /** How far the fog reaches: nothing past it is worth culling against, let alone drawing. */
    public void setViewDistance(float blocks) { this.viewDistance = blocks; }

    /**
     * The terrain sheet as a texture array, one 16x16 tile per layer. Sliced rather than
     * sampled as an atlas so the mip chain can exist at all: mipping a packed sheet averages
     * neighbouring tiles into each other and fringes every distant block.
     */
    private VkTexture terrain() {
        if (terrain == null) terrain = Textures.loadTileArray("/terrain.png", 16, 16);
        return terrain;
    }

    public void render(Player player, int layer) {
        GameRenderer r = GameRenderer.instance;
        if (layer == 0) {
            WorldChunk.beginFrame();
            computeVisibility(player);
            // visible chunks get the mesh budget first, then whatever is left goes to the
            // hidden ones nearest the player so they are ready before they come into view
            for (var chunk : visible) chunk.pump();
            offscreen.clear();
            for (var chunk : level.getLoadedChunks())
                if (chunk.visFrame != visStamp && chunk.hasWork()) offscreen.add(chunk);
            if (!offscreen.isEmpty()) {
                offscreen.sort(java.util.Comparator.comparingDouble(c -> distSq(c, player)));
                for (var chunk : offscreen) chunk.pump();
            }
        }
        r.bindTexture(terrain());
        if (layer == 0) {
            r.zoneBegin(GpuProfiler.Zone.CHUNKS_OPAQUE);
            r.beginChunkBatch(Pipelines.Pipeline.CHUNK_OPAQUE);
            // front to back, the order the flood fill produced: the depth test throws out
            // hidden fragments soonest that way
            for (var chunk : visible) chunk.render(0, chunk.visMask);
            r.flushChunkBatch();
            r.zoneEnd(GpuProfiler.Zone.CHUNKS_OPAQUE);
        } else {
            r.zoneBegin(GpuProfiler.Zone.CHUNKS_WATER);
            r.beginChunkBatch(Pipelines.Pipeline.CHUNK_WATER);
            // and back to front for water, which blends instead of writing depth: reversing
            // the same list is the whole sort, since the flood already ordered it by distance
            for (int i = visible.size() - 1; i >= 0; i--) {
                WorldChunk chunk = visible.get(i);
                chunk.render(1, chunk.visMask);
            }
            r.flushChunkBatch();
            r.zoneEnd(GpuProfiler.Zone.CHUNKS_WATER);
        }
    }

    private static double distSq(WorldChunk c, Player player) {
        double dx = c.cx * CHUNK_SIZE + CHUNK_SIZE / 2.0 - player.x;
        double dz = c.cz * CHUNK_SIZE + CHUNK_SIZE / 2.0 - player.z;
        return dx * dx + dz * dz;
    }

    /**
     * Pick the sections to draw by flooding out from the one holding the camera, stepping
     * into a neighbour only through a face pair its visibility graph actually connects.
     * Solid rock connects nothing, so everything sealed behind it — the cave systems that
     * make up most of a chunk's geometry — is never visited. The flood also runs
     * front-to-back, which is the order the depth test rejects overdraw fastest in.
     */
    private void computeVisibility(Player player) {
        visible.clear();
        frustum = Frustum.getFrustum();
        int stamp = ++visStamp;
        int pcx = Math.floorDiv((int) Math.floor(player.x), CHUNK_SIZE);
        int pcz = Math.floorDiv((int) Math.floor(player.z), CHUNK_SIZE);
        WorldChunk start = level.getChunk(pcx, pcz);
        if (start == null) { visibleByFrustum(player, stamp); return; }

        int lx = (int) Math.floor(player.x) - start.cx * CHUNK_SIZE;
        int lz = (int) Math.floor(player.z) - start.cz * CHUNK_SIZE;
        int sy = Math.clamp((int) Math.floor(player.y) / WorldChunk.SECTION, 0, WorldChunk.SECTIONS - 1);

        qHead = qTail = 0;
        // Seed the sections the body occupies, each restricted to the faces of the open
        // pocket the camera is actually standing in. A camera buried in rock gets all six
        // faces back, so the world never vanishes on a bad spawn.
        int bodySy = Math.clamp((int) Math.floor(player.y - 0.9f) / WorldChunk.SECTION, 0, WorldChunk.SECTIONS - 1);
        enqueue(start, sy, -1, start.pocketFaces(lx, (int) Math.floor(player.y), lz), 0, stamp);
        if (bodySy != sy)
            enqueue(start, bodySy, -1, start.pocketFaces(lx, (int) Math.floor(player.y - 0.9f), lz), 0, stamp);

        float maxDist = viewDistance + CHUNK_SIZE;
        float maxDistSq = maxDist * maxDist;
        while (qHead < qTail) {
            WorldChunk c = qChunk[qHead];
            int csy = qSy[qHead], from = qFrom[qHead], dirs = qDirs[qHead];
            // faces sight may leave through: for a seeded section that is the camera's own
            // pocket, otherwise the row of the visibility graph for the face we came in by
            int exits = from >= 0 ? WorldChunk.visExits(c.visGraph(csy), from) : qMask[qHead];
            qHead++;
            for (int face = 0; face < 6; face++) {
                if ((exits & (1 << face)) == 0) continue;
                // never step back along an axis the walk already used: sight travels away
                // from the eye, so a path that goes west and then east again is not a line
                // of sight, it is the flood crawling through the cave network
                if ((dirs & (1 << (face ^ 1))) != 0) continue;
                int ncx = c.cx, ncz = c.cz, nsy = csy;
                switch (face) {
                    case WorldChunk.FACE_DOWN  -> nsy--;
                    case WorldChunk.FACE_UP    -> nsy++;
                    case WorldChunk.FACE_NORTH -> ncz--;
                    case WorldChunk.FACE_SOUTH -> ncz++;
                    case WorldChunk.FACE_WEST  -> ncx--;
                    default                    -> ncx++;
                }
                if (nsy < 0 || nsy >= WorldChunk.SECTIONS) continue;
                WorldChunk n = (ncx == c.cx && ncz == c.cz) ? c : level.getChunk(ncx, ncz);
                if (n == null) continue;
                if (nearestDistSq(n, player) > maxDistSq) continue;
                float x0 = n.cx * CHUNK_SIZE, z0 = n.cz * CHUNK_SIZE, y0 = nsy * WorldChunk.SECTION;
                if (!frustum.cubeInFrustum(x0, y0, z0, x0 + CHUNK_SIZE,
                                           y0 + WorldChunk.SECTION, z0 + CHUNK_SIZE)) continue;
                // enter through the face opposite the one we left by
                enqueue(n, nsy, face ^ 1, 0, dirs | (1 << face), stamp);
            }
        }
    }

    /** Squared horizontal distance from the player to the nearest point of a chunk column. */
    private static float nearestDistSq(WorldChunk c, Player player) {
        float dx = Math.max(Math.abs(player.x - (c.cx * CHUNK_SIZE + CHUNK_SIZE / 2f)) - CHUNK_SIZE / 2f, 0f);
        float dz = Math.max(Math.abs(player.z - (c.cz * CHUNK_SIZE + CHUNK_SIZE / 2f)) - CHUNK_SIZE / 2f, 0f);
        return dx * dx + dz * dz;
    }

    private void enqueue(WorldChunk c, int sy, int from, int mask, int dirs, int stamp) {
        // a chunk enters the draw list the first time the flood reaches any of its sections
        boolean firstTouch = c.visFrame != stamp;
        if (!c.markVisible(sy, stamp)) return;
        if (firstTouch) visible.add(c);
        if (qTail == qChunk.length) {
            qChunk = java.util.Arrays.copyOf(qChunk, qTail * 2);
            qSy = java.util.Arrays.copyOf(qSy, qTail * 2);
            qFrom = java.util.Arrays.copyOf(qFrom, qTail * 2);
            qMask = java.util.Arrays.copyOf(qMask, qTail * 2);
            qDirs = java.util.Arrays.copyOf(qDirs, qTail * 2);
        }
        qChunk[qTail] = c;
        qSy[qTail] = sy;
        qFrom[qTail] = from;
        qMask[qTail] = mask;
        qDirs[qTail] = dirs;
        qTail++;
    }

    /** Fallback while the camera's own chunk is still streaming in: plain frustum culling. */
    private void visibleByFrustum(Player player, int stamp) {
        float maxDist = viewDistance + CHUNK_SIZE;
        float maxDistSq = maxDist * maxDist;
        for (var chunk : level.getLoadedChunks()) {
            if (nearestDistSq(chunk, player) > maxDistSq) continue;
            if (!frustum.cubeInFrustum(chunk.aabb)) continue;
            float x0 = chunk.cx * CHUNK_SIZE, z0 = chunk.cz * CHUNK_SIZE;
            boolean any = false;
            for (int sy = 0; sy < WorldChunk.SECTIONS; sy++) {
                float y0 = sy * WorldChunk.SECTION;
                if (!frustum.cubeInFrustum(x0, y0, z0, x0 + CHUNK_SIZE,
                                           y0 + WorldChunk.SECTION, z0 + CHUNK_SIZE)) continue;
                chunk.markVisible(sy, stamp);
                any = true;
            }
            if (any) visible.add(chunk);
        }
    }

    public void renderHit(HitResult h) {
        GameRenderer r = GameRenderer.instance;
        r.setPipeline(Pipelines.Pipeline.LINES);
        r.bindWhite();
        float alpha = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;
        r.setColor(1.0f, 1.0f, 1.0f, alpha);
        t.init();
        // wireframe box outline of the hit face as line pairs
        addFaceWireframe(h.x(), h.y(), h.z(), h.f());
        t.flushLines();
        r.setColor(1f, 1f, 1f, 1f);
    }

    /** Emit a face outline (4 edges = 8 line vertices) for the given block face. */
    private void addFaceWireframe(int x, int y, int z, int face) {
        float x0 = x, x1 = x + 1f, y0 = y, y1 = y + 1f, z0 = z, z1 = z + 1f;
        float[][] corners;
        switch (face) {
            case 0 -> corners = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}}; // bottom (y-)
            case 1 -> corners = new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}}; // top (y+)
            case 2 -> corners = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0}}; // z-
            case 3 -> corners = new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}; // z+
            case 4 -> corners = new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}}; // x-
            default -> corners = new float[][]{{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}}; // x+
        }
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i];
            float[] b = corners[(i + 1) % 4];
            t.vertex(a[0], a[1], a[2]);
            t.vertex(b[0], b[1], b[2]);
        }
    }

    private void setDirty(int x0, int y0, int z0, int x1, int y1, int z1, boolean urgent) {
        int cx0 = Math.floorDiv(x0, CHUNK_SIZE);
        int cx1 = Math.floorDiv(x1, CHUNK_SIZE);
        int cz0 = Math.floorDiv(z0, CHUNK_SIZE);
        int cz1 = Math.floorDiv(z1, CHUNK_SIZE);
        // look the touched chunks up directly — scanning every loaded chunk costs
        // hundreds of misses per edited tile at a large render distance
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                WorldChunk chunk = level.getChunk(cx, cz);
                // only the sections covering the touched Y-range rebuild, not the whole column
                if (chunk != null) chunk.setDirtyRange(y0, y1, urgent);
            }
        }
    }

    @Override
    public void tileChanged(int x, int y, int z, boolean urgent) {
        // urgent (player/network edit) rebuilds this frame to avoid visible lag; fluid
        // updates settle on the background pool instead of stalling the render thread
        setDirty(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1, urgent);
    }

    @Override
    public void allChanged() {
        for (var chunk : level.getLoadedChunks()) chunk.setDirty();
    }
}
