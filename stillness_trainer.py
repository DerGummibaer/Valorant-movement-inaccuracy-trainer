#!/usr/bin/env python3
"""
Valorant Stillness Trainer — requires: pip install pynput
"""

import tkinter as tk
from tkinter import colorchooser
import threading, time, queue, json, os
from pynput import keyboard, mouse as pynput_mouse

# ── Themes ─────────────────────────────────────────────────────────────────────
THEMES = {
    "Dark Pro": {
        "bg": "#111111", "panel": "#1a1a1a", "border": "#333333",
        "key_bg": "#222222", "text": "#ffffff", "text_mid": "#aaaaaa",
        "text_dim": "#555555", "green": "#00e676", "green_dim": "#0a2a15",
        "red": "#ff1744", "red_dim": "#2a0008",
    },
    "Slate": {
        "bg": "#0f172a", "panel": "#1e293b", "border": "#334155",
        "key_bg": "#1e293b", "text": "#f1f5f9", "text_mid": "#94a3b8",
        "text_dim": "#475569", "green": "#4ade80", "green_dim": "#0a1f12",
        "red": "#f87171", "red_dim": "#1f0a0a",
    },
    "Light": {
        "bg": "#f5f5f5", "panel": "#ffffff", "border": "#cccccc",
        "key_bg": "#e0e0e0", "text": "#111111", "text_mid": "#444444",
        "text_dim": "#999999", "green": "#16a34a", "green_dim": "#dcfce7",
        "red": "#dc2626", "red_dim": "#fee2e2",
    },
    "Military": {
        "bg": "#0d1200", "panel": "#141a00", "border": "#2a3a00",
        "key_bg": "#1a2400", "text": "#c8ff00", "text_mid": "#7a9900",
        "text_dim": "#3a4d00", "green": "#a3e635", "green_dim": "#111d00",
        "red": "#ff6b00", "red_dim": "#1d0a00",
    },
    "Custom": {},
}

DEFAULT_THEME     = "Dark Pro"
DEFAULT_THRESHOLD = 150
MAX_HISTORY       = 60
MOVE_CHARS        = {'w', 'a', 's', 'd'}
SETTINGS_FILE     = os.path.join(os.path.dirname(os.path.abspath(__file__)), "trainer_settings.json")


def load_settings():
    try:
        with open(SETTINGS_FILE) as f:
            return json.load(f)
    except Exception:
        return {}

def save_settings(data):
    try:
        with open(SETTINGS_FILE, "w") as f:
            json.dump(data, f, indent=2)
    except Exception:
        pass


class StillnessTrainer(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Stillness Trainer")
        self.geometry("960x580")
        self.minsize(720, 460)

        saved = load_settings()
        self.theme_name = saved.get("theme", DEFAULT_THEME)
        if self.theme_name not in THEMES:
            self.theme_name = DEFAULT_THEME
        if saved.get("custom_theme"):
            THEMES["Custom"] = saved["custom_theme"]
        self.T = dict(THEMES[self.theme_name])

        self.threshold_ms   = saved.get("threshold", DEFAULT_THRESHOLD)
        self.keys_down      = set()
        self.is_still       = True
        self.still_at       = time.perf_counter()
        self.still_timer    = None
        self.progress_job   = None
        self.progress_start = None
        self.event_queue    = queue.Queue()
        self.hits = self.misses = 0
        self.reaction_times = []
        self.best_reaction  = None
        self.history        = []

        # All widgets that take the main bg colour
        self._bg_widgets    = []
        # All widgets that take the panel colour  
        self._panel_widgets = []

        self._build_ui()
        self._apply_theme()
        self._start_listeners()
        self._poll_queue()

    # ── helpers to register widgets ────────────────────────────────────────────
    def _bg(self, w):
        self._bg_widgets.append(w)
        return w

    def _panel(self, w):
        self._panel_widgets.append(w)
        return w

    # ── Build UI ───────────────────────────────────────────────────────────────
    def _build_ui(self):
        self.grid_columnconfigure(0, weight=3)
        self.grid_columnconfigure(1, weight=2)
        self.grid_rowconfigure(0, weight=1)
        self.configure(bg="#000000")   # overridden by theme

        # ── LEFT ──────────────────────────────────────────────────────────────
        left = self._bg(tk.Frame(self))
        left.grid(row=0, column=0, sticky="nsew", padx=(20, 10), pady=20)
        left.grid_columnconfigure(0, weight=1)
        self.left = left

        self.canvas = tk.Canvas(left, highlightthickness=0, height=210)
        self.canvas.grid(row=0, column=0, sticky="ew")
        self._bg_widgets.append(self.canvas)
        self.canvas.bind("<Configure>", lambda e: self._draw_ring())

        self.state_var = tk.StringVar(value="STILL")
        self.state_lbl = self._bg(tk.Label(left, textvariable=self.state_var,
                                           font=("Courier", 15, "bold")))
        self.state_lbl.grid(row=1, column=0, pady=(4, 0))

        self.pb_frame = self._bg(tk.Frame(left, height=4))
        self.pb_frame.grid(row=2, column=0, sticky="ew", pady=(8, 0))
        self.pb_frame.grid_propagate(False)
        self.pb_fill = tk.Frame(self.pb_frame, height=4)
        self.pb_fill.place(x=0, y=0, relheight=1, relwidth=0)

        # WASD
        key_outer = self._bg(tk.Frame(left))
        key_outer.grid(row=3, column=0, pady=(14, 0))
        self._build_keys(key_outer)

        # Stats
        stats_f = self._bg(tk.Frame(left))
        stats_f.grid(row=4, column=0, pady=(16, 0))
        self._build_stats(stats_f)

        # Threshold
        thr_f = self._bg(tk.Frame(left))
        thr_f.grid(row=5, column=0, pady=(14, 0))
        self._build_slider(thr_f)

        # Bottom row
        bot = self._bg(tk.Frame(left))
        bot.grid(row=6, column=0, pady=(10, 0), sticky="ew")
        bot.grid_columnconfigure(0, weight=1)
        self.hint_lbl = self._bg(tk.Label(bot, text="LEFT CLICK  =  SHOOT",
                                          font=("Courier", 9)))
        self.hint_lbl.grid(row=0, column=0)
        self.theme_btn = tk.Button(bot, text="🎨  THEMES",
                                   font=("Courier", 9), relief="flat", bd=0,
                                   cursor="hand2",
                                   command=self._open_theme_window)
        self.theme_btn.grid(row=0, column=1, sticky="e", padx=(0, 4))
        self._bg_widgets.append(self.theme_btn)

        # ── RIGHT ─────────────────────────────────────────────────────────────
        right = self._panel(tk.Frame(self, highlightthickness=1))
        right.grid(row=0, column=1, sticky="nsew", padx=(0, 20), pady=20)
        right.grid_rowconfigure(1, weight=1)
        right.grid_columnconfigure(0, weight=1)
        self.right = right

        self.hist_lbl = self._panel(tk.Label(right, text="HISTORY",
                                             font=("Courier", 9)))
        self.hist_lbl.grid(row=0, column=0, pady=(12, 4), padx=12, sticky="w")

        list_f = self._panel(tk.Frame(right))
        list_f.grid(row=1, column=0, sticky="nsew")
        list_f.grid_rowconfigure(0, weight=1)
        list_f.grid_columnconfigure(0, weight=1)

        self.history_lb = tk.Listbox(list_f, font=("Courier", 11),
                                     borderwidth=0, highlightthickness=0,
                                     relief="flat", activestyle="none")
        self.history_lb.grid(row=0, column=0, sticky="nsew")
        self.scrollbar = tk.Scrollbar(list_f, orient="vertical",
                                      command=self.history_lb.yview)
        self.scrollbar.grid(row=0, column=1, sticky="ns")
        self.history_lb.configure(yscrollcommand=self.scrollbar.set)

        self.clear_btn = tk.Button(right, text="CLEAR",
                                   font=("Courier", 9), relief="flat", bd=0,
                                   cursor="hand2", command=self._clear_history)
        self.clear_btn.grid(row=2, column=0, pady=(4, 12), padx=12, sticky="e")
        self._panel_widgets.append(self.clear_btn)

    def _build_keys(self, parent):
        self.key_frames = {}
        self.key_labels = {}
        grid = self._bg(tk.Frame(parent))
        grid.pack()
        for text, r, c in [("W", 0, 1), ("A", 1, 0), ("S", 1, 1), ("D", 1, 2)]:
            ch = text.lower()
            f = tk.Frame(grid, padx=1, pady=1)
            f.grid(row=r, column=c, padx=3, pady=3)
            lbl = tk.Label(f, text=text, font=("Courier", 13, "bold"),
                           width=2, height=1, padx=4, pady=4)
            lbl.pack()
            self.key_frames[ch] = f
            self.key_labels[ch] = lbl

    def _build_stats(self, parent):
        self.stat_vars   = {}
        self.stat_lbl_top  = {}
        self.stat_lbl_val  = {}
        defs = [("HITS", "v_hits", "0"), ("MISSES", "v_misses", "0"),
                ("AVG",  "v_avg",  "—"), ("BEST",   "v_best",  "—")]
        for i, (label, key, init) in enumerate(defs):
            v = tk.StringVar(value=init)
            self.stat_vars[key] = v
            f = self._bg(tk.Frame(parent))
            f.grid(row=0, column=i, padx=12)
            lt = self._bg(tk.Label(f, text=label, font=("Courier", 8)))
            lt.pack()
            lv = self._bg(tk.Label(f, textvariable=v, font=("Courier", 14, "bold")))
            lv.pack()
            self.stat_lbl_top[key] = lt
            self.stat_lbl_val[key] = lv

    def _build_slider(self, parent):
        self.thr_title_lbl = self._bg(tk.Label(parent, text="THRESHOLD", font=("Courier", 9)))
        lbl = self.thr_title_lbl
        lbl.pack(side="left", padx=(0, 8))
        self.threshold_var = tk.IntVar(value=self.threshold_ms)
        self.slider = tk.Scale(parent, from_=0, to=500, resolution=25,
                               orient="horizontal", variable=self.threshold_var,
                               font=("Courier", 8), highlightthickness=0,
                               bd=0, sliderrelief="flat", showvalue=False,
                               length=180, command=self._on_threshold_change)
        self.slider.pack(side="left")
        self.thr_lbl = self._bg(tk.Label(parent, text=f"{self.threshold_ms}ms",
                                         font=("Courier", 10), width=6))
        self.thr_lbl.pack(side="left", padx=(6, 0))

    # ── Theme ──────────────────────────────────────────────────────────────────
    def _apply_theme(self):
        T = self.T
        self.configure(bg=T["bg"])

        for w in self._bg_widgets:
            try:
                w.configure(bg=T["bg"])
            except Exception:
                pass

        for w in self._panel_widgets:
            try:
                w.configure(bg=T["panel"])
            except Exception:
                pass

        # Canvas
        self.canvas.configure(bg=T["bg"])

        # State label colour depends on state
        self.state_lbl.configure(fg=T["green"] if self.is_still else T["red"])

        # Progress bar frame border colour
        self.pb_frame.configure(bg=T["border"])
        self.pb_fill.configure(bg=T["green"] if self.is_still else T["red"])

        # Keys
        for ch in MOVE_CHARS:
            active = ch in self.keys_down
            self.key_frames[ch].configure(bg=T["green"] if active else T["border"])
            self.key_labels[ch].configure(
                bg=T["green_dim"] if active else T["key_bg"],
                fg=T["green"] if active else T["text_mid"])

        # Stats colours
        self.stat_lbl_val["v_hits"].configure(fg=T["green"])
        self.stat_lbl_val["v_misses"].configure(fg=T["red"])
        self.stat_lbl_val["v_avg"].configure(fg=T["text_mid"])
        self.stat_lbl_val["v_best"].configure(fg=T["text_mid"])
        for k in self.stat_lbl_top:
            self.stat_lbl_top[k].configure(fg=T["text_dim"])

        # Slider
        self.slider.configure(bg=T["bg"], fg=T["text"],
                              troughcolor=T["border"],
                              activebackground=T["green"])
        self.thr_lbl.configure(fg=T["text"])
        self.thr_title_lbl.configure(fg=T["text"])

        # Hint & theme btn
        self.hint_lbl.configure(fg=T["text_dim"])
        self.theme_btn.configure(fg=T["text_dim"],
                                 activebackground=T["bg"],
                                 activeforeground=T["text"])

        # Right panel
        self.right.configure(highlightbackground=T["border"])
        self.hist_lbl.configure(fg=T["text_dim"])
        self.history_lb.configure(bg=T["panel"], fg=T["text_mid"],
                                  selectbackground=T["border"],
                                  selectforeground=T["text"])
        self.scrollbar.configure(bg=T["panel"], troughcolor=T["border"])
        self.clear_btn.configure(fg=T["text_dim"],
                                 activebackground=T["panel"],
                                 activeforeground=T["text"])

        self._recolour_history()
        self._draw_ring()

    def _open_theme_window(self):
        if hasattr(self, "_tw") and self._tw.winfo_exists():
            self._tw.lift(); return
        T = self.T
        win = tk.Toplevel(self)
        win.title("Themes")
        win.geometry("400x460")
        win.resizable(False, False)
        win.configure(bg=T["bg"])
        self._tw = win

        tk.Label(win, text="PRESET THEMES", font=("Courier", 10, "bold"),
                 bg=T["bg"], fg=T["text"]).pack(pady=(18, 8))

        btn_f = tk.Frame(win, bg=T["bg"])
        btn_f.pack()
        for name in [n for n in THEMES if n != "Custom"]:
            active = name == self.theme_name
            tk.Button(btn_f, text=name, font=("Courier", 10), relief="flat",
                      padx=10, pady=6, cursor="hand2",
                      bg=T["border"] if active else T["key_bg"],
                      fg=T["green"] if active else T["text_mid"],
                      activebackground=T["border"],
                      activeforeground=T["text"],
                      command=lambda n=name: self._select_theme(n, win)
                      ).pack(side="left", padx=3)

        tk.Frame(win, bg=T["border"], height=1).pack(fill="x", padx=20, pady=(20, 8))
        tk.Label(win, text="CUSTOM COLOURS", font=("Courier", 10, "bold"),
                 bg=T["bg"], fg=T["text"]).pack(pady=(0, 2))
        tk.Label(win, text="click a swatch to change it",
                 font=("Courier", 8), bg=T["bg"], fg=T["text_dim"]).pack()

        sw_f = tk.Frame(win, bg=T["bg"])
        sw_f.pack(pady=10)

        base = dict(THEMES["Custom"] if THEMES["Custom"] else T)
        self._custom = dict(base)
        self._swatches = {}

        colour_defs = [
            ("bg", "Background"), ("panel", "Panel"), ("border", "Border"),
            ("key_bg", "Key BG"), ("text", "Text"), ("text_mid", "Text mid"),
            ("green", "Still / Hit"), ("red", "Moving / Miss"),
        ]
        for i, (key, label) in enumerate(colour_defs):
            r, c = divmod(i, 2)
            tk.Label(sw_f, text=label, font=("Courier", 9), bg=T["bg"],
                     fg=T["text_mid"], width=11, anchor="e"
                     ).grid(row=r, column=c * 3, padx=(8, 4), pady=5)
            colour = base.get(key, "#888888")
            sw = tk.Label(sw_f, bg=colour, width=4, height=1,
                          relief="flat", cursor="hand2",
                          highlightthickness=1,
                          highlightbackground=T["border"])
            sw.grid(row=r, column=c * 3 + 1, padx=(0, 16))
            sw.bind("<Button-1>", lambda e, k=key, s=sw: self._pick(k, s))
            self._swatches[key] = sw

        tk.Button(win, text="APPLY CUSTOM",
                  font=("Courier", 10, "bold"), relief="flat",
                  padx=14, pady=7, cursor="hand2",
                  bg=T["green"], fg=T["bg"],
                  activebackground=T["green"], activeforeground=T["bg"],
                  command=lambda: self._apply_custom(win)).pack(pady=(8, 0))

    def _pick(self, key, swatch):
        result = colorchooser.askcolor(color=self._custom.get(key, "#888888"),
                                       title=f"Pick: {key}", parent=self._tw)
        if result and result[1]:
            self._custom[key] = result[1]
            swatch.configure(bg=result[1])

    def _apply_custom(self, win):
        base = dict(THEMES[self.theme_name if self.theme_name != "Custom" else DEFAULT_THEME])
        base.update(self._custom)
        # derive missing dim colours
        if "green_dim" not in base:
            base["green_dim"] = "#0a2a15"
        if "red_dim" not in base:
            base["red_dim"] = "#2a0008"
        THEMES["Custom"] = base
        self._select_theme("Custom", win)

    def _select_theme(self, name, win):
        self.theme_name = name
        self.T = dict(THEMES[name])
        self._apply_theme()
        save_settings({"theme": name, "custom_theme": THEMES.get("Custom", {}),
                       "threshold": self.threshold_ms})
        win.destroy()

    # ── Threshold ──────────────────────────────────────────────────────────────
    def _on_threshold_change(self, _=None):
        self.threshold_ms = self.threshold_var.get()
        self.thr_lbl.configure(text=f"{self.threshold_ms}ms")
        if not self.is_still and not self.keys_down:
            self._cancel_timers()
            self._schedule_still()

    # ── Canvas ─────────────────────────────────────────────────────────────────
    def _draw_ring(self):
        c = self.canvas
        c.delete("all")
        w = c.winfo_width() or 300
        h = c.winfo_height() or 210
        cx, cy = w // 2, h // 2
        r = min(w, h) // 2 - 12
        T = self.T
        colour = T["green"] if self.is_still else T["red"]
        c.configure(bg=T["bg"])
        c.create_oval(cx-r, cy-r, cx+r, cy+r, outline=colour, width=3, fill=T["bg"])
        gap  = r * 0.18
        arm  = r * 0.42
        thick = max(2, r // 28)
        for x1, y1, x2, y2 in [
            (cx-arm-gap, cy, cx-gap, cy), (cx+gap, cy, cx+arm+gap, cy),
            (cx, cy-arm-gap, cx, cy-gap), (cx, cy+gap, cx, cy+arm+gap),
        ]:
            c.create_line(x1, y1, x2, y2, fill=colour, width=thick)
        dot = max(3, thick + 1)
        c.create_oval(cx-dot, cy-dot, cx+dot, cy+dot, fill=colour, outline="")

    # ── Listeners ──────────────────────────────────────────────────────────────
    def _start_listeners(self):
        self.kb = keyboard.Listener(on_press=self._kp, on_release=self._kr)
        self.kb.start()
        self.ms = pynput_mouse.Listener(on_click=self._mc)
        self.ms.start()

    def _char(self, key):
        try: return key.char.lower() if key.char else None
        except AttributeError: return None

    def _kp(self, key):
        ch = self._char(key)
        if ch in MOVE_CHARS: self.event_queue.put(("dn", ch))

    def _kr(self, key):
        ch = self._char(key)
        if ch in MOVE_CHARS: self.event_queue.put(("up", ch))

    def _mc(self, x, y, btn, pressed):
        if btn == pynput_mouse.Button.left and pressed:
            self.event_queue.put(("shoot",))

    # ── Event loop ─────────────────────────────────────────────────────────────
    def _poll_queue(self):
        try:
            while True:
                ev = self.event_queue.get_nowait()
                k = ev[0]
                if k == "dn":
                    ch = ev[1]
                    self.keys_down.add(ch)
                    self._key_ui(ch, True)
                    if self.is_still: self._go_moving()
                    else: self._cancel_timers()
                elif k == "up":
                    ch = ev[1]
                    self.keys_down.discard(ch)
                    self._key_ui(ch, False)
                    if not self.keys_down: self._schedule_still()
                elif k == "shoot":
                    self._shoot()
        except queue.Empty:
            pass
        self.after(16, self._poll_queue)

    # ── State machine ──────────────────────────────────────────────────────────
    def _go_still(self):
        self.is_still = True
        self.still_at = time.perf_counter()
        self._cancel_timers()
        T = self.T
        self.state_var.set("STILL")
        self.state_lbl.configure(fg=T["green"])
        self.pb_fill.configure(bg=T["green"])
        self.pb_fill.place(relwidth=1)
        self._draw_ring()

    def _go_moving(self):
        self._cancel_timers()
        self.is_still = False
        T = self.T
        self.state_var.set("MOVING")
        self.state_lbl.configure(fg=T["red"])
        self.pb_fill.configure(bg=T["red"])
        self.pb_fill.place(relwidth=0)
        self._draw_ring()

    def _schedule_still(self):
        if self.keys_down or self.still_timer: return
        if self.threshold_ms == 0: self._go_still(); return
        self.progress_start = time.perf_counter()
        self._anim()
        self.still_timer = self.after(self.threshold_ms, self._still_fired)

    def _still_fired(self):
        self.still_timer = None
        if not self.keys_down: self._go_still()

    def _cancel_timers(self):
        if self.still_timer:
            self.after_cancel(self.still_timer); self.still_timer = None
        if self.progress_job:
            self.after_cancel(self.progress_job); self.progress_job = None
        self.progress_start = None

    def _anim(self):
        if self.progress_start is None: return
        pct = min((time.perf_counter() - self.progress_start) * 1000 / max(self.threshold_ms, 1), 1.0)
        self.pb_fill.place(relwidth=pct)
        if pct < 1.0:
            self.progress_job = self.after(20, self._anim)

    # ── Shoot ──────────────────────────────────────────────────────────────────
    def _shoot(self):
        T = self.T
        now = time.perf_counter()
        if self.is_still:
            ms = int((now - self.still_at) * 1000)
            if ms > 300:
                return   # too long after stopping — not a meaningful rep
            self.hits += 1
            self.reaction_times.append(ms)
            self.best_reaction = min(self.best_reaction, ms) if self.best_reaction else ms
            avg = int(sum(self.reaction_times) / len(self.reaction_times))
            self.stat_vars["v_hits"].set(str(self.hits))
            self.stat_vars["v_avg"].set(f"{avg}ms")
            self.stat_vars["v_best"].set(f"{self.best_reaction}ms")
            self.stat_lbl_val["v_avg"].configure(
                fg=T["green"] if avg < 150 else T["text_mid"])
            self._add_history(True, ms)
        else:
            elapsed = (now - self.progress_start) * 1000 if self.progress_start else 0
            ms_left = max(0, int(self.threshold_ms - elapsed))
            self.misses += 1
            self.stat_vars["v_misses"].set(str(self.misses))
            self._add_history(False, ms_left)

    # ── History ────────────────────────────────────────────────────────────────
    def _add_history(self, success, ms):
        import datetime
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        if success:
            r = ("lightning" if ms < 80 else "clean" if ms < 150 else "ok" if ms < 250 else "slow")
            entry = f"  HIT   +{ms:>5}ms  {r:<9}  {ts}"
        else:
            entry = f"  MISS  -{ms:>5}ms  too early    {ts}"
        self.history.insert(0, (entry, success))
        if len(self.history) > MAX_HISTORY: self.history.pop()
        self._recolour_history()

    def _recolour_history(self):
        T = self.T
        lb = self.history_lb
        lb.delete(0, "end")
        for text, success in self.history:
            lb.insert("end", text)
            lb.itemconfigure("end",
                             fg=T["green"] if success else T["red"],
                             background=T["green_dim"] if success else T["red_dim"])

    def _clear_history(self):
        self.history.clear()
        self.history_lb.delete(0, "end")
        self.hits = self.misses = 0
        self.reaction_times.clear()
        self.best_reaction = None
        for k, v in [("v_hits","0"),("v_misses","0"),("v_avg","—"),("v_best","—")]:
            self.stat_vars[k].set(v)

    # ── Key UI ─────────────────────────────────────────────────────────────────
    def _key_ui(self, ch, active):
        T = self.T
        f, l = self.key_frames.get(ch), self.key_labels.get(ch)
        if f and l:
            f.configure(bg=T["green"] if active else T["border"])
            l.configure(bg=T["green_dim"] if active else T["key_bg"],
                        fg=T["green"] if active else T["text_mid"])

    def destroy(self):
        try: self.kb.stop(); self.ms.stop()
        except Exception: pass
        super().destroy()


if __name__ == "__main__":
    StillnessTrainer().mainloop()
