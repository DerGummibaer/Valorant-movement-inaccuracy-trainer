@echo off
title Stillness Trainer - Build

echo ============================================================
echo  Stillness Trainer - Build standalone .exe
echo ============================================================
echo.
echo This compiles stillness_trainer.py into a single executable
echo using PyInstaller. The result will be in the dist\ folder.
echo.

echo Checking Python...
python --version >nul 2>&1
if errorlevel 1 (
    echo Python not found. Install from https://www.python.org/downloads/
    pause
    exit /b 1
)

echo Installing build dependencies...
pip install pynput pyinstaller --quiet

echo.
echo Building...
echo.

pyinstaller ^
    --onefile ^
    --noconsole ^
    --name "StillnessTrainer" ^
    stillness_trainer.py

if errorlevel 1 (
    echo.
    echo Build failed. See the error above.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  Done! Your executable is at:
echo  dist\StillnessTrainer.exe
echo ============================================================
echo.
echo Note: Windows Defender and other antivirus software may flag
echo this file because it uses global input hooks - the same as a
echo keylogger. This is a known false positive with PyInstaller.
echo Sharing the source code and this build script is the better
echo option if you want others to trust what they're running.
echo.
pause
