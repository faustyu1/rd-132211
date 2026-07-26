package com.mojang.rubydung.level;

public interface LevelListener {
    /** urgent = player/network edit that must not lag a frame; background edits (fluids) pass false. */
    void tileChanged(int x, int y, int z, boolean urgent);
    void allChanged();
}
