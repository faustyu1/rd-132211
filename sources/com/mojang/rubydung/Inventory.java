package com.mojang.rubydung;

/**
 * The player's item slots, laid out the way Minecraft lays them out: slots 0..8 are the
 * hotbar and 9..35 are the three rows above it.
 *
 * Each slot is one stack — an id plus a count — rather than the flat "how many of id X do
 * I own" tally this used to be. That is what makes the inventory screen mean anything: the
 * same block can sit in two slots, a stack can be split, and a fresh world starts with
 * every slot genuinely empty instead of with a pre-filled hotbar.
 */
public final class Inventory {
    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_SIZE   = 27;
    public static final int SIZE        = HOTBAR_SIZE + MAIN_SIZE;

    private final int[] ids    = new int[SIZE];
    private final int[] counts = new int[SIZE];

    /** Id in the slot, or 0 when it is empty. Ids and counts are kept consistent by {@link #set}. */
    public int id(int slot)    { return ids[slot]; }
    public int count(int slot) { return counts[slot]; }
    public boolean isEmpty(int slot) { return ids[slot] == 0; }

    public void set(int slot, int id, int count) {
        if (id == 0 || count <= 0) { ids[slot] = 0; counts[slot] = 0; }
        else { ids[slot] = id; counts[slot] = Math.min(count, Items.maxStack(id)); }
    }

    public void clear() {
        java.util.Arrays.fill(ids, 0);
        java.util.Arrays.fill(counts, 0);
    }

    /**
     * Put {@code n} of {@code id} away, topping up partial stacks before taking empty slots
     * (and the hotbar before the rows above it, so a pickup lands somewhere reachable).
     * Returns what did not fit.
     */
    public int add(int id, int n) {
        if (id == 0 || n <= 0) return 0;
        int max = Items.maxStack(id);
        for (int i = 0; i < SIZE && n > 0; i++) {
            if (ids[i] != id || counts[i] >= max) continue;
            int room = max - counts[i];
            int put = Math.min(room, n);
            counts[i] += put;
            n -= put;
        }
        for (int i = 0; i < SIZE && n > 0; i++) {
            if (ids[i] != 0) continue;
            int put = Math.min(max, n);
            ids[i] = id; counts[i] = put;
            n -= put;
        }
        return n;
    }

    /** Take one off a slot, emptying it when it runs out. */
    public void shrink(int slot) {
        if (ids[slot] == 0) return;
        if (--counts[slot] <= 0) { ids[slot] = 0; counts[slot] = 0; }
    }

    /** How many of {@code id} are held across every slot. */
    public int total(int id) {
        int t = 0;
        for (int i = 0; i < SIZE; i++) if (ids[i] == id) t += counts[i];
        return t;
    }

    /** Spend {@code n} of {@code id}, drawing from the smallest stacks first. Caller checks stock. */
    public void remove(int id, int n) {
        for (int i = SIZE - 1; i >= 0 && n > 0; i--) {
            if (ids[i] != id) continue;
            int take = Math.min(counts[i], n);
            counts[i] -= take;
            n -= take;
            if (counts[i] <= 0) { ids[i] = 0; counts[i] = 0; }
        }
    }

    /** Totals indexed by {@code id & 0xFF}, the shape {@link Items#canCraft} wants. */
    public int[] countsById() {
        int[] c = new int[256];
        for (int i = 0; i < SIZE; i++) if (ids[i] != 0) c[ids[i] & 0xFF] += counts[i];
        return c;
    }

    /** True when {@code n} of {@code id} would fit somewhere — crafting refuses to overflow. */
    public boolean hasRoomFor(int id, int n) {
        int max = Items.maxStack(id);
        int room = 0;
        for (int i = 0; i < SIZE && room < n; i++) {
            if (ids[i] == 0) room += max;
            else if (ids[i] == id) room += max - counts[i];
        }
        return room >= n;
    }

    public void write(java.io.DataOutputStream dos) throws java.io.IOException {
        dos.writeInt(SIZE);
        for (int i = 0; i < SIZE; i++) { dos.writeInt(ids[i]); dos.writeInt(counts[i]); }
    }

    public void read(java.io.DataInputStream dis) throws java.io.IOException {
        clear();
        int n = dis.readInt();
        for (int i = 0; i < n; i++) {
            int id = dis.readInt(), c = dis.readInt();
            if (i < SIZE) set(i, id, c);
        }
    }
}
