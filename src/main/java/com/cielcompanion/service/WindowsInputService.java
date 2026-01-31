package com.cielcompanion.service;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.List;

public class WindowsInputService {

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        /**
         * Contains the time of the last input.
         */
        class LASTINPUTINFO extends Structure {
            public int cbSize;
            public int dwTime;

            @Override
            protected List<String> getFieldOrder() {
                return Arrays.asList("cbSize", "dwTime");
            }
        }

        boolean GetLastInputInfo(LASTINPUTINFO result);
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        /**
         * Retrieves the number of milliseconds that have elapsed since the system was started.
         */
        int GetTickCount();
    }

    /**
     * Calculates the idle time in milliseconds.
     * @return Idle time in ms.
     */
    public static long getIdleTimeMillis() {
        User32.LASTINPUTINFO lastInputInfo = new User32.LASTINPUTINFO();
        lastInputInfo.cbSize = lastInputInfo.size();
        
        if (User32.INSTANCE.GetLastInputInfo(lastInputInfo)) {
            // GetTickCount returns time since boot
            // dwTime returns time of last input since boot
            // Diff is idle time
            return Kernel32.INSTANCE.GetTickCount() - lastInputInfo.dwTime;
        }
        return 0;
    }
}