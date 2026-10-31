package com.codextemp.wechatcaptureprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 隔离偏好及虚构文字验证本地模式，scope为注入许可，不代表真实微信来源。 */
public final class LocalDemoSmoke extends Instrumentation {
    private static final String PREFIX="local_demo_verify_";
    private AnalysisCoordinator coordinator;
    private Context isolated;
    private int checks;
    private final AtomicBoolean scope=new AtomicBoolean(true);
    private final AtomicInteger providers=new AtomicInteger();
    private boolean fixture;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void require(boolean condition,String code){checks++;if(!condition)throw new AssertionError(code);}
    private void fresh(String token){
        AssistantState.publish(7,"虚构测试",token,List.of(new ChatMessage(false,"虚构样本甲",0)),1,
                new MessageTracker.Update(0,MessageTracker.Kind.BASELINE),new CaptureCoverage(0,0,true));
    }
    @Override public void onStart(){
        Bundle output=new Bundle();
        try{
            ModelSettingsStore real=new ModelSettingsStore(getTargetContext());
            require(!AssistantCaptureService.running()&&!CaptureService.running()&&!ChatGateService.enabled()
                    &&!real.hasKey()&&!real.cloudAllowed(),"real-state-off");
            fixture=true;
            isolated=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(PREFIX+name,mode);}
            };
            for(String name:List.of("model_settings","assistant","confirmed_memory"))getTargetContext().deleteSharedPreferences(PREFIX+name);
            // 仅模拟配置标志用于证明待执行真实分支被取消，不创建密钥或真正的Provider。
            isolated.getSharedPreferences("model_settings",0).edit().putString("key_ciphertext","FICTIONAL_INVALID")
                    .putString("key_iv","FICTIONAL_INVALID").putBoolean("chat_cloud_enabled",true)
                    .putLong("config_revision",0).putLong("consent_revision",0).putBoolean("partial_analysis_allowed",true).commit();
            runOnMainSync(()->{
                AssistantState.clear();AssistantState.paused=false;
                coordinator=new AnalysisCoordinator(isolated,permission->{providers.incrementAndGet();throw new AssertionError("provider-must-not-be-created");},s->scope.get());
                require(!AnalysisCoordinator.demoEnabled(),"default-off");
                fresh("fictional-one");coordinator.poll();
                AnalysisCoordinator.toggleDemo();
                require(AnalysisCoordinator.demoEnabled(),"explicit-enabled");
                require(AssistantState.analysis!=null&&AssistantState.analysis.simulated&&AssistantState.analysis.replies.size()==3,"three-marked-local-replies");
                require(AssistantState.analysisStatus.contains("本地模拟"),"status-marked");
                AnalysisResult first=AssistantState.analysis;
                coordinator.poll();require(AssistantState.analysis==first,"stable-result-does-not-reset-selection");
                scope.set(false);coordinator.poll();require(AssistantState.analysis==null,"expired-source-clears");
                scope.set(true);fresh("fictional-two");coordinator.poll();
                require(AssistantState.analysis!=null&&AssistantState.analysis!=first,"new-source-new-result");
                AssistantState.paused=true;coordinator.poll();require(AssistantState.analysis==null,"pause-clears");
                AssistantState.paused=false;AssistantState.block(7,"虚构测试","fictional-two");coordinator.poll();
                require(AssistantState.analysis==null,"excluded-clears");
                AssistantState.clear();AssistantState.paused=false;coordinator.poll();require(AssistantState.analysis==null,"empty-ocr-no-demo");
                fresh("fictional-three");coordinator.poll();
                require(AssistantState.analysis!=null&&AssistantState.analysis.intents.get(0).evidence.isEmpty(),"no-real-chat-evidence");
            });
            SystemClock.sleep(1300);
            require(providers.get()==0,"pending-real-request-cancelled");
            runOnMainSync(()->{
                isolated.getSharedPreferences("model_settings",0).edit().putBoolean("chat_cloud_enabled",false).commit();
                AnalysisCoordinator.toggleDemo();require(!AnalysisCoordinator.demoEnabled()&&AssistantState.analysis==null,"exit-clears");
                AnalysisCoordinator.toggleDemo();require(AssistantState.analysis!=null,"local-without-cloud");
                coordinator.close();require(!AnalysisCoordinator.demoEnabled(),"close-not-persistent");
                coordinator=new AnalysisCoordinator(isolated,permission->{providers.incrementAndGet();throw new AssertionError();},s->scope.get());
                require(!AnalysisCoordinator.demoEnabled(),"new-owner-default-off");
                coordinator.close();
                isolated.getSharedPreferences("model_settings",0).edit().clear().commit();
                coordinator=new AnalysisCoordinator(isolated,permission->{providers.incrementAndGet();throw new AssertionError();},s->scope.get());
                require(AnalysisCoordinator.demoEnabled(),"no-key-automatic-local-mode");
                fresh("fictional-auto");coordinator.poll();
                require(AssistantState.analysis!=null&&AssistantState.analysis.simulated,"automatic-no-generate-button");
                ChatInteraction.observed(SystemClock.elapsedRealtime());
                fresh("fictional-typing");coordinator.poll();
                require(AssistantState.analysis==null,"typing-defers-new-demo");
                scope.set(false);coordinator.poll();
                require(AssistantState.analysis==null,"typing-does-not-grant-scope");
                scope.set(true);
            });
            SystemClock.sleep(500);
            runOnMainSync(()->{coordinator.poll();require(AssistantState.analysis!=null&&AssistantState.analysis.simulated,"quiet-resumes-automatically");});
            runOnMainSync(()->{long uptime=SystemClock.uptimeMillis();
                ChatInteraction.observedScroll(SystemClock.elapsedRealtime(),uptime,uptime-100);
                fresh("fictional-scroll-deadline");coordinator.poll();});
            SystemClock.sleep(200);
            runOnMainSync(()->{long uptime=SystemClock.uptimeMillis();
                ChatInteraction.observedScroll(SystemClock.elapsedRealtime(),uptime,uptime-100);
                require(AssistantState.analysis==null,"continuous-scroll-does-not-publish");});
            SystemClock.sleep(200);
            runOnMainSync(()->{require(ChatInteraction.quietRemaining(SystemClock.elapsedRealtime())>0,"scroll-extended-window");
                require(AssistantState.analysis==null,"old-scroll-wake-rechecks-new-deadline");});
            SystemClock.sleep(250);
            runOnMainSync(()->{require(AssistantState.analysis!=null,"scroll-deadline-publishes-without-poll");});
            runOnMainSync(()->{ChatInteraction.observed(SystemClock.elapsedRealtime());fresh("fictional-deadline");coordinator.poll();});
            SystemClock.sleep(250);
            runOnMainSync(()->{ChatInteraction.observed(SystemClock.elapsedRealtime());});
            SystemClock.sleep(260);
            runOnMainSync(()->{require(ChatInteraction.quietRemaining(SystemClock.elapsedRealtime())>0,"extended-quiet-test-window");
                require(AssistantState.analysis==null,"old-wake-cannot-bypass-new-interaction");});
            SystemClock.sleep(300);
            runOnMainSync(()->{require(AssistantState.analysis!=null,"deadline-publishes-without-poll");
                ChatInteraction.observed(SystemClock.elapsedRealtime());fresh("fictional-revoked");coordinator.poll();scope.set(false);});
            SystemClock.sleep(550);
            runOnMainSync(()->{require(AssistantState.analysis==null,"deadline-rechecks-source");scope.set(true);
                ChatInteraction.observed(SystemClock.elapsedRealtime());fresh("fictional-closed");coordinator.poll();coordinator.close();});
            SystemClock.sleep(550);
            runOnMainSync(()->{require(AssistantState.analysis==null,"close-cancels-deadline");});
            require(!real.hasKey()&&!real.cloudAllowed()&&providers.get()==0,"real-settings-unchanged-provider-zero");
            output.putString("result","PASS localDemoChecks="+checks+" providerCreations="+providers.get()+" syntheticSource=true wechatActions=0 clipboardWrites=0");
        }catch(Throwable failure){output.putString("result","FAIL localDemoChecks="+checks+" type="+failure.getClass().getSimpleName());}
        finally{if(fixture){runOnMainSync(()->{if(coordinator!=null)coordinator.close();AssistantState.clear();});
            for(String name:List.of("model_settings","assistant","confirmed_memory"))getTargetContext().deleteSharedPreferences(PREFIX+name);}}
        finish(output.getString("result").startsWith("PASS")?-1:0,output);
    }
}
