@echo off
call gradlew :wormsink-cli:installDist
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)
call "%~dp0run.bat" send "D:\Programs_Portable_More\zzz.7z" --signaling-url=http://127.0.0.1:8787
