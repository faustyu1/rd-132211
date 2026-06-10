package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.LevelRenderer;
import com.mojang.rubydung.level.Tile;
import com.mojang.rubydung.level.WorldChunk;
import com.mojang.rubydung.level.Frustum;
import com.mojang.rubydung.net.GameClient;
import com.mojang.rubydung.net.GameServer;
import com.mojang.rubydung.render.GL;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;

public class RubyDung implements Runnable {
    private int width;
    private int height;
    private int winWidth;   // logical window size (for mouse coords)
    private int winHeight;
    private Level level;
    private LevelRenderer levelRenderer;
    private Player player;
    private FontRenderer fontRenderer;
    private GameRenderer renderer;
    private final Timer timer = new Timer(60.0f);
    private HitResult hitResult = null;
    private long window;

    private int frameCount = 0;
    private long fpsLastTime = System.currentTimeMillis();
    private int displayFps = 0;

    // screens: -1=main menu, 0=game, 1=pause, 2=settings, 3=mp host, 4=direct connect,
    //          5=server list, 6=inventory, 7=crafting, 8=world select, 9=create world, 10=add/edit server, 11=loading
    private int screen = -1;
    private boolean shouldQuit = false;
    private int menuCooldown = 0;
    private volatile String loadingStatus = "";
    private volatile boolean loadingDone = false;

    // world management
    private static final java.io.File SAVES_DIR = new java.io.File("saves");
    private String worldName = "";            // active world folder name
    private long   worldSeedValue = 0;        // active world seed (shown in settings)
    private String createName = "";           // create-world: name field
    private String createSeed = "";           // create-world: seed field
    private boolean editCreateName = true;    // which create field has focus
    private java.util.List<String> worldList = new java.util.ArrayList<>();
    private int worldScroll = 0;

    // multiplayer
    private GameServer server;
    private GameClient client;
    private final Map<Integer, RemotePlayer> remotePlayers = new HashMap<>();
    private String ipInput    = "";
    private String portInput  = "25565";
    private String nameInput  = "Player";
    private String mpStatus   = "";
    private boolean editingPort = false;
    private boolean editingName = false;

    // server list (screen 5 / 10)
    private static final java.io.File SERVERS_FILE = new java.io.File("servers.properties");
    private java.util.List<String[]> serverList = new java.util.ArrayList<>(); // [name, address]
    private int serverScroll = 0;
    private int serverSelected = -1;
    private String addServerName = "";
    private String addServerAddr = "";
    private boolean editAddName = true;
    private int editServerIdx = -1; // -1 = adding new

    private final Settings settings = new Settings();

    // smooth FOV and sneak offset interpolation
    private float currentFov = 70.0f;
    private float currentSneakOffset = 0.0f;
    private long lastFrameNanos = System.nanoTime();
    private final Matrix4f frustumProj = new Matrix4f();
    private final Matrix4f frustumMv = new Matrix4f();
    private final Matrix4f savedProj = new Matrix4f();
    private final Matrix4f nameTagMv = new Matrix4f();

    // day/night
    private float timeOfDay = 0.0f; // 0=noon, 0.5=midnight
    private int fluidTickCounter = 0;

    // block breaking (survival) + particles
    private final ParticleSystem particles = new ParticleSystem();
    private int breakX, breakY, breakZ;
    private boolean breaking = false;
    private boolean miningHeld = false;
    private float breakProgress = 0f; // 0..1
    private long breakNextMs  = 0;   // ms timestamp: next allowed creative break
    private long placeNextMs  = 0;   // ms timestamp: next allowed place

    // hotbar
    private final int[] hotbar = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    private int selectedSlot = 0;

    // chat
    private boolean chatOpen = false;
    private String chatInput = "";
    private final java.util.ArrayDeque<String> chatMessages = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long>   chatTimes    = new java.util.ArrayDeque<>();
    private static final int  CHAT_MAX     = 50;
    private static final long CHAT_FADE_MS = 8000; // message visible for 8 seconds

    // tab player list
    private boolean tabOpen = false;

    // crafting (screen=7)
    private byte[][] craftGrid = new byte[2][2];
    private byte craftOutput = 0;

    // inventory (screen=6)
    private byte[] inventorySlots = new byte[36];

    public void init() {
        settings.load();

        if (!glfwInit()) throw new RuntimeException("Failed to initialize GLFW");
        if (!glfwVulkanSupported()) throw new RuntimeException("Vulkan is not supported on this system");

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        int[] res = Settings.RESOLUTIONS[settings.resIndex];
        width  = res[0];
        height = res[1];
        winWidth = res[0];
        winHeight = res[1];

        if (settings.fullscreen) {
            long monitor = glfwGetPrimaryMonitor();
            var mode = glfwGetVideoMode(monitor);
            if (mode != null) { width = mode.width(); height = mode.height(); }
            window = glfwCreateWindow(width, height, "RubyDung", monitor, 0);
        } else {
            window = glfwCreateWindow(width, height, "RubyDung", 0, 0);
        }
        if (window == 0) throw new RuntimeException("Failed to create GLFW window");

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> Input.onKey(key, action));
        glfwSetCharCallback(window, (win, codepoint) -> Input.onChar((char) codepoint));
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> Input.onMouseButton(button, action));
        glfwSetCursorPosCallback(window, (win, x, y) -> Input.onCursorPos(x, y));
        glfwSetScrollCallback(window, (win, sx, sy) -> Input.onScroll(sy));
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            width = w; height = h;
            if (renderer != null) renderer.requestResize();
        });
        glfwSetWindowSizeCallback(window, (win, w, h) -> { winWidth = w; winHeight = h; });

        glfwShowWindow(window);

        // read actual sizes after window creation (Retina: fb != window size)
        int[] fbw = new int[1], fbh = new int[1], ww = new int[1], wh = new int[1];
        glfwGetFramebufferSize(window, fbw, fbh);
        glfwGetWindowSize(window, ww, wh);
        if (fbw[0] > 0) { width = fbw[0]; height = fbh[0]; }
        if (ww[0] > 0)  { winWidth = ww[0]; winHeight = wh[0]; }

        renderer = new GameRenderer(window, settings.vsync);
        fontRenderer = new FontRenderer(21);

        screen = -1;
        menuCooldown = 5;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    }

    public void destroy() {
        stopMultiplayer();
        if (level != null && !worldName.isEmpty()) level.save(worldDir(worldName));
        settings.save();
        if (renderer != null) renderer.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Override
    public void run() {
        try {
            init();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        long lastTime = System.currentTimeMillis();

        try {
            while (!shouldQuit && !glfwWindowShouldClose(window)) {
                glfwPollEvents();
                // finish loading on main thread (GL context required for LevelRenderer)
                if (screen == 11 && loadingDone) {
                    levelRenderer = new LevelRenderer(level);
                    player = new Player(level);
                    screen = 0;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    Input.consumeMouseDelta();
                    loadingDone = false;
                }
                timer.advanceTime();
                if (screen == 0) {
                    for (int i = 0; i < timer.ticks; i++) tick();
                } else if (screen == 1 && (server != null || client != null)) {
                    // multiplayer: keep ticking physics+network even while paused so player doesn't float
                    for (int i = 0; i < timer.ticks; i++) tick();
                } else if (screen == -1 || screen == 8 || screen == 9) {
                    for (int i = 0; i < timer.ticks; i++) timeOfDay = (timeOfDay + 1f / (20 * 60 * 10)) % 1f;
                }
                render(timer.a);
                while (System.currentTimeMillis() >= lastTime + 1000) {
                    System.out.printf("%d fps, %d chunk updates, fb=%dx%d win=%dx%d%n", displayFps, WorldChunk.updates, width, height, winWidth, winHeight);
                    WorldChunk.updates = 0;
                    lastTime += 1000;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            destroy();
        }
    }

    public void tick() {
        timeOfDay = (timeOfDay + 1f / (20 * 60 * 10)) % 1f;
        player.tick();
        level.update(player.x, player.z, 8);
        if (fluidTickCounter++ % 5 == 0) level.tickFluids();
        particles.tick();
        if (miningHeld && hitResult != null) updateBreaking();

        if (server != null) {
            server.tick(player.x, player.y, player.z, player.yRot, player.xRot);
            String cm;
            while ((cm = server.pollChat()) != null) addChat(cm);
            // host renders clients using server's stored positions
            var posMap   = server.getClientPos();
            var nameMap  = server.getClientNames();
            for (var entry : posMap.entrySet()) {
                int id = entry.getKey();
                float[] pos = entry.getValue();
                var rp = remotePlayers.computeIfAbsent(id, k -> new RemotePlayer());
                rp.updateFromNet(pos[0], pos[1], pos[2], pos[3], pos[4]);
                if (nameMap.containsKey(id)) rp.name = nameMap.get(id);
            }
            remotePlayers.keySet().retainAll(posMap.keySet());
        }
        if (client != null && client.isAlive()) {
            client.tick(player.x, player.y, player.z, player.yRot, player.xRot);
            String cm;
            while ((cm = client.pollChat()) != null) addChat(cm);
            var posMap  = client.getRemotePlayers();
            var nameMap = client.getRemoteNames();
            for (var entry : posMap.entrySet()) {
                int id = entry.getKey();
                float[] pos = entry.getValue();
                var rp = remotePlayers.computeIfAbsent(id, k -> new RemotePlayer());
                rp.updateFromNet(pos[0], pos[1], pos[2], pos[3], pos[4]);
                if (nameMap.containsKey(id)) rp.name = nameMap.get(id);
            }
            remotePlayers.keySet().retainAll(posMap.keySet());
        }
    }

    private void moveCameraToPlayer(float a) {
        renderer.translate(0.0f, 0.0f, -0.3f);
        renderer.rotate(player.xRot, 1.0f, 0.0f, 0.0f);
        renderer.rotate(player.yRot, 0.0f, 1.0f, 0.0f);
        float x = player.xo + (player.x - player.xo) * a;
        float y = player.yo + (player.y - player.yo) * a;
        float z = player.zo + (player.z - player.zo) * a;
        renderer.translate(-x, -(y - currentSneakOffset), -z);
    }

    private void setPerspective(float fov, float aspect, float near, float far) {
        renderer.setPerspective(fov, aspect, near, far);
    }

    private void setupCamera(float a) {
        long now = System.nanoTime();
        float dt = Math.min((now - lastFrameNanos) / 1_000_000_000.0f, 0.1f);
        lastFrameNanos = now;

        float baseFov = Settings.FOV_VALUES[settings.fovIndex];
        float targetFov = (player != null && player.sprinting) ? baseFov + 10f : baseFov;
        float targetSneak = (player != null && player.sneaking) ? 0.2f : 0.0f;
        float fovAlpha   = 1.0f - (float) Math.pow(0.003, dt);
        float sneakAlpha = 1.0f - (float) Math.pow(0.001, dt);
        currentFov         += (targetFov   - currentFov)         * fovAlpha;
        currentSneakOffset += (targetSneak - currentSneakOffset) * sneakAlpha;

        setPerspective(currentFov, (float) width / height, 0.1f, 384.0f);
        renderer.loadIdentity();
        moveCameraToPlayer(a);
        // update frustum from current matrices
        Frustum.getFrustum().update(renderer.getProjection(frustumProj), renderer.getModelView(frustumMv));
    }

    private HitResult pick() {
        float yaw   = (float) Math.toRadians(player.yRot);
        float pitch = (float) Math.toRadians(player.xRot);
        float dx =  (float)( Math.sin(yaw) * Math.cos(pitch));
        float dy =  (float)(-Math.sin(pitch));
        float dz =  (float)(-Math.cos(yaw) * Math.cos(pitch));

        float px = player.x, py = player.y, pz = player.z;
        float reach = 5.0f;
        int steps = 200;
        int lastBx = Integer.MIN_VALUE, lastBy = 0, lastBz = 0;
        for (int i = 0; i <= steps; i++) {
            float t = reach * i / steps;
            int bx = (int) Math.floor(px + dx * t);
            int by = (int) Math.floor(py + dy * t);
            int bz = (int) Math.floor(pz + dz * t);
            if (level.isSolidTile(bx, by, bz)) {
                int face = 0;
                if (lastBx != Integer.MIN_VALUE) {
                    int fdx = bx - lastBx, fdy = by - lastBy, fdz = bz - lastBz;
                    if      (fdy > 0) face = 0;
                    else if (fdy < 0) face = 1;
                    else if (fdz > 0) face = 2;
                    else if (fdz < 0) face = 3;
                    else if (fdx > 0) face = 4;
                    else              face = 5;
                }
                return new HitResult(bx, by, bz, face);
            }
            lastBx = bx; lastBy = by; lastBz = bz;
        }
        return null;
    }

    private void handleInput(float a) {
        // TAB player list (frame-level check, not event-driven)
        tabOpen = screen == 0 && Input.isKeyDown(GLFW_KEY_TAB);

        for (var e : Input.pollKeyEvents()) {
            boolean pressed = e[1] != GLFW_RELEASE;
            if (pressed) {
                int key = e[0];

                // chat input captures keys first
                if (chatOpen) {
                    if (key == GLFW_KEY_ESCAPE) { chatOpen = false; chatInput = ""; Input.blocked = false; continue; }
                    if (key == GLFW_KEY_ENTER) {
                        if (!chatInput.isEmpty()) {
                            String msg = chatInput.trim();
                            if (msg.startsWith("/gamemode ")) {
                                String arg = msg.substring(10).trim().toUpperCase();
                                if (player != null) {
                                    try { player.mode = Player.GameMode.valueOf(arg); } catch (IllegalArgumentException ex) { /* ignore */ }
                                }
                            } else {
                                String name = nameInput.isEmpty() ? "Player" : nameInput;
                                String formatted = "<" + name + "> " + msg;
                                addChat(formatted);
                                if (server != null) server.broadcastChat(formatted);
                                else if (client != null) client.sendChat(formatted);
                            }
                        }
                        chatInput = ""; chatOpen = false; Input.blocked = false; continue;
                    }
                    if (key == GLFW_KEY_BACKSPACE && !chatInput.isEmpty()) {
                        chatInput = chatInput.substring(0, chatInput.length() - 1);
                    }
                    continue; // consume all other keys while chat open
                }

                if (key == GLFW_KEY_ESCAPE) {
                    if (screen == 7) { screen = 6; }
                    else if (screen == 6) { screen = 0; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); Input.consumeMouseDelta(); }
                    else if (screen == -1) { /* stay on main menu */ }
                    else if (screen == 9) { refreshWorldList(); screen = 8; menuCooldown = 2; }
                    else if (screen == 8) { screen = -1; menuCooldown = 2; }
                    else if (screen == 4) { screen = 5; ipInput = ""; mpStatus = ""; }
                    else if (screen == 10) { screen = 5; }
                    else if (screen == 5) { screen = (level == null) ? -1 : 1; serverSelected = -1; mpStatus = ""; }
                    else if (screen == 3) { screen = (level == null) ? -1 : 1; }
                    else if (screen == 2) { screen = (level == null) ? -1 : 1; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL); Input.consumeMouseDelta(); }
                    else if (screen == 1) { screen = 0; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); Input.consumeMouseDelta(); }
                    else { screen = 1; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL); Input.consumeMouseDelta(); }
                } else if (key == GLFW_KEY_F11) {
                    settings.fullscreen = !settings.fullscreen;
                    applyDisplayMode();
                    settings.save();
                } else if (screen == 9) {
                    if (key == GLFW_KEY_BACKSPACE) {
                        if (editCreateName && !createName.isEmpty()) createName = createName.substring(0, createName.length() - 1);
                        else if (!editCreateName && !createSeed.isEmpty()) createSeed = createSeed.substring(0, createSeed.length() - 1);
                    } else if (key == GLFW_KEY_TAB) {
                        editCreateName = !editCreateName;
                    } else if (key == GLFW_KEY_ENTER) {
                        createWorld();
                    }
                } else if (screen == 3 && editingPort) {
                    // port-only input handled via char events below
                } else if (screen == 10) {
                    if (key == GLFW_KEY_BACKSPACE) {
                        if (editAddName && !addServerName.isEmpty())
                            addServerName = addServerName.substring(0, addServerName.length() - 1);
                        else if (!editAddName && !addServerAddr.isEmpty())
                            addServerAddr = addServerAddr.substring(0, addServerAddr.length() - 1);
                    } else if (key == GLFW_KEY_TAB) {
                        editAddName = !editAddName;
                    } else if (key == GLFW_KEY_ENTER && !addServerAddr.isEmpty()) {
                        saveServer();
                    }
                } else if (screen == 4) {
                    if (key == GLFW_KEY_BACKSPACE) {
                        if (editingName && !nameInput.isEmpty())
                            nameInput = nameInput.substring(0, nameInput.length() - 1);
                        else if (editingPort && !portInput.isEmpty())
                            portInput = portInput.substring(0, portInput.length() - 1);
                        else if (!editingPort && !editingName && !ipInput.isEmpty())
                            ipInput = ipInput.substring(0, ipInput.length() - 1);
                    } else if (key == GLFW_KEY_ENTER && !ipInput.isEmpty()) {
                        doConnect();
                    } else if (key == GLFW_KEY_TAB) {
                        if (editingName)       { editingName = false; editingPort = false; }
                        else if (!editingPort) { editingPort = true; }
                        else                   { editingPort = false; editingName = true; }
                    }
                } else if (screen == 3 && key == GLFW_KEY_BACKSPACE && editingPort && !portInput.isEmpty()) {
                    portInput = portInput.substring(0, portInput.length() - 1);
                } else if (screen == 0) {
                    if (key == GLFW_KEY_T) {
                        chatOpen = true; chatInput = "";
                        Input.blocked = true;
                        Input.pollCharEvents(); // discard the 't' char event from this same keypress
                        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    } else if (key == GLFW_KEY_E) {
                        screen = 6;
                        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                        Input.consumeMouseDelta();
                    }
                } else if (screen == 6 && key == GLFW_KEY_E) {
                    screen = 0; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); Input.consumeMouseDelta();
                } else if (screen == 7 && key == GLFW_KEY_E) {
                    screen = 6;
                }
            }
        }

        // char events for text input (must happen before chatOpen early-return)
        for (char c : Input.pollCharEvents()) {
            if (chatOpen) {
                if (chatInput.length() < 100 && c >= 0x20) chatInput += c; // includes Cyrillic (U+0400+)
            } else if (screen == 9) {
                if (editCreateName) {
                    if (createName.length() < 24 && c >= 0x20 && c < 0x7F) createName += c;
                } else {
                    if (createSeed.length() < 20 && c >= 0x20 && c < 0x7F) createSeed += c;
                }
            } else if (screen == 10) {
                if (editAddName) {
                    if (addServerName.length() < 32 && c >= 0x20 && c < 0x7F) addServerName += c;
                } else {
                    if (addServerAddr.length() < 64 && c >= 0x20 && c < 0x7F) addServerAddr += c;
                }
            } else if (screen == 4) {
                if (editingName) {
                    if (nameInput.length() < 16 && c >= 0x20 && c < 0x7F)
                        nameInput += c;
                } else if (editingPort) {
                    if (Character.isDigit(c) && portInput.length() < 5) portInput += c;
                } else {
                    if ((Character.isDigit(c) || c == '.' || c == ':' || c == '-'
                            || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
                            && ipInput.length() < 40)
                        ipInput += Character.toLowerCase(c);
                }
            } else if (screen == 3 && editingPort) {
                if (Character.isDigit(c) && portInput.length() < 5) portInput += c;
            }
        }

        // when chat is open, discard remaining input and return
        if (chatOpen) {
            Input.pollMouseEvents();
            Input.consumeMouseDelta();
            Input.consumeScroll();
            return;
        }

        if (screen == 0) {
            // re-lock cursor if chat just closed
            if (!chatOpen) glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

            float sens = Settings.SENS_VALUES[settings.sensitivity];
            double[] delta = Input.consumeMouseDelta();
            if (!chatOpen) player.turn((float)(delta[0] * sens / 0.15f), -(float)(delta[1] * sens / 0.15f));
            hitResult = pick();

            double scroll = Input.consumeScroll();
            if (scroll != 0) {
                selectedSlot = ((selectedSlot - (int)Math.signum(scroll)) % 9 + 9) % 9;
            }

            long now = System.currentTimeMillis();

            // mouse events: creative break on LMB press
            for (var e : Input.pollMouseEvents()) {
                if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS
                        && player.mode == Player.GameMode.CREATIVE && hitResult != null && now >= breakNextMs) {
                    breakBlock(hitResult.x(), hitResult.y(), hitResult.z());
                    breakNextMs = now + 250;
                }
                if (e[0] == GLFW_MOUSE_BUTTON_2 && e[1] == GLFW_PRESS) {
                    tryPlaceBlock(); placeNextMs = now + 250; // first press immediate
                }
                if (e[0] == GLFW_MOUSE_BUTTON_2 && e[1] == GLFW_RELEASE) {
                    placeNextMs = 0; // reset so next press is immediate
                }
            }

            // hold LMB — creative continuous break every 250ms
            if (Input.isMouseDown(GLFW_MOUSE_BUTTON_1) && hitResult != null
                    && player.mode == Player.GameMode.CREATIVE && now >= breakNextMs) {
                breakBlock(hitResult.x(), hitResult.y(), hitResult.z());
                breakNextMs = now + 250;
            }

            // continuous LMB in survival
            boolean lmbHeld = Input.isMouseDown(GLFW_MOUSE_BUTTON_1) && hitResult != null;
            boolean mining = lmbHeld && player.mode == Player.GameMode.SURVIVAL;
            miningHeld = mining;
            if (!mining) resetBreaking();

            // hold RMB — place every 250ms
            if (Input.isMouseDown(GLFW_MOUSE_BUTTON_2) && hitResult != null && now >= placeNextMs) {
                tryPlaceBlock(); placeNextMs = now + 250;
            }
        } else {
            // consume mouse delta while in menus so it doesn't accumulate
            Input.consumeMouseDelta();
        }
    }

    private void tryPlaceBlock() {
        if (hitResult == null || player.mode == Player.GameMode.SPECTATOR) return;
        int x = hitResult.x(), y = hitResult.y(), z = hitResult.z();
        switch (hitResult.f()) {
            case 0 -> y--; case 1 -> y++;
            case 2 -> z--; case 3 -> z++;
            case 4 -> x--; case 5 -> x++;
        }
        var blockAABB = new com.mojang.rubydung.phys.AABB(x, y, z, x + 1, y + 1, z + 1);
        if (!blockAABB.intersects(player.bb)) {
            int tileType = hotbar[selectedSlot];
            level.setTile(x, y, z, tileType);
            if (client != null) client.sendSetTile(x, y, z, tileType);
            else if (server != null) server.broadcastTile(x, y, z, tileType);
        }
    }

    private void breakBlock(int x, int y, int z) {
        byte type = level.getBlock(x, y, z);
        if (type == 0 || Tile.hardness(type) < 0) return; // air / unbreakable
        particles.spawnBlockBreak(x, y, z, type);
        level.setTile(x, y, z, 0);
        if (client != null) client.sendSetTile(x, y, z, 0);
        else if (server != null) server.broadcastTile(x, y, z, 0);
    }

    private void updateBreaking() {
        int x = hitResult.x(), y = hitResult.y(), z = hitResult.z();
        byte type = level.getBlock(x, y, z);
        if (type == 0 || Tile.hardness(type) < 0) { resetBreaking(); return; }

        // survival: accumulate progress on the targeted block
        if (!breaking || x != breakX || y != breakY || z != breakZ) {
            breaking = true; breakX = x; breakY = y; breakZ = z; breakProgress = 0f;
        }
        float perTick = (1f / 20f) / Math.max(0.05f, Tile.hardness(type));
        breakProgress += perTick;
        if (breakProgress >= 1f) {
            breakBlock(x, y, z);
            resetBreaking();
        }
    }

    private void resetBreaking() {
        breaking = false;
        breakProgress = 0f;
        breakX = Integer.MIN_VALUE;
    }

    /** Darkening overlay on the block being mined; opacity grows with progress. */
    private void renderBreakProgress() {
        GL.set3DQuadPipeline(Pipelines.Pipeline.WORLD_TRANSLUCENT);
        GL.glColor4f(0f, 0f, 0f, 0.25f + breakProgress * 0.45f);
        float s = 0.502f, cx = breakX + 0.5f, cy = breakY + 0.5f, cz = breakZ + 0.5f;
        float x0 = cx - s, x1 = cx + s, y0 = cy - s, y1 = cy + s, z0 = cz - s, z1 = cz + s;
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex3f(x0,y0,z1); GL.glVertex3f(x1,y0,z1); GL.glVertex3f(x1,y1,z1); GL.glVertex3f(x0,y1,z1);
        GL.glVertex3f(x1,y0,z0); GL.glVertex3f(x0,y0,z0); GL.glVertex3f(x0,y1,z0); GL.glVertex3f(x1,y1,z0);
        GL.glVertex3f(x0,y1,z1); GL.glVertex3f(x1,y1,z1); GL.glVertex3f(x1,y1,z0); GL.glVertex3f(x0,y1,z0);
        GL.glVertex3f(x0,y0,z0); GL.glVertex3f(x1,y0,z0); GL.glVertex3f(x1,y0,z1); GL.glVertex3f(x0,y0,z1);
        GL.glVertex3f(x0,y0,z0); GL.glVertex3f(x0,y0,z1); GL.glVertex3f(x0,y1,z1); GL.glVertex3f(x0,y1,z0);
        GL.glVertex3f(x1,y0,z1); GL.glVertex3f(x1,y0,z0); GL.glVertex3f(x1,y1,z0); GL.glVertex3f(x1,y1,z1);
        GL.glEnd();
        GL.set3DQuadPipeline(Pipelines.Pipeline.WORLD_OPAQUE);
    }

    public void render(float a) {
        handleInput(a);

        // compute sky color for clear
        float sunAngle = (float)(Math.cos(timeOfDay * Math.PI * 2));
        float brightness = Math.max(0.05f, (sunAngle + 1) * 0.5f);
        float sr = 0.5f * brightness, sg = 0.8f * brightness, sb = 1.0f * brightness;

        if (screen == -1) {
            if (!renderer.beginFrame(sr, sg, sb)) return;
            renderMainMenu();
            renderer.endFrame();
            return;
        }
        if (screen == 11) {
            if (!renderer.beginFrame(0.05f, 0.05f, 0.05f)) return;
            renderLoadingScreen();
            renderer.endFrame();
            return;
        }
        if (screen == 2 || screen == 3 || screen == 4 || screen == 5 || screen == 10) {
            if (player == null) {
                if (!renderer.beginFrame(sr, sg, sb)) return;
                if      (screen == 2)  renderSettingsMenu();
                else if (screen == 5)  renderServerList();
                else if (screen == 10) renderAddServer();
                else if (screen == 3)  renderMultiplayerMenu();
                else                   renderJoinScreen();
                renderer.endFrame();
                return;
            }
            // fall through to full 3D render path so game world shows behind menu
        }
        if (screen == 8 || screen == 9) {
            if (!renderer.beginFrame(sr, sg, sb)) return;
            if (screen == 8) renderWorldSelect(); else renderCreateWorld();
            renderer.endFrame();
            return;
        }

        if (!renderer.beginFrame(sr, sg, sb)) return;
        setupCamera(screen != 0 ? 1.0f : a);

        float fogEnd = Settings.DIST_END[settings.renderDist];
        renderer.setFog(sr, sg, sb, 0.0f, fogEnd);

        levelRenderer.render(player, 0);
        levelRenderer.render(player, 1);

        renderer.disableFog();

        particles.render(a);

        if (hitResult != null && screen == 0) levelRenderer.renderHit(hitResult);
        if (breaking && breakProgress > 0f && screen == 0) renderBreakProgress();

        renderRemotePlayers(timer.a);
        renderHud();

        if      (screen == 0) { renderCrosshair(); renderHotbar(); renderChat(); renderTabList(); }
        else if (screen == 1) renderPauseMenu();
        else if (screen == 2) renderSettingsMenu();
        else if (screen == 3) renderMultiplayerMenu();
        else if (screen == 4) renderJoinScreen();
        else if (screen == 5) renderServerList();
        else if (screen == 6) renderInventory();
        else if (screen == 7) renderCrafting();
        else if (screen == 10) renderAddServer();

        renderer.endFrame();
    }

    private void applyVsync() {
        renderer.setVsync(settings.vsync);
    }

    private void applyDisplayMode() {
        int[] res = Settings.RESOLUTIONS[settings.resIndex];
        long monitor = glfwGetPrimaryMonitor();
        if (settings.fullscreen) {
            var mode = glfwGetVideoMode(monitor);
            int w = (mode != null) ? mode.width()  : res[0];
            int h = (mode != null) ? mode.height() : res[1];
            glfwSetWindowMonitor(window, monitor, 0, 0, w, h, GLFW_DONT_CARE);
            winWidth = w; winHeight = h;
        } else {
            var mode = glfwGetVideoMode(monitor);
            int ox = (mode != null) ? (mode.width()  - res[0]) / 2 : 100;
            int oy = (mode != null) ? (mode.height() - res[1]) / 2 : 100;
            glfwSetWindowMonitor(window, 0, ox, oy, res[0], res[1], GLFW_DONT_CARE);
            winWidth = res[0]; winHeight = res[1];
        }
        // On macOS the framebufferSizeCallback may not fire synchronously — read it explicitly
        int[] fbw = new int[1], fbh = new int[1];
        glfwGetFramebufferSize(window, fbw, fbh);
        if (fbw[0] > 0 && fbh[0] > 0) {
            width = fbw[0]; height = fbh[0];
            if (renderer != null) renderer.requestResize();
        }
    }

    private void stopMultiplayer() {
        if (server != null) { server.stop(); server = null; }
        if (client != null) { client.stop(); client = null; }
        remotePlayers.clear();
        mpStatus = "";
    }

    private void renderRemotePlayers(float a) {
        if (remotePlayers.isEmpty()) return;
        GL.set3DQuadPipeline(Pipelines.Pipeline.WORLD_OPAQUE);
        for (var rp : remotePlayers.values()) {
            float rx = rp.xo + (rp.x - rp.xo) * a;
            float ry = rp.yo + (rp.y - rp.yo) * a;
            float rz = rp.zo + (rp.z - rp.zo) * a;
            renderer.push();
            renderer.translate(rx, ry, rz);
            renderer.rotate(-rp.yRot, 0, 1, 0);
            renderMinecraftModel();
            renderer.pop();
            renderNameTag(rx, ry + 0.75f, rz, rp.name);
        }
    }

    /**
     * Classic Minecraft player model. Origin = eye position.
     * Head: 8x8x8 at top, Body: 8x12x4, Arms: 4x12x4 sides, Legs: 4x12x4.
     * Sizes in 1/16 blocks (1 block = 16 px).
     */
    private void renderMinecraftModel() {
        // all sizes in blocks (MC: 1 block = 16px, player = 32px tall)
        // head: 8px cube = 0.5 blocks, sits at eye-level ~ +0.1 to +0.6 above origin
        GL.glColor3f(0.95f, 0.78f, 0.62f); // skin tone
        drawBox(-0.25f, 0.1f, -0.25f,  0.25f, 0.6f, 0.25f);  // head

        // body: 8x12x4 px = 0.5 x 0.75 x 0.25
        GL.glColor3f(0.25f, 0.45f, 0.75f); // shirt
        drawBox(-0.25f, -0.625f, -0.125f,  0.25f, 0.1f, 0.125f);

        // arms 4x12x4 px = 0.25 x 0.75 x 0.25
        GL.glColor3f(0.95f, 0.78f, 0.62f);
        drawBox(-0.5f, -0.625f, -0.125f,  -0.25f, 0.1f, 0.125f);  // left arm
        drawBox( 0.25f,-0.625f, -0.125f,   0.5f,  0.1f, 0.125f);  // right arm

        // legs 4x12x4 px each
        GL.glColor3f(0.18f, 0.25f, 0.55f); // pants
        drawBox(-0.25f, -1.375f, -0.125f,  0f,    -0.625f, 0.125f); // left leg
        drawBox( 0f,    -1.375f, -0.125f,  0.25f, -0.625f, 0.125f); // right leg
    }

    private void drawBox(float x0, float y0, float z0, float x1, float y1, float z1) {
        GL.glBegin(GL.GL_QUADS);
        // front
        GL.glVertex3f(x0,y0,z1); GL.glVertex3f(x1,y0,z1); GL.glVertex3f(x1,y1,z1); GL.glVertex3f(x0,y1,z1);
        // back
        GL.glVertex3f(x1,y0,z0); GL.glVertex3f(x0,y0,z0); GL.glVertex3f(x0,y1,z0); GL.glVertex3f(x1,y1,z0);
        // left
        GL.glVertex3f(x0,y0,z0); GL.glVertex3f(x0,y0,z1); GL.glVertex3f(x0,y1,z1); GL.glVertex3f(x0,y1,z0);
        // right
        GL.glVertex3f(x1,y0,z1); GL.glVertex3f(x1,y0,z0); GL.glVertex3f(x1,y1,z0); GL.glVertex3f(x1,y1,z1);
        // top
        GL.glVertex3f(x0,y1,z1); GL.glVertex3f(x1,y1,z1); GL.glVertex3f(x1,y1,z0); GL.glVertex3f(x0,y1,z0);
        // bottom
        GL.glVertex3f(x0,y0,z0); GL.glVertex3f(x1,y0,z0); GL.glVertex3f(x1,y0,z1); GL.glVertex3f(x0,y0,z1);
        GL.glEnd();
    }

    private void renderNameTag(float x, float y, float z, String name) {
        // billboard: face the camera by zeroing the rotation 3x3 of the modelView
        renderer.push();
        renderer.translate(x, y, z);
        renderer.getModelView(nameTagMv);
        // reset rotation 3x3 to identity (keep translation column)
        nameTagMv.m00(1).m01(0).m02(0);
        nameTagMv.m10(0).m11(1).m12(0);
        nameTagMv.m20(0).m21(0).m22(1);
        renderer.loadModelView(nameTagMv);

        int cw = 5, ch = 7, sp = 1;
        float scale = 0.01f;
        int tw = name.length() * (cw + sp);
        float hw = tw * scale / 2f;

        // dark background (3D quad, overlay pipeline so it ignores depth)
        GL.set3DQuadPipeline(Pipelines.Pipeline.OVERLAY_3D);
        GL.glColor4f(0f, 0f, 0f, 0.5f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex3f(-hw - 0.02f, -ch * scale - 0.01f, 0);
        GL.glVertex3f( hw + 0.02f, -ch * scale - 0.01f, 0);
        GL.glVertex3f( hw + 0.02f, 0.01f, 0);
        GL.glVertex3f(-hw - 0.02f, 0.01f, 0);
        GL.glEnd();

        // text
        GL.glColor4f(1f, 1f, 1f, 1f);
        renderer.translate(-hw, -ch * scale, 0);
        renderer.scale(scale, scale, scale);
        for (int i = 0; i < name.length(); i++)
            drawChar(Character.toUpperCase(name.charAt(i)), i * (cw + sp), 0, cw, ch);

        renderer.pop();
        GL.set3DQuadPipeline(Pipelines.Pipeline.WORLD_OPAQUE);
    }

    private void renderMultiplayerMenu() {
        beginOrtho();
        renderOverlay();
        drawTitle("MULTIPLAYER", height / 2 - 160);

        int btnW = 380, btnH = 60, portH = 30, gap = 12;
        int bx = (width - btnW) / 2;

        // layout: HOST btn, port field (if not hosting), JOIN btn, BACK btn
        int by1  = height / 2 - 80;
        int portY = by1 + btnH + gap;
        int by2  = server == null ? portY + portH + gap : by1 + btnH + gap;
        int by3  = by2 + btnH + gap;

        int mx = mouseScreenX(), my = mouseScreenY();
        boolean h1    = hover(mx, my, bx, by1,  btnW, btnH);
        boolean hPort = server == null && hover(mx, my, bx, portY, btnW, portH);
        boolean h2    = hover(mx, my, bx, by2,  btnW, btnH);
        boolean h3    = hover(mx, my, bx, by3,  btnW, btnH);

        String hostLabel = server != null ? "HOSTING  PORT " + server.port : "HOST GAME";
        drawButton(bx, by1, btnW, btnH, hostLabel, h1);
        if (server == null)
            drawInputField(bx, portY, btnW, portH, "PORT  " + portInput, editingPort && screen == 3);
        drawButton(bx, by2, btnW, btnH, "JOIN GAME", h2);
        drawButton(bx, by3, btnW, btnH, server != null || client != null ? "DISCONNECT" : "BACK", h3);

        if (!mpStatus.isEmpty()) {
            GL.glEnable(GL.GL_BLEND);
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            GL.glColor4f(1f, 1f, 0.4f, 1f);
            int sw = mpStatus.length() * 10;
            for (int i = 0; i < mpStatus.length(); i++)
                drawChar(mpStatus.charAt(i), (width - sw) / 2 + i * 10, by3 + btnH + 14, 8, 12);
        }

        GL.glDisable(GL.GL_BLEND);

        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (hPort) { editingPort = true; }
                else if (h1) {
                    editingPort = false;
                    if (server == null) {
                        try {
                            int p = portInput.isEmpty() ? GameServer.DEFAULT_PORT : Integer.parseInt(portInput);
                            stopMultiplayer();
                            if (level == null) startWorld("MP Host", new java.util.Random().nextLong());
                            server = new GameServer(level, p);
                            server.setHostName(nameInput.isEmpty() ? "Host" : nameInput);
                            mpStatus = "HOSTING ON PORT " + p;
                        } catch (Exception ex) {
                            mpStatus = "FAILED  " + (ex.getMessage() != null ? ex.getMessage().toUpperCase() : "ERROR");
                        }
                    }
                }
                else if (h2) { screen = 4; ipInput = ""; editingPort = false; mpStatus = ""; }
                else if (h3) {
                    if (server != null || client != null) { stopMultiplayer(); mpStatus = "DISCONNECTED"; }
                    else { screen = (level == null) ? -1 : 1; }
                }
                else editingPort = false;
            }
        }
        endOrtho();
    }

    private void renderJoinScreen() {
        beginOrtho();
        renderOverlay();
        drawTitle("JOIN GAME", height / 2 - 160);

        int fw = 320, fh = 38, gap = 8;
        int fx = (width - fw) / 2;
        int fy = height / 2 - 90;

        int mx = mouseScreenX(), my = mouseScreenY();
        boolean hName = hover(mx, my, fx, fy,              fw, fh);
        boolean hIp   = hover(mx, my, fx, fy+(fh+gap),    fw, fh);
        boolean hPort = hover(mx, my, fx, fy+(fh+gap)*2,  fw, fh);

        drawInputField(fx, fy,             fw, fh, "NAME  " + nameInput, editingName);
        drawInputField(fx, fy+(fh+gap),    fw, fh, "IP  "   + (ipInput.isEmpty() ? "" : ipInput), !editingPort && !editingName);
        drawInputField(fx, fy+(fh+gap)*2,  fw, fh, "PORT  " + portInput, editingPort);

        int by   = fy + (fh+gap)*3 + 6;
        boolean hConn = hover(mx, my, fx, by,        fw, fh);
        boolean hBack = hover(mx, my, fx, by+fh+gap, fw, fh);
        drawButton(fx, by,        fw, fh, "CONNECT", hConn);
        drawButton(fx, by+fh+gap, fw, fh, "BACK",    hBack);

        if (!mpStatus.isEmpty()) {
            GL.glEnable(GL.GL_BLEND);
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            GL.glColor4f(1f, 0.4f, 0.4f, 1f);
            for (int i = 0; i < mpStatus.length(); i++)
                drawChar(mpStatus.charAt(i), fx + i * 10, by + fh*2 + gap*2, 8, 12);
        }

        GL.glDisable(GL.GL_BLEND);
        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (hName) { editingName = true;  editingPort = false; }
                if (hIp)   { editingName = false; editingPort = false; }
                if (hPort) { editingName = false; editingPort = true;  }
                if (hConn && !ipInput.isEmpty()) doConnect();
                if (hBack) { screen = 3; mpStatus = ""; }
            }
        }
        endOrtho();
    }

    // ── Server List helpers ──────────────────────────────────────────────────

    private void refreshServerList() {
        serverList.clear();
        if (!SERVERS_FILE.exists()) return;
        try (var br = new java.io.BufferedReader(new java.io.FileReader(SERVERS_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx > 0) serverList.add(new String[]{line.substring(0, idx), line.substring(idx + 1)});
            }
        } catch (java.io.IOException ignored) {}
    }

    private void saveServerList() {
        try (var pw = new java.io.PrintWriter(new java.io.FileWriter(SERVERS_FILE))) {
            for (var s : serverList) pw.println(s[0] + "=" + s[1]);
        } catch (java.io.IOException ignored) {}
    }

    private void saveServer() {
        String name = addServerName.trim().isEmpty() ? addServerAddr : addServerName.trim();
        if (editServerIdx >= 0 && editServerIdx < serverList.size()) {
            serverList.set(editServerIdx, new String[]{name, addServerAddr});
        } else {
            serverList.add(new String[]{name, addServerAddr});
        }
        saveServerList();
        screen = 5;
        editServerIdx = -1;
    }

    private void renderServerList() {
        beginOrtho();
        renderOverlay();
        drawTitle("MULTIPLAYER", height / 2 - 160);

        int listW = 400, entryH = 40, gap = 6;
        int lx = (width - listW) / 2;
        int listTop = height / 2 - 100;
        int visCount = 4;

        int mx = mouseScreenX(), my = mouseScreenY();

        // draw server entries
        for (int i = 0; i < visCount; i++) {
            int idx = i + serverScroll;
            if (idx >= serverList.size()) break;
            String[] s = serverList.get(idx);
            int ey = listTop + i * (entryH + gap);
            boolean hov = hover(mx, my, lx, ey, listW, entryH);
            boolean sel = idx == serverSelected;

            GL.glEnable(GL.GL_BLEND);
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            GL.glColor4f(1f, 1f, 1f, sel ? 0.25f : hov ? 0.15f : 0.08f);
            GL.glBegin(GL.GL_QUADS);
            GL.glVertex2f(lx, ey); GL.glVertex2f(lx+listW, ey);
            GL.glVertex2f(lx+listW, ey+entryH); GL.glVertex2f(lx, ey+entryH);
            GL.glEnd();
            GL.glColor4f(1f, 1f, 1f, sel ? 1f : 0.4f);
            GL.glLineWidth(1f);
            GL.glBegin(GL.GL_LINE_LOOP);
            GL.glVertex2f(lx+0.5f,ey+0.5f); GL.glVertex2f(lx+listW-0.5f,ey+0.5f);
            GL.glVertex2f(lx+listW-0.5f,ey+entryH-0.5f); GL.glVertex2f(lx+0.5f,ey+entryH-0.5f);
            GL.glEnd();
            GL.glColor4f(1f, 1f, 1f, 1f);
            String label = s[0];
            for (int ci = 0; ci < Math.min(label.length(), 30); ci++)
                drawChar(label.charAt(ci), lx + 10 + ci * 10, ey + 7, 8, 12);
            GL.glColor4f(0.7f, 0.7f, 0.7f, 1f);
            String addr = s[1];
            for (int ci = 0; ci < Math.min(addr.length(), 38); ci++)
                drawChar(addr.charAt(ci), lx + 10 + ci * 8, ey + 22, 6, 10);
        }

        // empty list hint
        if (serverList.isEmpty()) {
            GL.glColor4f(0.6f, 0.6f, 0.6f, 1f);
            String hint = "NO SERVERS  ADD ONE BELOW";
            int hw = hint.length() * 10;
            for (int i = 0; i < hint.length(); i++)
                drawChar(hint.charAt(i), (width - hw) / 2 + i * 10, listTop + 14, 8, 12);
        }

        int btnY = listTop + visCount * (entryH + gap) + 10;
        int btnW = 190, btnH = 54, btnGap = 8;
        int row1x = (width - btnW * 2 - btnGap) / 2;

        boolean hJoin = serverSelected >= 0 && hover(mx, my, row1x,           btnY, btnW, btnH);
        boolean hEdit = serverSelected >= 0 && hover(mx, my, row1x+btnW+btnGap, btnY, btnW, btnH);
        drawButton(row1x,           btnY, btnW, btnH, "JOIN",  hJoin);
        drawButton(row1x+btnW+btnGap, btnY, btnW, btnH, "EDIT", hEdit);

        int btnY2 = btnY + btnH + btnGap;
        int btnW2 = 155, btnGap2 = 8;
        int row2x = (width - btnW2 * 2 - btnGap2) / 2;
        boolean hAdd    = hover(mx, my, row2x,             btnY2, btnW2, btnH);
        boolean hDirect = hover(mx, my, row2x+btnW2+btnGap2, btnY2, btnW2, btnH);
        drawButton(row2x,               btnY2, btnW2, btnH, "ADD SERVER",     hAdd);
        drawButton(row2x+btnW2+btnGap2, btnY2, btnW2, btnH, "DIRECT CONNECT", hDirect);

        int btnY3 = btnY2 + btnH + btnGap;
        boolean hBack = hover(mx, my, (width-380)/2, btnY3, 380, btnH);
        drawButton((width-380)/2, btnY3, 380, btnH, "BACK", hBack);

        // status
        if (!mpStatus.isEmpty()) {
            GL.glEnable(GL.GL_BLEND);
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            GL.glColor4f(1f, 0.4f, 0.4f, 1f);
            int sw = mpStatus.length() * 10;
            for (int i = 0; i < mpStatus.length(); i++)
                drawChar(mpStatus.charAt(i), (width - sw) / 2 + i * 10, btnY2 + btnH + 10, 8, 12);
        }

        GL.glDisable(GL.GL_BLEND);

        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                // click on entry
                for (int i = 0; i < visCount; i++) {
                    int idx = i + serverScroll;
                    if (idx >= serverList.size()) break;
                    int ey = listTop + i * (entryH + gap);
                    if (hover(mx, my, lx, ey, listW, entryH)) {
                        if (serverSelected == idx) {
                            // double-click → join
                            connectToServer(serverList.get(idx)[1]);
                        } else {
                            serverSelected = idx;
                        }
                    }
                }
                if (hJoin && serverSelected >= 0)   connectToServer(serverList.get(serverSelected)[1]);
                if (hAdd)  { editServerIdx = -1; addServerName = ""; addServerAddr = ""; editAddName = true; screen = 10; }
                if (hEdit && serverSelected >= 0) {
                    editServerIdx = serverSelected;
                    addServerName = serverList.get(serverSelected)[0];
                    addServerAddr = serverList.get(serverSelected)[1];
                    editAddName = false;
                    screen = 10;
                }
                if (hDirect) { ipInput = ""; portInput = "25565"; editingPort = false; editingName = false; mpStatus = ""; screen = 4; }
                if (hBack)   { serverSelected = -1; screen = (level == null) ? -1 : 1; }
            }
        }

        // scroll
        double scroll = Input.consumeScroll();
        if (scroll != 0) {
            serverScroll = Math.max(0, Math.min(serverScroll - (int)scroll, Math.max(0, serverList.size() - visCount)));
        }

        endOrtho();
    }

    private void connectToServer(String address) {
        String addr = address.trim();
        String ip = addr; String port = "25565";
        int colon = addr.lastIndexOf(':');
        if (colon > 0) { ip = addr.substring(0, colon); port = addr.substring(colon + 1); }
        ipInput = ip; portInput = port;
        mpStatus = "";
        doConnect();
        if (client != null) mpStatus = "";
    }

    private void renderAddServer() {
        beginOrtho();
        renderOverlay();
        drawTitle(editServerIdx >= 0 ? "EDIT SERVER" : "ADD SERVER", height / 2 - 140);

        int fw = 360, fh = 40, gap = 10;
        int fx = (width - fw) / 2;
        int fy = height / 2 - 60;

        int mx = mouseScreenX(), my = mouseScreenY();
        boolean hName = hover(mx, my, fx, fy,       fw, fh);
        boolean hAddr = hover(mx, my, fx, fy+fh+gap, fw, fh);

        drawInputField(fx, fy,        fw, fh, "NAME  " + addServerName, editAddName);
        drawInputField(fx, fy+fh+gap, fw, fh, "ADDR  " + addServerAddr, !editAddName);

        int btnW = 200, btnH = 54, btnGap = 10;
        int bx = (width - btnW * 2 - btnGap) / 2;
        int by = fy + (fh + gap) * 2 + 8;
        boolean hSave = hover(mx, my, bx,           by, btnW, btnH);
        boolean hBack = hover(mx, my, bx+btnW+btnGap, by, btnW, btnH);
        drawButton(bx,               by, btnW, btnH, "SAVE",   hSave);
        drawButton(bx+btnW+btnGap,   by, btnW, btnH, "CANCEL", hBack);

        // delete button if editing
        if (editServerIdx >= 0) {
            int dw = 120, dy = by + btnH + btnGap;
            int dx = (width - dw) / 2;
            boolean hDel = hover(mx, my, dx, dy, dw, btnH);
            drawButton(dx, dy, dw, btnH, "DELETE", hDel);
            for (var e : Input.pollMouseEvents()) {
                if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                    if (hName) editAddName = true;
                    if (hAddr) editAddName = false;
                    if (hSave && !addServerAddr.isEmpty()) saveServer();
                    if (hBack) { screen = 5; editServerIdx = -1; }
                    if (hDel)  { serverList.remove(editServerIdx); saveServerList(); serverSelected = -1; editServerIdx = -1; screen = 5; }
                }
            }
        } else {
            for (var e : Input.pollMouseEvents()) {
                if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                    if (hName) editAddName = true;
                    if (hAddr) editAddName = false;
                    if (hSave && !addServerAddr.isEmpty()) saveServer();
                    if (hBack) { screen = 5; editServerIdx = -1; }
                }
            }
        }
        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private void drawInputField(int x, int y, int w, int h, String text, boolean focused) {
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        GL.glColor4f(1f, 1f, 1f, focused ? 0.18f : 0.08f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(x, y); GL.glVertex2f(x+w, y);
        GL.glVertex2f(x+w, y+h); GL.glVertex2f(x, y+h);
        GL.glEnd();
        GL.glColor4f(1f, 1f, 1f, focused ? 1f : 0.5f);
        GL.glLineWidth(focused ? 2f : 1f);
        GL.glBegin(GL.GL_LINE_LOOP);
        GL.glVertex2f(x+0.5f, y+0.5f); GL.glVertex2f(x+w-0.5f, y+0.5f);
        GL.glVertex2f(x+w-0.5f, y+h-0.5f); GL.glVertex2f(x+0.5f, y+h-0.5f);
        GL.glEnd();
        GL.glColor4f(1f, 1f, 1f, 1f);
        for (int i = 0; i < text.length(); i++)
            drawChar(text.charAt(i), x + 10 + i * 10, y + (h - 12) / 2, 8, 12);
    }

    private void doConnect() {
        try {
            int p = portInput.isEmpty() ? GameServer.DEFAULT_PORT : Integer.parseInt(portInput);
            stopMultiplayer();
            Level connLevel = (level != null) ? level : new Level(0);
            client = new GameClient(ipInput, p, connLevel);
            client.sendName(nameInput.isEmpty() ? "Player" : nameInput);
            if (level == null) {
                level = connLevel;
                levelRenderer = new LevelRenderer(level);
                player = new Player(level);
            }
            mpStatus = "";
            screen = 0;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); Input.consumeMouseDelta();
        } catch (Exception e) {
            e.printStackTrace();
            mpStatus = e.getMessage() != null ? e.getMessage().toUpperCase() : "CONNECTION FAILED";
            client = null;
        }
    }

    private void addChat(String msg) {
        chatMessages.addLast(msg);
        chatTimes.addLast(System.currentTimeMillis());
        while (chatMessages.size() > CHAT_MAX) { chatMessages.removeFirst(); chatTimes.removeFirst(); }
    }

    private void renderChat() {
        beginOrtho();
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        int lineH = fontRenderer.glyphH + 4, maxLines = 15, pad = 4;
        int hotbarTop = height - 40 - 10;
        var msgs  = chatMessages.toArray(new String[0]);
        var times = chatTimes.toArray(new Long[0]);
        long now  = System.currentTimeMillis();
        int start = Math.max(0, msgs.length - maxLines);
        int baseY = hotbarTop - lineH * (msgs.length - start);

        for (int i = start; i < msgs.length; i++) {
            long age = now - times[i];
            if (!chatOpen && age > CHAT_FADE_MS) continue;
            float fade = (float)(CHAT_FADE_MS - age) / 1000f;
            float alpha = chatOpen ? 1.0f : Math.min(1.0f, Math.max(0f, fade));
            String line = msgs[i];
            int w = fontRenderer.stringWidth(line);
            int ly = baseY + (i - start) * lineH;
            GL.glColor4f(0, 0, 0, alpha * 0.5f);
            GL.glDisable(GL.GL_TEXTURE_2D);
            GL.glBegin(GL.GL_QUADS);
            GL.glVertex2f(pad, ly); GL.glVertex2f(pad + w + 6, ly);
            GL.glVertex2f(pad + w + 6, ly + lineH); GL.glVertex2f(pad, ly + lineH);
            GL.glEnd();
            fontRenderer.drawString(line, pad + 3, ly + 1, 1, 1, 1, alpha);
        }

        if (chatOpen) {
            String prompt = "> " + chatInput;
            int py = hotbarTop;
            GL.glDisable(GL.GL_TEXTURE_2D);
            GL.glColor4f(0, 0, 0, 0.7f);
            GL.glBegin(GL.GL_QUADS);
            GL.glVertex2f(pad, py); GL.glVertex2f(width - pad, py);
            GL.glVertex2f(width - pad, py + lineH + 4); GL.glVertex2f(pad, py + lineH + 4);
            GL.glEnd();
            fontRenderer.drawString(prompt, pad + 3, py + 2, 1, 1, 1, 1);
        }

        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private void drawSmallText(String text, int x, int y) {
        int charW = 6, charH = 8, sp = 7;
        GL.glColor4f(1,1,1,1);
        for (int i = 0; i < text.length(); i++)
            drawChar(Character.toUpperCase(text.charAt(i)), x + i * sp, y, charW, charH);
    }

    private void renderTabList() {
        if (!tabOpen) return;
        beginOrtho();

        java.util.List<String> players = new java.util.ArrayList<>();
        players.add((nameInput.isEmpty() ? "Player" : nameInput) + " (" + (player != null ? player.mode.name().charAt(0) : 'C') + ")");
        for (var rp : remotePlayers.values()) players.add(rp.name);

        int cols = players.size() > 6 ? 2 : 1;
        int rows = (players.size() + cols - 1) / cols;
        int cellW = 180, cellH = 20, cellGap = 2;
        int panW = cols * cellW + (cols + 1) * cellGap;
        int panH = 30 + rows * (cellH + cellGap) + cellGap;
        int px = (width - panW) / 2, py = 10;

        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        // background panel
        GL.glColor4f(0, 0, 0, 0.7f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(px, py); GL.glVertex2f(px + panW, py);
        GL.glVertex2f(px + panW, py + panH); GL.glVertex2f(px, py + panH);
        GL.glEnd();

        // header
        GL.glColor4f(1, 1, 1, 1);
        String header = players.size() + " player" + (players.size() != 1 ? "s" : "") + " online";
        drawSmallText(header, px + panW / 2 - header.length() * 4, py + 8);

        // player cells
        for (int i = 0; i < players.size(); i++) {
            int col = i % cols, row = i / cols;
            int cx = px + cellGap + col * (cellW + cellGap);
            int cy = py + 28 + row * (cellH + cellGap);
            GL.glColor4f(0.4f, 0.4f, 0.4f, 0.8f);
            GL.glBegin(GL.GL_QUADS);
            GL.glVertex2f(cx, cy); GL.glVertex2f(cx + cellW, cy);
            GL.glVertex2f(cx + cellW, cy + cellH); GL.glVertex2f(cx, cy + cellH);
            GL.glEnd();
            GL.glColor4f(1, 1, 1, 1);
            drawSmallText(players.get(i), cx + 6, cy + (cellH - 8) / 2);
        }

        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private byte checkCraft() {
        byte a=craftGrid[0][0], b=craftGrid[0][1], c=craftGrid[1][0], d=craftGrid[1][1];
        if (a==1 && b==1 && c==1 && d==1) return 1;
        if (a==2 && b==0 && c==0 && d==0) return 1;
        return 0;
    }

    private void renderInventory() {
        beginOrtho();
        renderOverlay();
        drawTitle("INVENTORY", height / 2 - 180);

        int slotSize = 36, gap = 4;
        int cols = 9;
        int invW = cols * (slotSize + gap) - gap;
        int invX = (width - invW) / 2, invY = height / 2 - 80;

        int mx = mouseScreenX(), my = mouseScreenY();

        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        for (int row = 0; row < 4; row++) {
            int y = invY + row * (slotSize + gap + 2) + (row == 3 ? 10 : 0);
            for (int col = 0; col < cols; col++) {
                int x = invX + col * (slotSize + gap);
                int slotIdx = row == 3 ? col : 9 + row * cols + col;
                boolean sel = slotIdx < 9 && slotIdx == selectedSlot;
                boolean hov = hover(mx, my, x, y, slotSize, slotSize);

                GL.glColor4f(1, 1, 1, sel ? 0.5f : hov ? 0.3f : 0.15f);
                GL.glBegin(GL.GL_QUADS);
                GL.glVertex2f(x, y); GL.glVertex2f(x + slotSize, y);
                GL.glVertex2f(x + slotSize, y + slotSize); GL.glVertex2f(x, y + slotSize);
                GL.glEnd();
                GL.glColor4f(1, 1, 1, sel || hov ? 1f : 0.4f);
                GL.glLineWidth(sel || hov ? 2f : 1f);
                GL.glBegin(GL.GL_LINE_LOOP);
                GL.glVertex2f(x + 0.5f, y + 0.5f); GL.glVertex2f(x + slotSize - 0.5f, y + 0.5f);
                GL.glVertex2f(x + slotSize - 0.5f, y + slotSize - 0.5f); GL.glVertex2f(x + 0.5f, y + slotSize - 0.5f);
                GL.glEnd();
            }
        }

        // crafting button
        int btnY = invY + 4 * (slotSize + gap + 2) + 10 + 20;
        boolean hCraft = hover(mx, my, (width - 120) / 2, btnY, 120, 30);
        drawButton((width - 120) / 2, btnY, 120, 30, "CRAFT", hCraft);

        // close button
        int closeY = btnY + 40;
        boolean hClose = hover(mx, my, (width - 120) / 2, closeY, 120, 30);
        drawButton((width - 120) / 2, closeY, 120, 30, "CLOSE", hClose);

        GL.glDisable(GL.GL_BLEND);

        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (hCraft) { screen = 7; }
                if (hClose) { screen = 0; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); Input.consumeMouseDelta(); }
            }
        }
        endOrtho();
    }

    private void renderCrafting() {
        beginOrtho();
        renderOverlay();
        drawTitle("CRAFTING", height / 2 - 120);

        int slotSize = 44, gap = 4;
        int gridX = width / 2 - slotSize - gap - 30;
        int gridY = height / 2 - slotSize - gap / 2;
        int mx = mouseScreenX(), my = mouseScreenY();

        craftOutput = checkCraft();

        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        // draw 2x2 craft grid
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                int sx = gridX + col * (slotSize + gap);
                int sy = gridY + row * (slotSize + gap);
                boolean hov = hover(mx, my, sx, sy, slotSize, slotSize);
                GL.glColor4f(1,1,1, hov ? 0.3f : 0.15f);
                GL.glBegin(GL.GL_QUADS);
                GL.glVertex2f(sx,sy); GL.glVertex2f(sx+slotSize,sy);
                GL.glVertex2f(sx+slotSize,sy+slotSize); GL.glVertex2f(sx,sy+slotSize);
                GL.glEnd();
                GL.glColor4f(1,1,1,0.8f);
                GL.glBegin(GL.GL_LINE_LOOP);
                GL.glVertex2f(sx+0.5f,sy+0.5f); GL.glVertex2f(sx+slotSize-0.5f,sy+0.5f);
                GL.glVertex2f(sx+slotSize-0.5f,sy+slotSize-0.5f); GL.glVertex2f(sx+0.5f,sy+slotSize-0.5f);
                GL.glEnd();
                byte block = craftGrid[row][col];
                if (block != 0) drawSmallText(String.valueOf(block), sx + 4, sy + 4);
            }
        }

        // arrow
        int arrowX = gridX + 2 * (slotSize + gap) + 10;
        int arrowY = gridY + slotSize / 2 + gap / 2;
        drawSmallText(">", arrowX, arrowY - 4);

        // output slot
        int outX = arrowX + 30, outY = gridY + gap / 2;
        boolean hOut = hover(mx, my, outX, outY, slotSize, slotSize * 2 + gap);
        GL.glColor4f(1,1,1, hOut && craftOutput != 0 ? 0.4f : 0.15f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(outX, outY); GL.glVertex2f(outX+slotSize, outY);
        GL.glVertex2f(outX+slotSize, outY+slotSize); GL.glVertex2f(outX, outY+slotSize);
        GL.glEnd();
        GL.glColor4f(1,1,1,0.8f);
        GL.glBegin(GL.GL_LINE_LOOP);
        GL.glVertex2f(outX+0.5f,outY+0.5f); GL.glVertex2f(outX+slotSize-0.5f,outY+0.5f);
        GL.glVertex2f(outX+slotSize-0.5f,outY+slotSize-0.5f); GL.glVertex2f(outX+0.5f,outY+slotSize-0.5f);
        GL.glEnd();
        if (craftOutput != 0) drawSmallText(String.valueOf(craftOutput), outX + 4, outY + 4);

        GL.glDisable(GL.GL_BLEND);

        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 2; col++) {
                        int sx = gridX + col * (slotSize + gap);
                        int sy = gridY + row * (slotSize + gap);
                        if (hover(mx, my, sx, sy, slotSize, slotSize)) {
                            craftGrid[row][col] = (byte)((craftGrid[row][col] + 1) % 4);
                        }
                    }
                }
                if (hOut && craftOutput != 0) {
                    hotbar[selectedSlot] = craftOutput;
                    craftGrid = new byte[2][2];
                    craftOutput = 0;
                }
            }
        }
        endOrtho();
    }

    private void renderHud() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - fpsLastTime >= 1000) {
            displayFps = frameCount;
            frameCount = 0;
            fpsLastTime = now;
        }

        beginOrtho();
        String text = "FPS " + displayFps;
        int x = 10, y = 10;
        int charW = 12, charH = 16;
        GL.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        for (int i = 0; i < text.length(); i++) {
            drawChar(text.charAt(i), x + i * (charW + 3), y, charW, charH);
        }
        endOrtho();
    }

    private void renderHotbar() {
        beginOrtho();
        int slots = 9, slotSize = 40, gap = 2;
        int totalW = slots * (slotSize + gap) - gap;
        int startX = (width - totalW) / 2, y = height - slotSize - 10;
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        for (int i = 0; i < slots; i++) {
            int x = startX + i * (slotSize + gap);
            boolean sel = i == selectedSlot;
            GL.glColor4f(1, 1, 1, sel ? 0.5f : 0.2f);
            GL.glBegin(GL.GL_QUADS);
            GL.glVertex2f(x, y); GL.glVertex2f(x + slotSize, y);
            GL.glVertex2f(x + slotSize, y + slotSize); GL.glVertex2f(x, y + slotSize);
            GL.glEnd();
            GL.glColor4f(1, 1, 1, sel ? 1f : 0.5f);
            GL.glLineWidth(sel ? 2f : 1f);
            GL.glBegin(GL.GL_LINE_LOOP);
            GL.glVertex2f(x + 0.5f, y + 0.5f); GL.glVertex2f(x + slotSize - 0.5f, y + 0.5f);
            GL.glVertex2f(x + slotSize - 0.5f, y + slotSize - 0.5f); GL.glVertex2f(x + 0.5f, y + slotSize - 0.5f);
            GL.glEnd();
        }
        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private void renderCrosshair() {
        beginOrtho();
        int cx = width / 2, cy = height / 2;
        int s = 10, t = 2;
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_ONE_MINUS_DST_COLOR, GL.GL_ZERO);
        GL.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL.glBegin(GL.GL_QUADS);
        // horizontal
        GL.glVertex2f(cx - s, cy - t); GL.glVertex2f(cx + s, cy - t);
        GL.glVertex2f(cx + s, cy + t); GL.glVertex2f(cx - s, cy + t);
        // vertical
        GL.glVertex2f(cx - t, cy - s); GL.glVertex2f(cx + t, cy - s);
        GL.glVertex2f(cx + t, cy + s); GL.glVertex2f(cx - t, cy + s);
        GL.glEnd();
        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private void renderOverlay() {
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        GL.glColor4f(0.0f, 0.0f, 0.0f, 0.6f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(0, 0); GL.glVertex2f(width, 0);
        GL.glVertex2f(width, height); GL.glVertex2f(0, height);
        GL.glEnd();
    }

    /** Open (and create if missing) a named world folder. */
    private void startWorld(String name, long seed) {
        worldName = name;
        worldSeedValue = seed;
        loadingStatus = "GENERATING WORLD...";
        loadingDone = false;
        screen = 11;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        Thread t = new Thread(() -> {
            java.io.File dir = worldDir(name);
            dir.mkdirs();
            level = new Level(seed);
            if (new java.io.File(dir, "seed.dat").exists()) {
                loadingStatus = "LOADING WORLD...";
                level.load(dir);
                worldSeedValue = level.getSeed();
            }
            loadingStatus = "GENERATING TERRAIN...";
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++)
                    level.getOrLoadChunk(dx, dz);
            level.update(0, 0, 8);
            loadingStatus = "SPAWNING...";
            loadingDone = true;
        }, "world-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Save the active world and return to the main menu, fully tearing down level state. */
    private void exitToMenu() {
        if (level != null && !worldName.isEmpty()) level.save(worldDir(worldName));
        stopMultiplayer();
        level = null;
        levelRenderer = null;
        player = null;
        particles.clear();
        worldName = "";
        screen = -1;
        menuCooldown = 3;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        Input.pollMouseEvents();
        Input.consumeMouseDelta();
    }

    private static java.io.File worldDir(String name) {
        return new java.io.File(SAVES_DIR, sanitize(name));
    }

    private static String sanitize(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
        return s.isEmpty() ? "World" : s;
    }

    private void refreshWorldList() {
        worldList.clear();
        SAVES_DIR.mkdirs();
        java.io.File[] dirs = SAVES_DIR.listFiles(java.io.File::isDirectory);
        if (dirs != null) {
            java.util.Arrays.sort(dirs, java.util.Comparator.comparingLong(java.io.File::lastModified).reversed());
            for (java.io.File d : dirs)
                if (new java.io.File(d, "seed.dat").exists()) worldList.add(d.getName());
        }
    }

    private void createWorld() {
        String name = createName.trim().isEmpty() ? "New World" : createName.trim();
        // ensure unique folder name
        String base = sanitize(name), unique = base; int n = 1;
        while (worldDir(unique).exists()) unique = base + "_" + (++n);
        long seed = createSeed.trim().isEmpty()
            ? new java.util.Random().nextLong()
            : (createSeed.matches("-?\\d+") ? Long.parseLong(createSeed.trim()) : createSeed.hashCode());
        startWorld(unique, seed);
    }

    private void deleteWorld(String name) {
        java.io.File dir = worldDir(name);
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) f.delete();
        dir.delete();
        refreshWorldList();
    }

    private void renderLoadingScreen() {
        beginOrtho();
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        // progress bar background
        int barW = 400, barH = 12;
        int barX = (width - barW) / 2, barY = height / 2 + 20;
        GL.glColor4f(1f, 1f, 1f, 0.15f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(barX, barY); GL.glVertex2f(barX+barW, barY);
        GL.glVertex2f(barX+barW, barY+barH); GL.glVertex2f(barX, barY+barH);
        GL.glEnd();
        // animated fill
        float fill = (System.currentTimeMillis() % 2000) / 2000f;
        GL.glColor4f(0.3f, 0.7f, 1f, 0.9f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(barX, barY); GL.glVertex2f(barX + barW * fill, barY);
        GL.glVertex2f(barX + barW * fill, barY+barH); GL.glVertex2f(barX, barY+barH);
        GL.glEnd();

        drawTitle("RUBYDUNG", height / 2 - 80);

        GL.glColor4f(0.8f, 0.8f, 0.8f, 1f);
        String s = loadingStatus;
        int sw = s.length() * 10;
        for (int i = 0; i < s.length(); i++)
            drawChar(s.charAt(i), (width - sw) / 2 + i * 10, height / 2 + 42, 8, 12);

        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private void renderMainMenu() {
        beginOrtho();

        // background quad (sky tinted)
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        GL.glColor4f(0f, 0f, 0f, 0.35f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(0, 0); GL.glVertex2f(width, 0);
        GL.glVertex2f(width, height); GL.glVertex2f(0, height);
        GL.glEnd();

        // logo shadow
        int logoY = height / 2 - 200;
        int cw = 28, ch = 38, sp = 5;
        String logo = "RUBYDUNG";
        int tw = logo.length() * (cw + sp);
        GL.glColor4f(0.1f, 0.1f, 0.1f, 0.8f);
        for (int i = 0; i < logo.length(); i++)
            drawChar(logo.charAt(i), (width - tw) / 2 + i * (cw + sp) + 3, logoY + 3, cw, ch);
        GL.glColor4f(1.0f, 0.85f, 0.3f, 1.0f);
        for (int i = 0; i < logo.length(); i++)
            drawChar(logo.charAt(i), (width - tw) / 2 + i * (cw + sp), logoY, cw, ch);

        int btnW = 380, btnH = 60, gap = 12;
        int bx = (width - btnW) / 2;
        int by0 = logoY + ch + 50;

        int mx = mouseScreenX(), my = mouseScreenY();
        boolean h0 = hover(mx, my, bx, by0,              btnW, btnH);
        boolean h2 = hover(mx, my, bx, by0+btnH+gap,     btnW, btnH);
        boolean h3 = hover(mx, my, bx, by0+(btnH+gap)*2, btnW, btnH);
        boolean h4 = hover(mx, my, bx, by0+(btnH+gap)*3, btnW, btnH);

        drawButton(bx, by0,              btnW, btnH, "SINGLEPLAYER", h0);
        drawButton(bx, by0+btnH+gap,     btnW, btnH, "MULTIPLAYER",  h2);
        drawButton(bx, by0+(btnH+gap)*2, btnW, btnH, "SETTINGS",     h3);
        drawButton(bx, by0+(btnH+gap)*3, btnW, btnH, "QUIT",         h4);

        // version strings
        GL.glColor4f(1f, 1f, 1f, 0.7f);
        String ver = "RUBYDUNG ALPHA 0.1";
        for (int i = 0; i < ver.length(); i++)
            drawChar(ver.charAt(i), 8 + i * 11, height - 22, 8, 12);

        var menuEvents = Input.pollMouseEvents();
        if (menuCooldown > 0) { menuCooldown--; menuEvents.clear(); }
        for (var e : menuEvents) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (h0) { refreshWorldList(); screen = 8; menuCooldown = 2; }
                else if (h2) {
                    refreshServerList();
                    screen = 5;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    Input.consumeMouseDelta();
                }
                else if (h3) {
                    screen = 2;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    Input.consumeMouseDelta();
                }
                else if (h4) shouldQuit = true;
            }
        }

        GL.glDisable(GL.GL_BLEND);
        endOrtho();
    }

    private void renderWorldSelect() {
        beginOrtho();
        renderOverlay();
        drawTitle("SELECT WORLD", 40);

        int listW = Math.min(440, width - 80);
        int rowH = 40, gap = 8;
        int bx = (width - listW) / 2;
        int top = 110;
        int maxRows = Math.max(1, (height - top - 140) / (rowH + gap));
        int mx = mouseScreenX(), my = mouseScreenY();

        int shown = Math.min(maxRows, worldList.size() - worldScroll);
        int clickedOpen = -1, clickedDel = -1;
        for (int i = 0; i < shown; i++) {
            int idx = worldScroll + i;
            int ry = top + i * (rowH + gap);
            boolean hRow = hover(mx, my, bx, ry, listW - rowH - gap, rowH);
            boolean hDel = hover(mx, my, bx + listW - rowH, ry, rowH, rowH);
            drawButton(bx, ry, listW - rowH - gap, rowH, worldList.get(idx), hRow);
            drawButton(bx + listW - rowH, ry, rowH, rowH, "X", hDel);
            if (hRow) clickedOpen = idx;
            if (hDel) clickedDel = idx;
        }
        if (worldList.isEmpty()) {
            GL.glColor4f(1f, 1f, 1f, 0.7f);
            drawSmallText("NO WORLDS YET - CREATE ONE", bx + 20, top + 10);
        }

        int btnW = 200, btnH = 44, by = height - 70;
        boolean hNew  = hover(mx, my, width/2 - btnW - 10, by, btnW, btnH);
        boolean hBack = hover(mx, my, width/2 + 10,        by, btnW, btnH);
        drawButton(width/2 - btnW - 10, by, btnW, btnH, "CREATE NEW", hNew);
        drawButton(width/2 + 10,        by, btnW, btnH, "BACK", hBack);

        GL.glDisable(GL.GL_BLEND);

        double scroll = Input.consumeScroll();
        if (scroll != 0 && worldList.size() > maxRows) {
            worldScroll = Math.clamp(worldScroll - (int) Math.signum(scroll), 0, worldList.size() - maxRows);
        }

        var events = Input.pollMouseEvents();
        if (menuCooldown > 0) { menuCooldown--; events.clear(); }
        for (var e : events) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (hNew)  { createName = ""; createSeed = ""; editCreateName = true; screen = 9; menuCooldown = 2; }
                else if (hBack) { screen = -1; menuCooldown = 2; }
                else if (clickedDel >= 0) { deleteWorld(worldList.get(clickedDel)); }
                else if (clickedOpen >= 0) { startWorld(worldList.get(clickedOpen), 0); }
            }
        }
        endOrtho();
    }

    private void renderCreateWorld() {
        beginOrtho();
        renderOverlay();
        drawTitle("CREATE WORLD", 60);

        int fw = Math.min(420, width - 80);
        int fx = (width - fw) / 2;
        int mx = mouseScreenX(), my = mouseScreenY();

        int nameY = height / 2 - 70;
        int seedY = height / 2;
        GL.glColor4f(1f, 1f, 1f, 0.8f);
        drawSmallText("WORLD NAME", fx, nameY - 18);
        drawInputField(fx, nameY, fw, 34, createName.isEmpty() ? "NEW WORLD" : createName, editCreateName);
        drawSmallText("SEED  (BLANK = RANDOM)", fx, seedY - 18);
        drawInputField(fx, seedY, fw, 34, createSeed, !editCreateName);

        int btnW = 200, btnH = 44, by = height / 2 + 80;
        boolean hCreate = hover(mx, my, width/2 - btnW - 10, by, btnW, btnH);
        boolean hBack   = hover(mx, my, width/2 + 10,        by, btnW, btnH);
        drawButton(width/2 - btnW - 10, by, btnW, btnH, "CREATE", hCreate);
        drawButton(width/2 + 10,        by, btnW, btnH, "BACK", hBack);

        boolean hName = hover(mx, my, fx, nameY, fw, 34);
        boolean hSeed = hover(mx, my, fx, seedY, fw, 34);

        GL.glDisable(GL.GL_BLEND);

        var events = Input.pollMouseEvents();
        if (menuCooldown > 0) { menuCooldown--; events.clear(); }
        for (var e : events) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (hName) editCreateName = true;
                else if (hSeed) editCreateName = false;
                else if (hCreate) createWorld();
                else if (hBack) { refreshWorldList(); screen = 8; menuCooldown = 2; }
            }
        }
        endOrtho();
    }

    private void renderPauseMenu() {
        beginOrtho();
        renderOverlay();

        drawTitle("PAUSE", height / 2 - 140);

        int btnW = 380, btnH = 60, gap = 16;
        int bx = (width - btnW) / 2;
        int by1 = height / 2 - 80;
        int by2 = by1 + btnH + gap;
        int by3 = by2 + btnH + gap;
        int by4 = by3 + btnH + gap;
        int by5 = by4 + btnH + gap;

        int mx = mouseScreenX(), my = mouseScreenY();
        boolean h1 = hover(mx, my, bx, by1, btnW, btnH);
        boolean h2 = hover(mx, my, bx, by2, btnW, btnH);
        boolean h3 = hover(mx, my, bx, by3, btnW, btnH);
        boolean h4 = hover(mx, my, bx, by4, btnW, btnH);
        boolean h5 = hover(mx, my, bx, by5, btnW, btnH);

        drawButton(bx, by1, btnW, btnH, "CONTINUE", h1);
        drawButton(bx, by2, btnW, btnH, "MULTIPLAYER", h2);
        drawButton(bx, by3, btnW, btnH, "SETTINGS", h3);
        drawButton(bx, by4, btnW, btnH, "SAVE & QUIT TO MENU", h4);
        drawButton(bx, by5, btnW, btnH, "QUIT GAME", h5);

        GL.glDisable(GL.GL_BLEND);

        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (h1) { screen = 0; glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); Input.consumeMouseDelta(); }
                if (h2) { refreshServerList(); screen = 5; }
                if (h3) { screen = 2; }
                if (h4) { exitToMenu(); }
                if (h5) { if (level != null && !worldName.isEmpty()) level.save(worldDir(worldName)); shouldQuit = true; }
            }
        }
        endOrtho();
    }

    private void renderSettingsMenu() {
        beginOrtho();
        renderOverlay();

        boolean inGame = level != null;
        // rows: vsync, fullscreen, resolution, fov, dist, sensitivity, [gamemode, seed if in-game], back
        int rows = inGame ? 9 : 7;

        // scale layout to fit the current window height
        int btnW = Math.min(420, width - 60);
        int titleH = 40;
        int avail = height - titleH - 30;
        int unit = Math.max(20, Math.min(62, avail / rows)); // row pitch
        int btnH = (int) (unit * 0.74f);
        int gap  = unit - btnH;
        int totalH = unit * rows;
        int by = Math.max(titleH + 16, (height - totalH) / 2);
        int bx = (width - btnW) / 2;

        drawTitle("SETTINGS", Math.max(8, by - 44));

        int mx = mouseScreenX(), my = mouseScreenY();
        int r = 0;
        boolean hVsync = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        boolean hFull  = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        boolean hRes   = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        boolean hFov   = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        boolean hDist  = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        boolean hSens  = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        boolean hMode = false, hSeed = false;
        if (inGame) {
            hMode = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
            hSeed = hover(mx, my, bx, by + unit*r, btnW, btnH); r++;
        }
        boolean hBack  = hover(mx, my, bx, by + unit*r, btnW, btnH);

        r = 0;
        drawButton(bx, by + unit*r++, btnW, btnH, "VSYNC  "       + (settings.vsync ? "ON" : "OFF"),      hVsync);
        drawButton(bx, by + unit*r++, btnW, btnH, "FULLSCREEN  "  + (settings.fullscreen ? "ON" : "OFF"), hFull);
        drawButton(bx, by + unit*r++, btnW, btnH, "RESOLUTION  "  + Settings.resLabel(settings.resIndex), hRes);
        drawButton(bx, by + unit*r++, btnW, btnH, "FOV  "         + Settings.FOV_LABELS[settings.fovIndex], hFov);
        drawButton(bx, by + unit*r++, btnW, btnH, "RENDER DIST  " + Settings.DIST_LABELS[settings.renderDist], hDist);
        drawButton(bx, by + unit*r++, btnW, btnH, "SENSITIVITY  " + Settings.SENS_LABELS[settings.sensitivity], hSens);
        if (inGame) {
            drawButton(bx, by + unit*r++, btnW, btnH, "MODE  " + player.mode.name(), hMode);
            drawButton(bx, by + unit*r++, btnW, btnH, "SEED  " + worldSeedValue, hSeed);
        }
        drawButton(bx, by + unit*r, btnW, btnH, "BACK", hBack);

        GL.glDisable(GL.GL_BLEND);

        for (var e : Input.pollMouseEvents()) {
            if (e[0] == GLFW_MOUSE_BUTTON_1 && e[1] == GLFW_PRESS) {
                if (hVsync) { settings.vsync = !settings.vsync; applyVsync(); settings.save(); }
                if (hFull)  { settings.fullscreen = !settings.fullscreen; applyDisplayMode(); settings.save(); }
                if (hRes)   { settings.resIndex = (settings.resIndex + 1) % Settings.RESOLUTIONS.length; applyDisplayMode(); settings.save(); }
                if (hFov)   { settings.fovIndex   = (settings.fovIndex   + 1) % Settings.FOV_VALUES.length;   settings.save(); }
                if (hDist)  { settings.renderDist  = (settings.renderDist  + 1) % Settings.DIST_END.length;   settings.save(); }
                if (hSens)  { settings.sensitivity = (settings.sensitivity + 1) % Settings.SENS_VALUES.length; settings.save(); }
                if (hMode)  { var m = Player.GameMode.values(); player.mode = m[(player.mode.ordinal() + 1) % m.length]; }
                if (hBack)  { screen = (level == null) ? -1 : 1; if (level == null) glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL); }
            }
        }
        endOrtho();
    }

    private int mouseScreenX() { return winWidth  > 0 ? (int)(Input.mouseX * width  / winWidth)  : (int)Input.mouseX; }
    private int mouseScreenY() { return winHeight > 0 ? (int)(Input.mouseY * height / winHeight) : (int)Input.mouseY; }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawTitle(String title, int y) {
        int cw = 30, ch = 42, sp = 5;
        int tw = title.length() * (cw + sp);
        GL.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        for (int i = 0; i < title.length(); i++)
            drawChar(title.charAt(i), (width - tw) / 2 + i * (cw + sp), y, cw, ch);
    }

    private void drawButton(int x, int y, int w, int h, String label, boolean hover) {
        GL.glEnable(GL.GL_BLEND);
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        // fill
        if (hover) GL.glColor4f(1.0f, 1.0f, 1.0f, 0.18f);
        else       GL.glColor4f(1.0f, 1.0f, 1.0f, 0.08f);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(x,     y);
        GL.glVertex2f(x + w, y);
        GL.glVertex2f(x + w, y + h);
        GL.glVertex2f(x,     y + h);
        GL.glEnd();

        // border
        GL.glColor4f(1.0f, 1.0f, 1.0f, hover ? 1.0f : 0.6f);
        GL.glLineWidth(hover ? 2.0f : 1.0f);
        GL.glBegin(GL.GL_LINE_LOOP);
        GL.glVertex2f(x + 0.5f,     y + 0.5f);
        GL.glVertex2f(x + w - 0.5f, y + 0.5f);
        GL.glVertex2f(x + w - 0.5f, y + h - 0.5f);
        GL.glVertex2f(x + 0.5f,     y + h - 0.5f);
        GL.glEnd();

        // label
        int charW = 18, charH = 26;
        int spacing = charW + 3;
        int textW = label.length() * spacing;
        GL.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        for (int i = 0; i < label.length(); i++) {
            drawChar(label.charAt(i), x + (w - textW) / 2 + i * spacing, y + (h - charH) / 2, charW, charH);
        }
    }

    private void beginOrtho() {
        renderer.push();
        renderer.getProjection(savedProj);
        renderer.setOrtho(width, height);
        renderer.loadIdentity();
        renderer.disableFog();
        GL.setOrtho(true);
    }

    private void endOrtho() {
        GL.setOrtho(false);
        renderer.setProjection(savedProj);
        renderer.pop();
    }

    /** 5x7 bitmap font. Bit 4 = leftmost pixel. */
    private static final int[][] GLYPHS = {
        {0b11111, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11111}, // 0
        {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // 1
        {0b11111, 0b00001, 0b00001, 0b11111, 0b10000, 0b10000, 0b11111}, // 2
        {0b11111, 0b00001, 0b00001, 0b11111, 0b00001, 0b00001, 0b11111}, // 3
        {0b10001, 0b10001, 0b10001, 0b11111, 0b00001, 0b00001, 0b00001}, // 4
        {0b11111, 0b10000, 0b10000, 0b11111, 0b00001, 0b00001, 0b11111}, // 5
        {0b11111, 0b10000, 0b10000, 0b11111, 0b10001, 0b10001, 0b11111}, // 6
        {0b11111, 0b00001, 0b00001, 0b00011, 0b00010, 0b00100, 0b00100}, // 7
        {0b11111, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b11111}, // 8
        {0b11111, 0b10001, 0b10001, 0b11111, 0b00001, 0b00001, 0b11111}, // 9
        {0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}, // A
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110}, // B
        {0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111}, // C
        {0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110}, // D
        {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111}, // E
        {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000}, // F
        {0b01111, 0b10000, 0b10000, 0b10011, 0b10001, 0b10001, 0b01111}, // G
        {0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}, // H
        {0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // I
        {0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100}, // J
        {0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001}, // K
        {0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111}, // L
        {0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001}, // M
        {0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001}, // N
        {0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}, // O
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000}, // P
        {0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101}, // Q
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001}, // R
        {0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110}, // S
        {0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100}, // T
        {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}, // U
        {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100}, // V
        {0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010}, // W
        {0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001}, // X
        {0b10001, 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100}, // Y
        {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111}, // Z
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000}, // ' '
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100}, // '.'
        {0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100}, // '!'
        {0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000}, // '-'
        {0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000}, // ':'
        {0b00001, 0b00010, 0b00100, 0b00100, 0b01000, 0b10000, 0b00000}, // '/'
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00100, 0b00100}, // ',' (approx)
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111}, // '_'
    };
    // Direct char→glyph index lookup. -1 = unsupported character.
    private static final int[] GLYPH_INDEX;
    static {
        char[] keys = {
            '0','1','2','3','4','5','6','7','8','9',
            'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','X','Y','Z',
            ' ','.','!','-',':','/',',','_'
        };
        int maxChar = 0;
        for (char k : keys) if (k > maxChar) maxChar = k;
        GLYPH_INDEX = new int[maxChar + 1];
        java.util.Arrays.fill(GLYPH_INDEX, -1);
        for (int i = 0; i < keys.length; i++) GLYPH_INDEX[keys[i]] = i;
    }

    private void drawChar(char c, int x, int y, int w, int h) {
        if (c >= 'a' && c <= 'z') c -= 32; // fold lowercase to the uppercase glyph
        int glyph = (c < GLYPH_INDEX.length) ? GLYPH_INDEX[c] : -1;
        if (glyph < 0) {
            // unknown glyph (e.g. Cyrillic): draw nothing rather than a black box
            return;
        }

        int pixW = Math.max(w / 5, 1);
        int pixH = Math.max(h / 7, 1);

        GL.glBegin(GL.GL_QUADS);
        for (int row = 0; row < 7; row++) {
            for (int col = 0; col < 5; col++) {
                if ((GLYPHS[glyph][row] & (1 << (4 - col))) != 0) {
                    float px = x + col * pixW;
                    float py = y + row * pixH;
                    GL.glVertex2f(px,          py);
                    GL.glVertex2f(px + pixW,   py);
                    GL.glVertex2f(px + pixW,   py + pixH);
                    GL.glVertex2f(px,          py + pixH);
                }
            }
        }
        GL.glEnd();
    }

    public static void main(String[] args) {
        new RubyDung().run();
    }
}
