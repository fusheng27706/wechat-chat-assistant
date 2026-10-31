package com.codextemp.wechatcaptureprobe;

import java.util.regex.Pattern;

/** 保留原OCR的ASCII及换行判据，只复用不可变Pattern；每次创建独立Matcher。 */
final class OcrTextRules {
    private static final Pattern LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern ALPHANUMERIC = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern END = Pattern.compile(".*[A-Za-z0-9]$");
    private static final Pattern START = Pattern.compile("^[A-Za-z0-9].*");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private OcrTextRules() { }

    static boolean hasLetter(String text) { return LETTER.matcher(text).matches(); }
    static boolean alphanumeric(String text) { return ALPHANUMERIC.matcher(text).matches(); }
    static boolean endsAlphanumeric(String text) { return END.matcher(text).matches(); }
    static boolean startsAlphanumeric(String text) { return START.matcher(text).matches(); }
    static String compact(String text) { return SPACE.matcher(text).replaceAll(""); }
}
