@echo off
rem Runs the adjustment ETL pipeline.
rem Working directory must be the sandbox root (the directory containing this script).
setlocal
cd /d "%~dp0"
set "JAR="
for %%F in (file-processor\target\file-processor-*.jar) do set "JAR=%%F"
if not defined JAR (
    echo ERROR: no JAR found matching file-processor\target\file-processor-*.jar
    echo        Run 'mvn clean package' first.
    exit /b 1
)
java --enable-native-access=ALL-UNNAMED ^
     -jar "%JAR%" ^
     file-processor\config\adjustment\adjustment_pipeline.toon
