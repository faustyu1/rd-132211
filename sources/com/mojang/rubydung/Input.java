package com.mojang.rubydung;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

public class Input {
    private static final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private static final List<int[]> keyEvents = new ArrayList<>();   // {key, action}
    private static final List<Character> charEvents = new ArrayList<>();
    private static final List<int[]> mouseEvents = new ArrayList<>();  // {button, action}
    private static final boolean[] mouseButtons = new boolean[8];
    public static double mouseX, mouseY;
    public static double mouseDX, mouseDY;
    public static double scrollY;
    private static double lastMouseX = Double.NaN, lastMouseY = Double.NaN;

    public static void onKey(int key, int action) {
        if (key >= 0 && key <= GLFW_KEY_LAST) keys[key] = (action != GLFW_RELEASE);
        keyEvents.add(new int[]{key, action});
    }

    public static void onChar(char c) { charEvents.add(c); }

    public static void onScroll(double dy) { scrollY += dy; }
    public static double consumeScroll() { double s = scrollY; scrollY = 0; return s; }

    public static void onMouseButton(int button, int action) {
        if (button >= 0 && button < mouseButtons.length) mouseButtons[button] = (action != GLFW_RELEASE);
        mouseEvents.add(new int[]{button, action});
    }

    public static boolean isMouseDown(int button) {
        return button >= 0 && button < mouseButtons.length && mouseButtons[button];
    }

    public static void onCursorPos(double x, double y) {
        if (Double.isNaN(lastMouseX)) { lastMouseX = x; lastMouseY = y; }
        mouseDX += x - lastMouseX;
        mouseDY += y - lastMouseY;
        lastMouseX = x; lastMouseY = y;
        mouseX = x; mouseY = y;
    }

    public static boolean isKeyDown(int key) {
        return key >= 0 && key <= GLFW_KEY_LAST && keys[key];
    }

    public static List<int[]> pollKeyEvents()   { var r = new ArrayList<>(keyEvents);   keyEvents.clear();   return r; }
    public static List<Character> pollCharEvents() { var r = new ArrayList<>(charEvents); charEvents.clear(); return r; }
    public static List<int[]> pollMouseEvents() { var r = new ArrayList<>(mouseEvents); mouseEvents.clear(); return r; }

    public static double[] consumeMouseDelta() {
        double dx = mouseDX, dy = mouseDY;
        mouseDX = 0; mouseDY = 0;
        return new double[]{dx, dy};
    }
}
