@echo off
rem ===========================================================================
rem run.cmd - Windows launcher for glint PR-Lens Bot (run.sh runner)
rem ===========================================================================

setlocal enabledelayedexpansion

rem Check for Git Bash
if exist "%ProgramFiles%\Git\bin\bash.exe" (
    "%ProgramFiles%\Git\bin\bash.exe" "%~dp0run.sh" %*
    exit /b %ERRORLEVEL%
)

rem Check for bash in PATH
where bash >nul 2>nul
if %ERRORLEVEL% equ 0 (
    bash "%~dp0run.sh" %*
    exit /b %ERRORLEVEL%
)

echo [glint] Running via Windows Command Prompt...
if exist "%ProgramFiles%\Eclipse Adoptium\jdk-25.0.4.101-hotspot" (
    set "JAVA_HOME=%ProgramFiles%\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
    set "PATH=!JAVA_HOME!\bin;!PATH!"
)

if "%DATABASE_URL%"=="" (
    if not exist "%~dp0data" mkdir "%~dp0data"
    set "DATABASE_URL=jdbc:h2:./data/pr_triage;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    set "DATABASE_USER=sa"
    set "DATABASE_PASSWORD="
)

cd "%~dp0bot"
call mvnw.cmd spring-boot:run %*
