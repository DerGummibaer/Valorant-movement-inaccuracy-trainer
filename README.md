<<<<<<< HEAD
# Valorant Stillness Trainer

A training tool that teaches you to wait for full movement recovery before shooting.
It listens for your **WASD keys** and **left mouse click** globally, even while Valorant is in focus,
so you can run it on a second monitor and train in real time.

**This tool is 100% open source. Read `stillness_trainer.py` before running it if you want to verify what it does.**
It uses OS-level input hooks (the same mechanism Windows uses for accessibility software) to read your keypresses.
No data is collected, sent anywhere, or stored beyond your local `trainer_settings.json`.

---

## Requirements

- Windows 10 or 11
- [Python 3.9+](https://www.python.org/downloads/) — tick **"Add Python to PATH"** during install

---

## How to run

Double-click **`start.bat`**.

That's it. It will install the one dependency (`pynput`) and launch the trainer.

---

## How to use

| Action | Effect |
|---|---|
| Press **W A S D** | Registers as movement — crosshair turns red |
| Release all keys | Starts the stillness countdown |
| Crosshair turns **green** | You're fully still — safe to shoot |
| **Left click** | Registers as a shot — shows hit/miss and reaction time |

**Hit** = you clicked while still. Shows how many milliseconds after becoming still you fired.
**Miss** = you clicked too early. Shows how many milliseconds you had left to wait.

### Threshold

The threshold (default **150ms**) is how long you must hold still before the crosshair goes green.
150ms matches Valorant's movement error recovery window according to community testing.
You can lower it while learning and raise it as you improve.

### Themes

Click **🎨 THEMES** to switch between Dark Pro, Slate, Light, and Military presets,
or build a fully custom colour scheme. Your theme and threshold are saved automatically.

---

## Building a standalone .exe yourself

If you'd prefer a single executable rather than running from Python, you can build one yourself
using **`build.bat`**. This compiles the code on your own machine so you know exactly what's inside.

---

## For developers — publishing a release

This repo uses GitHub Actions to build the `.exe` automatically and transparently.
Anyone can trace the download back to the build log and the source code that produced it.

**To publish a new release:**

```bash
git tag v1.0
git push origin v1.0
```

GitHub Actions will then compile and attach `StillnessTrainer.exe` to a new GitHub Release automatically,
with a link to the build log so anyone can verify what produced the file.

---

## Vanguard / anti-cheat note

Valorant's Vanguard anti-cheat may block global input hooks on some systems.
If keys aren't registering in-game, try running `start.bat` as administrator (right-click → Run as administrator).

---

## FAQ

**Does this get me banned?**
This tool only reads input — it never writes, simulates, or injects keystrokes into any application.
It is a passive observer, the same as any accessibility overlay. That said, use it at your own risk.

**Why Python and not an exe?**
A compiled exe using global keyboard hooks looks identical to a keylogger to antivirus software,
and will be flagged regardless of what it actually does. Running from source lets you (and your
antivirus) see exactly what the code does before executing it.

**My settings aren't saving.**
Make sure the folder containing `stillness_trainer.py` isn't read-only.
Settings are written to `trainer_settings.json` in the same folder.
=======
# Valorant-movement-inaccuracy-trainer
A small lightweight program that listens to your keypresses even ingame and compares the time between your movement keys being let go and your mouse 1 clicking to tell you if you shot too early or could shoot earlier
>>>>>>> beaea1a5c663fcda25140bc84c6a893672428584
