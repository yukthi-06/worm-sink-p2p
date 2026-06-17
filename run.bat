@echo off
setlocal
set "LIB_DIR=%~dp0wormsink-cli\build\install\wormsink-cli\lib"
set "CLASSPATH=%LIB_DIR%\*"
java -cp "%CLASSPATH%" org.wormsink.cli.WormSinkCli %* 2>NUL
endlocal
