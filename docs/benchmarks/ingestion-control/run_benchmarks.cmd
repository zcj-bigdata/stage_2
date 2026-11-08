@echo off
rem Runs every benchmark of the Ingestion Service and the Control Module (Windows).
rem
rem Usage, from any folder:
rem     docs\benchmarks\ingestion-control\run_benchmarks.cmd 2026-11-08
rem
rem The results go to docs\benchmarks\ingestion-control\<argument>\ (CSV + console logs).
rem It takes about 25-30 minutes. Keep the laptop plugged in, close other programs and
rem stop any service that is using the ports 7000-7003 or 17000-17002.

setlocal
cd /d "%~dp0..\..\.."
if "%~1"=="" (set RUN=results) else (set RUN=%~1)
set OUT=docs\benchmarks\ingestion-control\%RUN%
if not exist "%OUT%" mkdir "%OUT%"

echo Host information -^> %OUT%\host.txt
(
  powershell -NoProfile -Command "(Get-CimInstance Win32_Processor).Name; 'Cores: ' + (Get-CimInstance Win32_Processor).NumberOfCores + ', logical CPUs: ' + (Get-CimInstance Win32_Processor).NumberOfLogicalProcessors; 'RAM (GB): ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1); (Get-CimInstance Win32_OperatingSystem).Caption"
  java -version 2>&1
  git rev-parse --short HEAD
) > "%OUT%\host.txt"
type "%OUT%\host.txt"

echo.
echo [1/4] Building the jars
call mvnw.cmd -q clean package -DskipTests || goto :error

echo [2/4] JMH: Ingestion Service (about 4 min)
call mvnw.cmd -q -pl ingestion-service -am test-compile exec:exec ^
  -Dbenchmark.args="IngestionBenchmark -f 2 -bm avgt -tu ms -rf csv -rff %OUT%\ingestion_jmh.csv" ^
  > "%OUT%\ingestion_jmh.log" 2>&1 || goto :error

echo [3/4] JMH: Control Module bookkeeping (about 9 min)
call mvnw.cmd -q -pl control-module -am test-compile exec:exec ^
  -Dbenchmark.args="ControlStateBenchmark -f 2 -bm avgt -tu us -rf csv -rff %OUT%\control_jmh.csv" ^
  > "%OUT%\control_jmh.log" 2>&1 || goto :error

echo [4/4] Load benchmark of the whole pipeline (about 12 min)
call mvnw.cmd -q -pl control-module -am test-compile exec:exec ^
  -Dbenchmark.main=com.zcj.control.benchmark.PipelineLoadBenchmark ^
  -Dbenchmark.args="--books 100 --workers 1,2,4,8 --reps 5 --results %OUT%" ^
  > "%OUT%\pipeline_load.log" 2>&1 || goto :error

echo.
echo Done. Results in %OUT%
dir /b "%OUT%"
exit /b 0

:error
echo.
echo A step failed. Look at the last .log file in %OUT%
exit /b 1
