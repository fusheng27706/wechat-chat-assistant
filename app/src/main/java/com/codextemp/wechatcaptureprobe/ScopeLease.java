package com.codextemp.wechatcaptureprobe;

final class ScopeLease implements AutoCloseable {
    static final class Request {
        final long identifier;
        final long epoch;
        final long startedAt;

        private Request(long identifier, long epoch, long startedAt) {
            this.identifier = identifier;
            this.epoch = epoch;
            this.startedAt = startedAt;
        }
    }

    static final class Certificate {
        private final ScopeLease owner;
        final long identifier;
        final long epoch;
        final long startedAt;

        private Certificate(ScopeLease owner, long identifier, long epoch, long startedAt) {
            this.owner = owner;
            this.identifier = identifier;
            this.epoch = epoch;
            this.startedAt = startedAt;
        }
    }

    private final long lifetime;
    private long sequence;
    private long epoch;
    private Request pending;
    private Certificate accepted;
    private boolean closed;

    ScopeLease(long lifetime) {
        if (lifetime <= 0) throw new IllegalArgumentException("Invalid scope lifetime");
        this.lifetime = lifetime;
    }

    synchronized Request begin(long now) {
        if (closed || pending != null) return null;
        pending = new Request(++sequence, epoch, now);
        return pending;
    }

    synchronized long nextIdentifier() { return sequence + 1; }
    synchronized boolean pending() { return pending != null; }

    synchronized Certificate complete(Request request, long now, boolean sceneChanged) {
        if (request == null || pending != request) return null;
        pending = null;
        if (closed || request.epoch != epoch || now < request.startedAt || now - request.startedAt >= lifetime) return null;
        if (sceneChanged) epoch++;
        accepted = new Certificate(this, request.identifier, epoch, request.startedAt);
        return accepted;
    }

    synchronized boolean reject(Request request) {
        if (request == null || pending != request) return false;
        pending = null;
        invalidate();
        return true;
    }

    synchronized boolean valid(Certificate certificate, long now) {
        return !closed && certificate != null && certificate.owner == this && accepted != null && certificate.epoch == epoch
                && accepted.epoch == epoch && now >= certificate.startedAt && now - certificate.startedAt < lifetime;
    }

    synchronized boolean expire(long now) {
        if (closed) return false;
        if (pending != null && pending.epoch == epoch && (now < pending.startedAt || now - pending.startedAt >= lifetime)
                || accepted != null && now < accepted.startedAt) {
            invalidate();
            return true;
        }
        // 在途查询未超期时不判定失效：它马上会给出新鲜结论（complete 续约 / reject 撤销 /
        // DROP 后重查）。否则「结果落地→覆盖层刷新→恰好撞上查询间隙」会把整个会话撤销，
        // 造成「面板出现→分析清空→面板隐藏→重新分析」的自指循环（真机实测 checkedAgeMs=207）。
        boolean pendingYoung = pending != null && pending.epoch == epoch
                && now >= pending.startedAt && now - pending.startedAt < lifetime;
        if (accepted != null && now - accepted.startedAt >= lifetime && !pendingYoung) {
            accepted = null;
            return true;
        }
        return false;
    }

    synchronized void invalidate() {
        epoch++;
        accepted = null;
    }

    @Override public synchronized void close() {
        closed = true;
        invalidate();
    }
}
