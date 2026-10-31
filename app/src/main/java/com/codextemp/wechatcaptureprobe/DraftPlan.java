package com.codextemp.wechatcaptureprobe;

import java.util.function.BooleanSupplier;

final class DraftPlan {
    static final int MAXIMUM_LENGTH = 10000;
    enum Mode { KEEP, APPEND, REPLACE }
    enum Status { PRESERVED, STALE, CHANGED, UNAVAILABLE, TOO_LONG, ALREADY_ATTEMPTED, REQUESTED, UNCONFIRMED, VERIFIED }

    interface Editor {
        Field inspect();
        boolean requestSetText(Field expected, String replacement);
    }

    static final class Field {
        final String identity;
        final String text;

        Field(String identity, String text) {
            this.identity = identity;
            this.text = text;
        }

        boolean same(Field other) {
            return other != null && identity.equals(other.identity) && text.equals(other.text);
        }
    }

    final String original;
    private final Field field;
    private final String reply;
    private boolean attempted;
    private String expectedAfter;

    private DraftPlan(Field field, String reply) {
        this.field = field;
        original = field.text;
        this.reply = reply;
    }

    static DraftPlan inspect(Editor editor, String reply, BooleanSupplier current) {
        if (!current.getAsBoolean() || reply == null || reply.trim().isEmpty() || reply.length() > 600) {
            return null;
        }
        Field field = editor.inspect();
        if (!current.getAsBoolean() || field == null || field.identity.isEmpty() || field.text.length() > MAXIMUM_LENGTH) {
            return null;
        }
        return new DraftPlan(field, reply);
    }

    String preview(Mode mode) {
        if (mode == Mode.KEEP) {
            return original;
        }
        return mode == Mode.APPEND && !original.isEmpty() ? original + "\n" + reply : reply;
    }

    Status commit(Mode mode, Editor editor, BooleanSupplier current) {
        if (attempted) {
            return Status.ALREADY_ATTEMPTED;
        }
        if (mode == Mode.KEEP) {
            return Status.PRESERVED;
        }
        if (!current.getAsBoolean()) {
            return Status.STALE;
        }
        Field now = editor.inspect();
        if (now == null) {
            return Status.UNAVAILABLE;
        }
        if (!field.same(now)) {
            return Status.CHANGED;
        }
        String replacement = preview(mode);
        if (replacement.length() > MAXIMUM_LENGTH) {
            return Status.TOO_LONG;
        }
        if (!current.getAsBoolean()) {
            return Status.STALE;
        }
        attempted = true;
        expectedAfter = replacement;
        return editor.requestSetText(field, replacement) ? Status.REQUESTED : Status.UNCONFIRMED;
    }

    Status verify(Editor editor, BooleanSupplier current) {
        if (!attempted || expectedAfter == null || !current.getAsBoolean()) {
            return Status.UNCONFIRMED;
        }
        Field now = editor.inspect();
        return current.getAsBoolean() && now != null && field.identity.equals(now.identity)
                && expectedAfter.equals(now.text) ? Status.VERIFIED : Status.UNCONFIRMED;
    }
}
