@echo off
call gradlew :wormsink-cli:installDist
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)
call "%~dp0run.bat" send "D:\1-Movies\crit\1\www.1TamilMV.cards - Karuppu (2026) Tamil HQ HDRip - 720p - x264 - (DD+5.1 - 192Kbps & AAC) - 1.3GB - ESub.mkv" --signaling-url=http://127.0.0.1:8787
