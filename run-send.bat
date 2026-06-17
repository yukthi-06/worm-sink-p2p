@echo off
call gradlew :wormsink-cli:installDist
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)
call "%~dp0run.bat" send "D:\1-Movies\crit\pixar\Zootopia 2 (2025) [1080p] [WEBRip] [5.1] [YTS.BZ]\Zootopia.2.2025.1080p.WEBRip.x264.AAC5.1-[YTS.BZ].mp4" --signaling-url=http://127.0.0.1:8787
