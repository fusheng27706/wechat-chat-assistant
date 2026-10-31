package com.codextemp.wechatcaptureprobe;

/** 纯状态回归；只用固定签名，不构造真实聊天来源或剪贴板成功证明。 */
public final class QuickSuggestionRegression {
    private static int checks;
    private static void require(boolean value) { checks++; if (!value) throw new AssertionError("check=" + checks); }
    private static boolean visible(SuggestionVisibility state, boolean quiet, long interaction, long frame, String signature, boolean valid, boolean appended) { return state.visible(quiet, interaction, frame, signature, valid, appended, 200); }
    public static void main(String[] args) {
        SuggestionVisibility state = new SuggestionVisibility();
        require(visible(state, true, 0, 1, "A", true, true));
        state.dismiss(0, 1, 100);
        require(!visible(state, true, 0, 1, "A", true, true));
        require(!visible(state, true, 0, 2, "old-message-revealed", true, true));
        for (int i = 0; i < 100; i++) require(!visible(state, true, 0, 2, "old-message-revealed", true, true));
        require(!visible(state, true, 0, 3, "earlier-old-message", true, false));
        require(!visible(state, false, 0, 4, "new-content", true, true));
        require(visible(state, true, 0, 4, "new-content", true, true));
        state.dismiss(7, 4, 100);
        require(!visible(state, false, 8, 4, "A", true, true));
        require(visible(state, true, 8, 4, "A", true, true));
        state.dismiss(8, 4, 100);
        require(!visible(state, true, 8, 5, "unconfirmed", false, true));
        require(!visible(state, true, 8, 5, "baseline", true, true));
        require(!visible(state, true, 8, 6, "baseline", true, true));
        state.open();
        require(visible(state, true, 8, 6, "baseline", true, true));
        long revision = ChatInteraction.revision();
        ChatInteraction.observed(1000);
        require(ChatInteraction.revision() == revision + 1);
        require(!ChatInteraction.quiet(1000));
        require(!ChatInteraction.quiet(1449));
        require(ChatInteraction.quiet(1450));
        ChatInteraction.observed(1400);
        require(!ChatInteraction.quiet(1450));
        require(ChatInteraction.quiet(1850));
        System.out.println("PASS quickSuggestionChecks=" + checks + " synthetic=true");
    }
}
