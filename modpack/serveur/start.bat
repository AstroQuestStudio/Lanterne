@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem === Club Citrouille - demarrage du serveur (Windows) ===
rem Installe NeoForge au premier lancement, puis redemarre le serveur s'il plante (borne a 5 essais).
cd /d "%~dp0"
set "NEOFORGE=26.3.0.7-beta"
set "ARGS=libraries\net\neoforged\neoforge\%NEOFORGE%\win_args.txt"
set "MAX_CRASH=5"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
  echo [ERREUR] Java introuvable. Installe Java 25 ^(https://adoptium.net^) ou definis JAVA_HOME.
  exit /b 1
)

if not exist "%ARGS%" (
  echo [INSTALL] NeoForge %NEOFORGE% absent : installation...
  if not exist "neoforge-%NEOFORGE%-installer.jar" (
    curl -fL -o "neoforge-%NEOFORGE%-installer.jar" "https://maven.neoforged.net/releases/net/neoforged/neoforge/%NEOFORGE%/neoforge-%NEOFORGE%-installer.jar"
    if errorlevel 1 ( echo [ERREUR] Telechargement impossible. & exit /b 1 )
  )
  java -jar "neoforge-%NEOFORGE%-installer.jar" --installServer
  if errorlevel 1 ( echo [ERREUR] Installation de NeoForge echouee. & exit /b 1 )
)

findstr /b /c:"eula=true" eula.txt >nul 2>nul
if errorlevel 1 (
  echo.
  echo Ce serveur necessite d'accepter le CLUF de Minecraft : https://aka.ms/MinecraftEULA
  if /i "%ACCEPT_EULA%"=="true" (
    echo eula=true> eula.txt
  ) else (
    set "REP="
    set /p "REP=Tape OUI pour accepter le CLUF : "
    if /i not "!REP!"=="OUI" ( echo CLUF non accepte, arret. & exit /b 1 )
    echo eula=true> eula.txt
  )
)

set /a CRASHES=0
:run
java @user_jvm_args.txt @%ARGS% nogui
set "CODE=%errorlevel%"
if "%CODE%"=="0" ( echo [OK] Serveur arrete proprement. & exit /b 0 )
set /a CRASHES+=1
echo [ATTENTION] Le serveur s'est arrete avec le code %CODE% ^(incident %CRASHES%/%MAX_CRASH%^).
if %CRASHES% GEQ %MAX_CRASH% ( echo [ERREUR] Trop d'incidents, abandon. Voir logs\latest.log et crash-reports\ & exit /b %CODE% )
echo Redemarrage dans 10 secondes... ^(Ctrl+C pour annuler^)
timeout /t 10 /nobreak >nul
goto run
