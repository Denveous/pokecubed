@echo off
set /p message="Commit message (optional): "

if "%message%"=="" (
    set message=Auto-update
)

echo Fixing git ownership...
git config --global --add safe.directory %CD%

echo Adding files...
git add .

echo Committing with message: %message%
git commit -m "%message%"

echo Pushing to GitHub...
git push origin main

echo Done!
pause