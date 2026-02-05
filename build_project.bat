@echo off
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot
cd /d C:\Users\gmincarbone\nearby
call gradlew.bat assembleRelease --no-daemon --stacktrace
