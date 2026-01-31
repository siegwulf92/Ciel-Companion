@echo off
REM =================================================================
REM Ciel Companion - Debug and Launch Script
REM =================================================================
echo [DEBUG] Starting Ciel Companion...
echo [DEBUG] Java Version Check:
java -version
echo.

REM Set the project directory to the location of this script
set "PROJECT_DIR=%~dp0"
echo [DEBUG] Project Directory set to: %PROJECT_DIR%

REM Set the path to the JAR file
set "JAR_PATH=%PROJECT_DIR%target\ciel-idle-companion-1.0-SNAPSHOT.jar"
echo [DEBUG] JAR Path set to: %JAR_PATH%

REM Check if the JAR file exists before trying to run it
if not exist "%JAR_PATH%" (
    echo [ERROR] JAR file not found at the expected location.
    echo [ERROR] Please run 'mvn clean package' first to build the project.
    pause
    exit /b
)

echo [INFO] Launching Ciel...
echo =================================================================

REM Run the Java application
REM We use "start javaw" to run it in the background without a hanging console window
start "Ciel Companion" javaw -jar "%JAR_PATH%"

echo [INFO] Ciel Companion has been started in the background.
echo [INFO] You can close this window. Check logs for output.

exit /b
