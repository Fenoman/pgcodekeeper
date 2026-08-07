@echo off
setlocal disabledelayedexpansion

set "DIR=%~dp0"
set "DIR=%DIR:~0,-1%"

if "%~1"=="" (
    echo Usage: %~nx0 ^<exact-version^> 1>&2
    endlocal & exit /b 2
)
if not "%~2"=="" (
    echo Usage: %~nx0 ^<exact-version^> 1>&2
    endlocal & exit /b 2
)
set "MAVEN_VERSION=%~1"
if not "%MAVEN_VERSION:!=%"=="%MAVEN_VERSION%" (
    echo Version must be X.Y.Z, X.Y.Z-SNAPSHOT or X.Y.Z-qualifier 1>&2
    endlocal & exit /b 2
)
setlocal enabledelayedexpansion
set "VERSION_BASE="
set "VERSION_QUALIFIER="
set "VERSION_EXTRA="
for /f "tokens=1,2,* delims=-" %%A in ("!MAVEN_VERSION!") do (
    set "VERSION_BASE=%%A"
    set "VERSION_QUALIFIER=%%B"
    set "VERSION_EXTRA=%%C"
)
if defined VERSION_EXTRA (
    goto :invalid_version
)
set "NORMALIZED_VERSION=!VERSION_BASE!"
if defined VERSION_QUALIFIER set "NORMALIZED_VERSION=!VERSION_BASE!-!VERSION_QUALIFIER!"
if not "!MAVEN_VERSION!"=="!NORMALIZED_VERSION!" goto :invalid_version
echo(!VERSION_BASE!| findstr /r "^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$" >nul
if errorlevel 1 (
    goto :invalid_version
)
set "OSGI_VERSION=%VERSION_BASE%"
if "%VERSION_QUALIFIER%"=="SNAPSHOT" (
    set "OSGI_VERSION=%VERSION_BASE%.qualifier"
) else if defined VERSION_QUALIFIER (
    if /i "%VERSION_QUALIFIER%"=="qualifier" goto :reserved_qualifier
    echo(!VERSION_QUALIFIER!| findstr /r "^[A-Za-z0-9_][A-Za-z0-9_]*$" >nul
    if errorlevel 1 (
        goto :invalid_version
    )
    set "OSGI_VERSION=%VERSION_BASE%.%VERSION_QUALIFIER%"
)

pushd "%DIR%\.."

call mvn org.eclipse.tycho:tycho-versions-plugin:4.0.13:set-version ^
-Dartifacts=ru.taximaxim.codekeeper.rcp.product,ru.taximaxim.codekeeper.ui,ru.taximaxim.codekeeper.mainapp,ru.taximaxim.codekeeper.feature,ru.taximaxim.codekeeper.updatesite ^
-DnewVersion=%OSGI_VERSION%

if errorlevel 1 goto :done

rem Маркетинговая Maven-версия корня сохраняет дефис; OSGi-модули используют
rem эквивалентный qualifier через точку.
call mvn org.eclipse.tycho:tycho-versions-plugin:4.0.13:set-property ^
-Dartifacts=ru.taximaxim.codeKeeper ^
-Dproperties=revision ^
-DnewRevision=%MAVEN_VERSION%

:done
set "RESULT=%ERRORLEVEL%"

popd
endlocal & endlocal & exit /b %RESULT%

:invalid_version
echo Version must be X.Y.Z, X.Y.Z-SNAPSHOT or X.Y.Z-qualifier 1>&2
endlocal & endlocal & exit /b 2

:reserved_qualifier
echo Literal qualifier is reserved; use X.Y.Z-SNAPSHOT 1>&2
endlocal & endlocal & exit /b 2
