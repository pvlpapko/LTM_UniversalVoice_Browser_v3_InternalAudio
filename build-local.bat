@echo off
gradle :app:assembleDebug :app:bundleDebug :app:bundleRelease --stacktrace
if errorlevel 1 exit /b 1
echo APK: app\build\outputs\apk\debug\app-debug.apk
echo AAB debug: app\build\outputs\bundle\debug\app-debug.aab
echo AAB release unsigned: app\build\outputs\bundle\release\app-release.aab
