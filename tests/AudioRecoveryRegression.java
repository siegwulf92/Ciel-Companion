import com.cielcompanion.service.*;
import com.cielcompanion.CielState;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioRecoveryRegression {
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    static class FakeListener extends VoiceListener {
        final AtomicInteger toggles=new AtomicInteger();
        FakeListener() { super(null); }
        @Override public void toggleListening() { toggles.incrementAndGet(); }
    }
    public static void main(String[] args) throws Exception {
        Settings.initialize();
        check(!Settings.getHardMuteProcs().contains("discord.exe"),"Discord still globally mutes speech");
        Method schedule=CielController.class.getDeclaredMethod("scheduleNextSpeakBasedOnPhase",int.class);
        schedule.setAccessible(true);
        for (int phase=0;phase<=3;phase++) {
            CielState.setNextSpeakAt(Long.MAX_VALUE);
            schedule.invoke(null,phase);
            check(CielState.getNextSpeakAt()!=Long.MAX_VALUE,"Speech timer stuck after suppression");
        }
        FakeListener listener=new FakeListener();
        HotkeyService hotkey=new HotkeyService(listener);
        Field key=HotkeyService.class.getDeclaredField("toggleKeyCode");key.setAccessible(true);key.setInt(hotkey,NativeKeyEvent.VC_F12);
        NativeKeyEvent event=new NativeKeyEvent(NativeKeyEvent.NATIVE_KEY_PRESSED,0,0,NativeKeyEvent.VC_F12,NativeKeyEvent.CHAR_UNDEFINED);
        hotkey.nativeKeyPressed(event);hotkey.nativeKeyPressed(event);
        long until=System.currentTimeMillis()+1000;
        while (listener.toggles.get()<1 && System.currentTimeMillis()<until) Thread.sleep(10);
        check(listener.toggles.get()==1,"Key repeat toggled listening twice");
        hotkey.nativeKeyReleased(event);hotkey.nativeKeyPressed(event);
        until=System.currentTimeMillis()+1000;
        while (listener.toggles.get()<2 && System.currentTimeMillis()<until) Thread.sleep(10);
        check(listener.toggles.get()==2,"Release did not allow the next toggle");
        listener.close();
        System.out.println("Audio recovery regressions passed: hard mute, finite speech timers, hotkey repeat/release.");
        System.exit(0);
    }
}
