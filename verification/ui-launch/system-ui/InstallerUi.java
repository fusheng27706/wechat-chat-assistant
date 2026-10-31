package com.codextemp.wechatcaptureprobe.verify;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.core.Configurator;

/** 仅确认已获用户同意的临时测试包安装；不用截图，不输出系统层级。 */
public final class InstallerUi extends UiAutomatorTestCase {
    public void testCloseOwnGate() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        assertEquals("settings-focused", "com.android.settings", getUiDevice().getCurrentPackageName());
        boolean toggled = false;
        long until = android.os.SystemClock.elapsedRealtime() + 20000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            UiObject detail = new UiObject(new UiSelector().packageName("com.android.settings").text("使用微信聊天辅助范围服务"));
            if (!toggled && detail.exists()) {
                assertTrue("own-service-row-click", detail.click());
                toggled = true;
                android.os.SystemClock.sleep(500);
            }
            UiObject own = new UiObject(new UiSelector().packageName("com.android.settings").textContains("微信聊天辅助范围服务"));
            if (toggled && own.exists()) {
                for (String label : new String[]{"停止", "关闭", "确定"}) {
                    UiObject stop = new UiObject(new UiSelector().packageName("com.android.settings").text(label).clickable(true));
                    if(stop.exists()) { assertTrue("own-service-stop-click", stop.click()); System.out.println("OWN_GATE_STOP_REQUESTED"); return; }
                }
            }
            if (!toggled) {
                UiObject service = new UiObject(new UiSelector().packageName("com.android.settings").text("微信聊天辅助范围服务"));
                if(service.exists()) service.click();
                else for(String label:new String[]{"已下载的应用","已下载的服务","下载的应用","下载的服务"}) {
                    UiObject row=new UiObject(new UiSelector().packageName("com.android.settings").text(label));
                    if(row.exists()){row.click();break;}
                }
            }
            android.os.SystemClock.sleep(250);
        }
        fail("own-service-stop-not-confirmed");
    }

    public void testStopOwnAssistant() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        assertEquals("own-home-focused", "com.codextemp.wechatcaptureprobe", getUiDevice().getCurrentPackageName());
        UiObject toggle = new UiObject(new UiSelector().packageName("com.codextemp.wechatcaptureprobe").className("android.widget.Switch"));
        assertTrue("own-toggle-present", toggle.waitForExists(5000));
        if (toggle.isChecked()) assertTrue("normal-own-stop-click", toggle.click());
        System.out.println("NORMAL_OWN_STOP_REQUESTED");
    }

    public void testAllowOwnVerifier() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        String expected = getParams().getString("app_label", "临时助手页面校验");
        assertTrue("unknown-verifier-label", expected.equals("临时助手页面校验") || expected.equals("临时悬浮触摸校验")
                || expected.equals("临时投射所有者校验"));
        String[] packages = {"com.miui.securitycenter", "com.miui.packageinstaller", "com.android.packageinstaller"};
        System.out.println("WATCHING_INSTALLER");
        long end = android.os.SystemClock.elapsedRealtime() + 20000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            for (String pkg : packages) {
                UiObject name = new UiObject(new UiSelector().packageName(pkg).text(expected));
                if (!name.exists()) continue;
                System.out.println("INSTALL_DIALOG_VERIFIED");
                for (String label : new String[]{"安装", "允许", "允许安装", "继续安装"}) {
                    UiObject allow = new UiObject(new UiSelector().packageName(pkg).text(label).clickable(true).enabled(true));
                    if (allow.exists()) {
                        boolean clicked = allow.click();
                        System.out.println("INSTALL_ALLOW_CLICK=" + clicked);
                        assertTrue("installer-click-not-accepted", clicked);
                        return;
                    }
                }
                fail("INSTALL_ALLOW_NOT_FOUND");
                return;
            }
            android.os.SystemClock.sleep(250);
        }
        fail("INSTALL_DIALOG_NOT_OBSERVED");
    }
}


