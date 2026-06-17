@echo off
call gradlew :wormsink-cli:installDist
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)
call "%~dp0wormsink-cli\build\install\wormsink-cli\bin\wormsink-cli.bat" %*
