@echo off
rem Runs the adjustment ETL pipeline.
rem Working directory must be the sandbox root (the directory containing this script).
setlocal
cd /d "%~dp0"
java --enable-native-access=ALL-UNNAMED ^
     -jar file-processor\target\file-processor-1.0.jar ^
     file-processor\config\adjustment\adjustment_pipeline.toon
