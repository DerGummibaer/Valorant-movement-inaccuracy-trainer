@echo off
title Stillness Trainer - Release

echo ============================================================
echo  Stillness Trainer - Publish Release
echo ============================================================
echo.

set /p MSG="Commit message (what changed?): "
set /p VER="Version tag (e.g. v1.1): "

echo.
echo Pushing "%MSG%" as %VER%...
echo.

git add .
git commit -m "%MSG%"
git push origin main
git tag %VER%
git push origin %VER%

echo.
echo ============================================================
echo  Done! GitHub Actions is building the exe now.
echo  Check progress at:
echo  https://github.com/DerGummibaer/Valorant-movement-inaccuracy-trainer/actions
echo ============================================================
echo.
pause
