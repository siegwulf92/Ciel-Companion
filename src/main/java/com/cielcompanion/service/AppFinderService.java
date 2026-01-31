package com.cielcompanion.service;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.util.Optional;

/**
 * Finds application paths by querying the Windows Registry.
 */
public class AppFinderService {

    /**
     * Searches the Windows Registry for the path to a given executable.
     * @param executableName The name of the .exe file (e.g., "Discord.exe").
     * @return An Optional containing the full path, or empty if not found.
     */
    public Optional<String> findAppPath(String executableName) {
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + executableName;
        try {
            // Check both HKEY_CURRENT_USER and HKEY_LOCAL_MACHINE for the path
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, keyPath)) {
                String path = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, keyPath, "");
                if (path != null && !path.isBlank()) {
                    return Optional.of(path);
                }
            }
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String path = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, keyPath, "");
                if (path != null && !path.isBlank()) {
                    return Optional.of(path);
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (AppFinderService): Error accessing registry for " + executableName);
            e.printStackTrace();
        }
        return Optional.empty();
    }
}
