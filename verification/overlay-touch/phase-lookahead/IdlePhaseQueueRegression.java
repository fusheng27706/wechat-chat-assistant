package com.codextemp.wechatcaptureprobe;

import java.util.ArrayList;
import java.util.List;

/** 受控时钟/终态验证队列所有权，不能证明真实服务或SDK已完成。 */
public final class IdlePhaseQueueRegression {
    private static int checks;
    private static void require(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static final class Fixture {
        long now=100;boolean eligible=true;int released;
        final List<IdlePhaseQueue.Ticket> calls=new ArrayList<>();
        final List<Integer> phases=new ArrayList<>();
        final IdlePhaseQueue queue=new IdlePhaseQueue(()->now,()->eligible,(phase,ticket)->{phases.add(phase);calls.add(ticket);},()->released++,600,2);
        void due(){now=700;queue.poll();}
    }
    public static void main(String[] args) {
        Fixture a=new Fixture();a.queue.poll();require(a.calls.isEmpty(),"wait-idle-opportunity");
        a.now=699;a.queue.poll();require(a.calls.isEmpty(),"not-before-deadline");
        a.due();require(a.calls.size()==1&&a.queue.inFlight()&&a.calls.get(0).current(),"first-active");
        a.now=10000;a.queue.poll();require(a.calls.size()==1&&a.released==0,"time-not-terminal");
        a.calls.get(0).complete();require(a.calls.size()==2&&a.phases.equals(java.util.Arrays.asList(1,2)),"serial-next-after-return");
        a.calls.get(0).complete();require(a.queue.inFlight()&&a.released==0,"old-duplicate-cannot-release-new");
        a.calls.get(1).complete();a.queue.poll();a.queue.cancel();a.calls.get(1).complete();
        require(a.released==1&&a.queue.finished()&&!a.queue.inFlight()&&a.calls.size()==2,"bounded-and-release-once");
        Fixture b=new Fixture();b.queue.cancel();b.due();require(b.calls.isEmpty()&&b.released==1,"cancel-before-start");
        Fixture c=new Fixture();c.due();c.queue.cancel();c.queue.cancel();c.now=20000;c.queue.poll();
        require(c.calls.size()==1&&c.released==0&&c.queue.inFlight()&&!c.calls.get(0).current(),"cancel-retains-active-slot");
        c.calls.get(0).complete();require(c.released==1&&c.queue.finished()&&c.calls.size()==1,"cancel-skips-rest");
        Fixture d=new Fixture();d.due();d.eligible=false;
        require(!d.calls.get(0).current()&&d.released==0,"source-failure-revokes-without-release");
        d.eligible=true;require(!d.calls.get(0).current(),"revocation-does-not-revive");
        d.calls.get(0).complete();require(d.released==1&&d.calls.size()==1,"source-failure-ends-on-return");
        Fixture e=new Fixture();e.eligible=false;e.due();require(e.calls.isEmpty()&&e.released==1,"source-invalid-before-start");
        int[] synchronous={0,0};
        IdlePhaseQueue sync=new IdlePhaseQueue(()->100,()->true,(phase,ticket)->{synchronous[0]++;ticket.complete();},()->synchronous[1]++,0,2);
        sync.poll();require(sync.finished()&&synchronous[0]==2&&synchronous[1]==1,"synchronous-terminal-reentry");
        boolean rejected=false;try{new IdlePhaseQueue(()->0,()->true,(p,t)->{},()->{},0,3);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"phase-cap");
        IdlePhaseQueue[] reentrant=new IdlePhaseQueue[1];int[] effects={0,0};
        reentrant[0]=new IdlePhaseQueue(()->100,()->{reentrant[0].cancel();return true;},(p,t)->effects[0]++,()->effects[1]++,0,2);
        reentrant[0].poll();require(effects[0]==0&&effects[1]==1,"validation-cancel-cannot-start-after-release");
        boolean[] revoke={false};IdlePhaseQueue.Ticket[] ticket=new IdlePhaseQueue.Ticket[1];
        reentrant[0]=new IdlePhaseQueue(()->100,()->{if(revoke[0])reentrant[0].cancel();return true;},(p,t)->ticket[0]=t,()->{},0,2);
        reentrant[0].poll();revoke[0]=true;
        require(!ticket[0].current()&&reentrant[0].inFlight(),"validation-cancel-cannot-return-current");ticket[0].complete();
        Fixture rollback=new Fixture();rollback.due();rollback.now=699;rollback.queue.poll();
        require(!rollback.calls.get(0).current()&&rollback.released==0,"clock-rollback-revokes-active");
        rollback.calls.get(0).complete();require(rollback.released==1,"rollback-waits-real-return");
        int[] errors={0};IdlePhaseQueue unavailable=new IdlePhaseQueue(()->100,()->{throw new IllegalStateException("fixed-source");},(p,t)->errors[0]++,()->errors[0]+=10,0,2);
        unavailable.poll();require(errors[0]==10&&unavailable.finished(),"source-exception-fail-closed");
        long[] time={100};IdlePhaseQueue badClock=new IdlePhaseQueue(()->time[0],()->true,(p,t)->errors[0]++,()->{},0,1);
        time[0]=-1;badClock.poll();require(badClock.finished()&&errors[0]==10,"negative-clock-fail-closed");
        int[] contractRelease={0};IdlePhaseQueue broken=new IdlePhaseQueue(()->100,()->true,(p,t)->{ticket[0]=t;throw new IllegalStateException("fixed-contract");},()->contractRelease[0]++,0,2);
        boolean thrown=false;try{broken.poll();}catch(IllegalStateException expected){thrown=true;}
        require(thrown&&broken.inFlight()&&!ticket[0].current()&&contractRelease[0]==0,"starter-error-does-not-guess-completion");
        ticket[0].complete();require(contractRelease[0]==1&&broken.finished(),"contract-error-real-return");
        System.out.println("PASS idlePhaseQueueChecks="+checks);
    }
}
