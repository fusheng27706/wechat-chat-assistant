package com.codextemp.wechatcaptureprobe;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 默认关闭的空闲实验调度。所有方法在同一调度线程调用；只管理空闲任务，不保存来源证明或前台画面。 */
final class IdlePhaseQueue {
    interface Starter {
        /** 不得抛异常；接管前失败也须complete，异步接管后只能在真实终态complete。 */
        void start(int phase, Ticket ticket);
    }
    final class Ticket {
        private boolean returned;
        /** 每个SDK/准备入口另复核当前来源；撤权不会提前释放在途槽。 */
        boolean current() {
            if (returned || active != this || cancelled || finished) return false;
            if (!sourceCurrent()) { cancel(); return false; }
            return !returned && active == this && !cancelled && !finished;
        }
        void complete() {
            if (returned || active != this) return;
            returned = true; active = null;
            if (cancelled || phase >= limit) finish();
            else poll();
        }
    }
    private final LongSupplier clock;
    private final BooleanSupplier eligible;
    private final Starter starter;
    private final Runnable released;
    private final long due;
    private final int limit;
    private long lastNow;
    private Ticket active;
    private int phase;
    private boolean cancelled, finished;

    /** delayMs是空闲机会等待，不替代生产稳定门槛；released归还父图并唤醒重新检查新帧。 */
    IdlePhaseQueue(LongSupplier clock, BooleanSupplier eligible, Starter starter, Runnable released,
                   long delayMs, int phases) {
        if(delayMs<0||phases<1||phases>2)throw new IllegalArgumentException("idle-bounds");
        this.clock=clock;this.eligible=eligible;this.starter=starter;this.released=released;this.limit=phases;
        long now=clock.getAsLong();
        if(now<0)throw new IllegalArgumentException("idle-clock");
        lastNow=now;
        due=now>Long.MAX_VALUE-delayMs?Long.MAX_VALUE:now+delayMs;
    }
    void poll() {
        if(finished)return;
        if(cancelled||!sourceCurrent()) { cancel();return; }
        // 外部校验可能同步触发停止，不能在父图已归还后继续启动。
        if(finished||cancelled||active!=null||lastNow<due)return;
        active=new Ticket();phase++;
        try { starter.start(phase,active); }
        catch(RuntimeException failure) {
            // 合同违例时只撤权；无法判断SDK是否已接管，不能猜测释放在途资源。
            cancel();throw failure;
        }
    }
    private boolean sourceCurrent() {
        try {
            long now=clock.getAsLong();
            if(now<0||now<lastNow)return false;
            lastNow=now;
            return eligible.getAsBoolean();
        }catch(RuntimeException unavailable){return false;}
    }
    /** 新操作、来源失效、停止统一撤销；等待中的任务可即刻归还，在途必须等complete。 */
    void cancel() {cancelled=true;if(active==null)finish();}
    private void finish() {
        if(finished||active!=null)return;
        finished=true;released.run();
    }
    boolean inFlight(){return active!=null;}
    boolean finished(){return finished;}
}
