package com.codextemp.wechatcaptureprobe;

/** 固定标题与虚构时间线，不操作设备；不替代实际新帧和SDK验收。 */
public final class ConfirmationRecoveryRegression {
    private static int checks;
    private static void require(boolean result, String name) {
        checks++; if (!result) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        ConversationSession session = new ConversationSession();
        require(!session.canPrepareInitialHeader(1), "unobserved-cannot-schedule-first-title");
        require(!session.claimInitialHeader(1), "unobserved-cannot-prepare-first-title");
        require(!session.awaitingConfirmation(1), "unobserved");
        session.observe(1, 10);
        long ticket = session.ticket();
        require(session.canPrepareInitialHeader(1) && session.canPrepareInitialHeader(1)
                && session.ticket()==ticket, "scheduling-query-does-not-consume-or-change-ticket");
        require(!session.canPrepareInitialHeader(2), "wrong-window-cannot-schedule-first-title");
        require(!session.awaitingConfirmation(1), "not-yet-read");
        require(!session.claimInitialHeader(2), "wrong-window-cannot-prepare-first-title");
        require(session.claimInitialHeader(1), "first-title-preparation-once");
        require(!session.canPrepareInitialHeader(1), "claimed-first-title-no-longer-schedules-early");
        require(!session.claimInitialHeader(1) && !session.awaitingConfirmation(1), "preparation-is-not-confirmation-or-refill");
        require(!session.confirm(ticket, "SYNTHETIC_A"), "first-not-confirmed");
        require(session.awaitingConfirmation(1), "one-confirmation");
        require(!session.claimInitialHeader(1), "second-frame-cannot-bypass-body-stability");
        require(session.canPrepareConfirmationFrame(1), "fresh-ticket-has-one-early-opportunity");
        require(!session.claimConfirmationFrame(2), "wrong-generation-cannot-consume");
        require(session.claimConfirmationFrame(1), "claim-before-reader-creation");
        require(!session.claimConfirmationFrame(1), "failed-or-zero-frame-cannot-reclaim");
        require(session.awaitingConfirmation(1), "claim-is-not-title-confirmation");
        require(!session.awaitingConfirmation(2), "different-window");
        long request = 1400, arrival = 1450, ocr = 1900;
        long due = FreshFrameRecovery.dueAt(1400, request, arrival, 0);
        require(due == 2450, "fresh-frame-ready-before-ocr-retry");
        require(FreshFrameRecovery.dueAt(1400, request, arrival, ocr) == 2900, "ordinary-recovery-unchanged");
        require(!FreshFrameRecovery.needed(due - 1, 1400, request, arrival, 0, false, true), "arrival-spacing");
        require(!FreshFrameRecovery.needed(due, 1400, request, arrival, 0, true, true), "busy-refused");
        require(!FreshFrameRecovery.needed(due, 1400, request, arrival, 0, false, false), "existing-frame-refused");
        require(FreshFrameRecovery.needed(due, 1400, request, arrival, 0, false, true), "prepare-real-frame");
        require(due < ocr + 600, "preparation-does-not-imply-ocr-eligibility");
        require(session.confirm(ticket, "SYNTHETIC_A"), "second-confirmed");
        require(!session.awaitingConfirmation(1), "confirmed-uses-ordinary-recovery");
        require(!session.claimInitialHeader(1), "confirmed-session-cannot-prepare-first-title");
        require(!session.confirm(ticket, "SYNTHETIC_B"), "changed-title-restarts");
        require(session.awaitingConfirmation(1), "changed-title-needs-second");
        require(!session.canPrepareConfirmationFrame(1), "changed-title-same-ticket-does-not-refill");
        session.suspend();
        require(!session.awaitingConfirmation(1), "suspended");
        require(!session.confirm(ticket, "SYNTHETIC_A"), "old-ticket-rejected");
        session.observe(2, 20);
        session.confirm(session.ticket(), "SYNTHETIC_C");
        require(session.canPrepareConfirmationFrame(2), "new-ticket-has-independent-opportunity");
        session.close();
        require(!session.awaitingConfirmation(2), "closed");
        session.observe(2, 20);
        session.confirm(session.ticket(), "SYNTHETIC_C");
        session.confirm(session.ticket(), " ");
        require(!session.awaitingConfirmation(2), "empty-title");
        require(!session.canPrepareConfirmationFrame(2), "empty-title-cannot-prepare");
        ConversationSession failed = new ConversationSession();
        failed.observe(9, 90);
        long failedTicket = failed.ticket();
        require(failed.claimInitialHeader(9), "empty-attempt-claims-first-slot");
        failed.confirm(failedTicket, " ");
        require(!failed.claimInitialHeader(9), "empty-result-does-not-refill-first-slot");
        require(!failed.observe(9, 90) && !failed.claimInitialHeader(9), "same-observation-does-not-refill");
        failed.observe(9, 91);
        require(failed.claimInitialHeader(9) && !failed.confirm(failedTicket, "SYNTHETIC_A"), "new-header-revision-rejects-old-confirmation");
        failed.close();
        require(!failed.claimInitialHeader(9), "closed-first-slot-denied");
        long early = FreshFrameRecovery.confirmationDueAt(1350, 1000, 1100);
        require(early == 1700, "second-frame-one-time-600ms");
        require(!FreshFrameRecovery.confirmationNeeded(1699, 1350, 1000, 1100, false, true), "early-lower-bound");
        require(FreshFrameRecovery.confirmationNeeded(1700, 1350, 1000, 1100, false, true), "early-inclusive");
        require(!FreshFrameRecovery.confirmationNeeded(1700, 1350, 1000, 1100, true, true), "early-sdk-still-busy");
        require(!FreshFrameRecovery.confirmationNeeded(1700, 1350, 1000, 1100, false, false), "eligible-frame-not-discarded");
        require(FreshFrameRecovery.confirmationDueAt(2100, 1000, 1100) == 2100, "overlay-stability-still-required");
        require(!FreshFrameRecovery.confirmationNeeded(1700, 1350, 1800, 1100, false, true), "future-request-refused");
        require(FreshFrameRecovery.confirmationDueAt(0, Long.MAX_VALUE - 10, 0) == Long.MAX_VALUE, "early-overflow-saturates");
        require(FreshFrameRecovery.dueAt(1350, 1700, 1100, 0) == 2700, "consumed-opportunity-normal-one-second-fallback");
        System.out.println("PASS confirmationRecoveryChecks=" + checks + " simulatedClock=true realImages=0");
    }
}
