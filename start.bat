@echo off
title Stillness Trainer

echo Checking Python...
python --version >nul 2>&1
if errorlevel 1 (
    echo.
    echo Python not found. Please install Python 3.9 or later from https://www.python.org/downloads/
    echo Make sure to tick "Add Python to PATH" during install.
    echo.
    pause
    exit /b 1
)

echo Installing dependencies...
pip install pynput --quiet

echo Launching Stillness Trainer...
echo.
python stillness_trainer.py

if errorlevel 1 (
    echo.
    echo Something went wrong. See the error above.
    pause
)
