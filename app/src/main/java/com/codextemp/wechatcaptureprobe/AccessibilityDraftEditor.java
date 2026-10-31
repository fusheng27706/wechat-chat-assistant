package com.codextemp.wechatcaptureprobe;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class AccessibilityDraftEditor implements DraftPlan.Editor {
    private final Supplier<AccessibilityNodeInfo> root;
    private final String packageName;
    private final int window;
    private final Rect composer;
    private final BooleanSupplier current;

    private static final class Entry {
        final AccessibilityNodeInfo node;
        final int depth;

        Entry(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    AccessibilityDraftEditor(Supplier<AccessibilityNodeInfo> root, String packageName, int window,
                             Rect composer, BooleanSupplier current) {
        this.root = root;
        this.packageName = packageName;
        this.window = window;
        this.composer = new Rect(composer);
        this.current = current;
    }

    @Override
    public DraftPlan.Field inspect() {
        AccessibilityNodeInfo input = findInput();
        if (input == null) {
            return null;
        }
        try {
            return field(input);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            input.recycle();
        }
    }

    @Override
    public boolean requestSetText(DraftPlan.Field expected, String replacement) {
        if (replacement.isEmpty() || replacement.length() > DraftPlan.MAXIMUM_LENGTH) {
            return false;
        }
        AccessibilityNodeInfo input = findInput();
        if (input == null) {
            return false;
        }
        try {
            if (!input.refresh() || !eligible(input) || !expected.same(field(input)) || !current.getAsBoolean()) {
                return false;
            }
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement);
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        } catch (RuntimeException exception) {
            return false;
        } finally {
            input.recycle();
        }
    }

    private AccessibilityNodeInfo findInput() {
        if (!current.getAsBoolean()) {
            return null;
        }
        ArrayDeque<Entry> queue = new ArrayDeque<>();
        AccessibilityNodeInfo selected = null;
        int visited = 0;
        boolean complete = true;
        try {
            AccessibilityNodeInfo top = root.get();
            if (top == null) {
                return null;
            }
            queue.add(new Entry(top, 0));
            while (!queue.isEmpty()) {
                Entry entry = queue.removeFirst();
                AccessibilityNodeInfo node = entry.node;
                try {
                    visited++;
                    if (visited > 500 || entry.depth > 20 || !current.getAsBoolean()) {
                        complete = false;
                        break;
                    }
                    if (!packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                            || node.getWindowId() != window) {
                        complete = false;
                        break;
                    }
                    if (eligible(node)) {
                        if (selected != null) {
                            complete = false;
                            break;
                        }
                        selected = AccessibilityNodeInfo.obtain(node);
                    }
                    int children = node.getChildCount();
                    if (children > 500 - visited - queue.size()) {
                        complete = false;
                        break;
                    }
                    for (int index = 0; index < children; index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) {
                            queue.add(new Entry(child, entry.depth + 1));
                        } else {
                            complete = false;
                        }
                    }
                } finally {
                    node.recycle();
                }
            }
        } catch (RuntimeException exception) {
            complete = false;
        } finally {
            while (!queue.isEmpty()) {
                queue.removeFirst().node.recycle();
            }
        }
        if (!complete || !current.getAsBoolean()) {
            if (selected != null) {
                selected.recycle();
            }
            return null;
        }
        return selected;
    }

    private boolean eligible(AccessibilityNodeInfo node) {
        if (!node.isEditable() || !node.isEnabled() || !node.isVisibleToUser() || node.isPassword()
                || !packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                || node.getWindowId() != window) {
            return false;
        }
        int inputType = node.getInputType();
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        int kind = inputType & InputType.TYPE_MASK_CLASS;
        if (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                || kind == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !bounds.isEmpty() && composer.contains(bounds)
                && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    }

    private DraftPlan.Field field(AccessibilityNodeInfo node) {
        if (!current.getAsBoolean() || !eligible(node)) {
            return null;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        CharSequence text = node.isShowingHintText() ? "" : node.getText();
        if (text != null && text.length() > DraftPlan.MAXIMUM_LENGTH) {
            return null;
        }
        String identity = window + ":" + node.getViewIdResourceName() + ":" + node.getClassName() + ":" + bounds.flattenToString();
        return new DraftPlan.Field(identity, text == null ? "" : text.toString());
    }
}
