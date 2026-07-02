@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat assembleDebug > build_compile_output.txt 2>&1
exit /b %ERRORLEVEL%