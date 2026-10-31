package com.codextemp.wechatcaptureprobe;

import android.os.SystemClock;
import com.google.android.gms.tasks.Task;
import java.util.concurrent.atomic.AtomicLong;

/** 仅观测任务完成至业务回调的等待；不接触识别结果，不调度或改变业务线程。 */
final class OcrCallbackClock {
    private final AtomicLong completedAt = new AtomicLong(-1);

    <T> Task<T> watch(Task<T> task) {
        // 直接执行器只记一个原子时间；必须先于业务监听注册，不能在SDK线程做校验或UI操作。
        task.addOnCompleteListener(Runnable::run,
                ignored -> completedAt.compareAndSet(-1, SystemClock.elapsedRealtime()));
        return task;
    }

    long queueMs() {
        long completed = completedAt.get();
        return completed < 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - completed);
    }
}
