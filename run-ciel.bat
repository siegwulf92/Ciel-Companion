@echo off
set "CIEL_ROOT=%~dp0"
REM Use the same guarded launcher as Task Scheduler. It selects the current
REM reviewed JAR, enforces the single-instance lock, and records startup
REM failures instead of leaving a second Java process behind.
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "%CIEL_ROOT%tools\startup\Start-Ciel.ps1"
exit /b %ERRORLEVEL%
