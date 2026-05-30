@echo off
rem URA File Management Suite ? development CLI runner
rem
rem Usage (from the file-processor\ directory after 'mvn clean package'):
rem   ura.bat [--dry-run] <command> <pipeline.toon> [args...]
rem
rem Examples:
rem   ura.bat help
rem   ura.bat search           config\adjustment\adjustment_pipeline.toon
rem   ura.bat copy             config\voucher\voucher_unknown_pipeline.toon
rem   ura.bat --dry-run backup config\adjustment\adjustment_pipeline.toon
rem   ura.bat prepare-inbox    config\adjustment\adjustment_pipeline.toon
rem   ura.bat create-schema    adjustment  samples\adj_sample.csv  config\adjustment\adj_gen.toon
rem
rem This script targets the fat JAR in target\ -- build once with 'mvn clean package'.
rem For deployed servers use ura.bat bundled alongside file-processor.jar.
setlocal
cd /d "%~dp0"

set "JAR="
for %%F in (target\file-processor-*.jar) do set "JAR=%%F"
if not defined JAR (
    echo ERROR: no JAR found matching target\file-processor-*.jar
    echo        Run 'mvn clean package' first.
    exit /b 1
)

java --enable-native-access=ALL-UNNAMED ^
     -cp "%JAR%" ^
     com.gamma.util.MainApp %*