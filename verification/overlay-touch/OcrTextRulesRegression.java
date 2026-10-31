package com.codextemp.wechatcaptureprobe;

import java.util.Random;

/** 包含换行及Unicode边界的固定种子对照，防止优化意外扩大英文复核判据。 */
public final class OcrTextRulesRegression {
    private static int checks;
    private static void compare(String text) {
        boolean same = OcrTextRules.hasLetter(text) == text.matches(".*[A-Za-z].*")
                && OcrTextRules.alphanumeric(text) == text.matches("[A-Za-z0-9]+")
                && OcrTextRules.endsAlphanumeric(text) == text.matches(".*[A-Za-z0-9]$")
                && OcrTextRules.startsAlphanumeric(text) == text.matches("^[A-Za-z0-9].*")
                && OcrTextRules.compact(text).equals(text.replaceAll("\\s+", ""));
        if (!same) throw new AssertionError("rule-mismatch-at-" + checks);
        checks++;
    }
    public static void main(String[] args) {
        String[] fixed = {"", "A", "012", "a\nb", "a\r\n", "\nA", "中A文", "ＡＢ１２", "é", "😀A", "a\u2028b", "a\u0085b", "a\u00a0b", " \t\r\n\f"};
        for (String text : fixed) compare(text);
        int[] alphabet = {'A','z','0','9','中',' ', '\t','\r','\n','\f',0x2028,0x2029,0x85,0xa0,0xff21,0x1f600};
        Random random = new Random(4127);
        for (int i = 0; i < 10000; i++) {
            StringBuilder text = new StringBuilder();
            int size = random.nextInt(40);
            for (int j = 0; j < size; j++) text.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
            compare(text.toString());
        }
        System.out.println("PASS ocrTextRuleInputs=" + checks + " predicatesPerInput=5 realChat=0 deviceLatencyMeasured=false");
        if (args.length > 0 && "--benchmark".equals(args[0])) benchmark(fixed);
    }

    private static volatile long consumed;

    private static long measure(String[] inputs, boolean reused) {
        long started = System.nanoTime(), sum = 0;
        for (int i = 0; i < 20000; i++) {
            String text = inputs[i % inputs.length];
            sum += (reused ? OcrTextRules.hasLetter(text) : text.matches(".*[A-Za-z].*")) ? 1 : 0;
            sum += (reused ? OcrTextRules.alphanumeric(text) : text.matches("[A-Za-z0-9]+")) ? 1 : 0;
            sum += (reused ? OcrTextRules.endsAlphanumeric(text) : text.matches(".*[A-Za-z0-9]$")) ? 1 : 0;
            sum += (reused ? OcrTextRules.startsAlphanumeric(text) : text.matches("^[A-Za-z0-9].*")) ? 1 : 0;
            sum += (reused ? OcrTextRules.compact(text) : text.replaceAll("\\s+", "")).length();
        }
        consumed = sum;
        return System.nanoTime() - started;
    }

    /** 仅对照桌面JVM中的规则成本，不作为Android端到端延迟或验收门槛。 */
    private static void benchmark(String[] inputs) {
        for (int i = 0; i < 6; i++) { measure(inputs, false); measure(inputs, true); }
        long[] original = new long[9], reused = new long[9];
        for (int i = 0; i < 9; i++) {
            if (i % 2 == 0) { original[i] = measure(inputs, false); reused[i] = measure(inputs, true); }
            else { reused[i] = measure(inputs, true); original[i] = measure(inputs, false); }
        }
        java.util.Arrays.sort(original); java.util.Arrays.sort(reused);
        System.out.println("RULE_MICROBENCH desktopJvm=true inputsPerRound=20000 rounds=9 originalMedianNs="
                + original[4] + " reusedMedianNs=" + reused[4] + " consumed=" + consumed
                + " androidLatencyClaim=false");
    }
}
