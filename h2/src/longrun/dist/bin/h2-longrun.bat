@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
set "LONGRUN_JAR=%APP_HOME%\lib\h2-longrun.jar"
set "CLASSPATH_VALUE=%LONGRUN_JAR%"
set "ARGS=%*"

if not defined JAVA_CMD set "JAVA_CMD=java"
if defined H2_LONGRUN_H2_JAR set "CLASSPATH_VALUE=%H2_LONGRUN_H2_JAR%;%CLASSPATH_VALUE%"

:parse
if "%~1"=="" goto run
if "%~1"=="--h2-jar" (
    if not "%~2"=="" (
        set "CLASSPATH_VALUE=%~2;%LONGRUN_JAR%"
    )
)
if "%~1"=="-j" (
    if not "%~2"=="" (
        set "CLASSPATH_VALUE=%~2;%LONGRUN_JAR%"
    )
)
shift
goto parse

:run
"%JAVA_CMD%" -cp "%CLASSPATH_VALUE%" org.h2.test.longrun.LongRunTestApp %ARGS%
exit /b %ERRORLEVEL%
