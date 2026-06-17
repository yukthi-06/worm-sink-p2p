@echo off
call gradlew :wormsink-cli:installDist
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)
call "%~dp0run.bat" send "D:\sw\sqlite-tools-win-x64-3510000.zip" --signaling-url=http://127.0.0.1:8787
