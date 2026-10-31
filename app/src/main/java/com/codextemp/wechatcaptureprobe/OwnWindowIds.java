package com.codextemp.wechatcaptureprobe;

import java.util.ArrayDeque;

final class OwnWindowIds {
    private final int limit;
    private final ArrayDeque<Integer> recent = new ArrayDeque<>();

    OwnWindowIds(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid own window history limit");
        this.limit = limit;
    }

    synchronized boolean remember(int identifier) {
        if (identifier < 0) return false;
        boolean added = !recent.remove(Integer.valueOf(identifier));
        recent.addLast(identifier);
        while (recent.size() > limit) recent.removeFirst();
        return added;
    }

    synchronized boolean contains(int identifier) { return identifier >= 0 && recent.contains(identifier); }

    boolean ignoreEvent(int identifier, boolean windowsChanged, boolean ownPackage) {
        return contains(identifier) && (windowsChanged || ownPackage);
    }

    boolean ignoreOcclusion(int identifier, boolean accessibilityOverlay) {
        return accessibilityOverlay && contains(identifier);
    }

    synchronized int size() { return recent.size(); }

    synchronized void clear() { recent.clear(); }
}
