// Playback integration with Android:
//  - reports the active video (embedded player / in-app player) to the native
//    media session → lock-screen + notification controls, headset buttons
//  - enables picture-in-picture while something plays, and lays the page out
//    as "video only" while in PiP
//  - executes media commands (play/pause/seek) inside the player frame
//  - handles the in-app player's brightness / volume / pip / cast requests
import { native, isNative } from "./native.js";
import { allFrames, execInFrame } from "./MobileWebView.jsx";

const P = native.plugin;

const STATE_JS = `(() => {
  const v = document.querySelector('video');
  if (!v || !(v.duration > 0)) return null;
  return { t: v.currentTime, d: v.duration === Infinity ? 0 : v.duration, paused: v.paused,
           w: v.videoWidth || 16, h: v.videoHeight || 9 };
})()`;

const cmdJs = (body) => `(() => { const v = document.querySelector('video'); if (!v) return null; ${body}; return true; })()`;

function localPlayerFrame() {
  return document.querySelector("iframe[data-sb-local-player]");
}

/** The iframe that currently holds the video we control. */
function activeFrame() {
  const local = localPlayerFrame();
  if (local) return local;
  const frames = allFrames().filter((f) => {
    const src = f.getAttribute("src") || "";
    return src && src !== "about:blank" && f.offsetWidth > 0;
  });
  return frames[frames.length - 1] || null;
}

function currentTitle(frame) {
  if (frame?.hasAttribute("data-sb-local-player")) {
    try {
      return new URL(frame.src).searchParams.get("title") || "Streambert";
    } catch {}
  }
  const t = document.querySelector(".main .detail-title")?.textContent?.trim();
  return t || "Streambert";
}

function currentSubtitle() {
  // TV page: active episode label if present
  const ep = document.querySelector(".episode-item.active .episode-title, .ep-card--active .ep-card__title");
  return ep?.textContent?.trim() || "Streambert";
}

let last = { active: false, playing: false };
let lastFrame = null;
let pip = false;

async function poll() {
  const frame = activeFrame();
  let state = null;
  if (frame) {
    try {
      state = await execInFrame(frame, STATE_JS, { deep: true, timeout: 1500 });
    } catch {}
  }
  lastFrame = state ? frame : null;
  const active = !!state;
  const playing = active && !state.paused;
  const payload = active
    ? {
        active,
        playing,
        title: currentTitle(frame),
        subtitle: currentSubtitle(),
        position: state.t || 0,
        duration: state.d || 0,
        aspect: state.w && state.h ? state.w / state.h : 16 / 9,
      }
    : { active: false, playing: false };
  // only talk to native when something changed (position updates every 5s)
  const now = Date.now();
  if (
    payload.active !== last.active ||
    payload.playing !== last.playing ||
    payload.title !== last.title ||
    (active && now - (last.at || 0) > 5000)
  ) {
    last = { ...payload, at: now };
    try { await P.setPlaybackState(payload); } catch {}
  }
}

async function command(cmd, value) {
  const frame = lastFrame || activeFrame();
  if (!frame) return;
  let js;
  if (cmd === "play") js = cmdJs("v.play()");
  else if (cmd === "pause") js = cmdJs("v.pause()");
  else if (cmd === "seek") js = cmdJs(`v.currentTime = ${Number(value) || 0}`);
  else if (cmd === "skip") js = cmdJs(`v.currentTime = Math.max(0, v.currentTime + (${Number(value) || 0}))`);
  if (!js) return;
  try {
    await execInFrame(frame, js, { deep: true, timeout: 1500 });
  } catch {}
  setTimeout(poll, 300);
}

function setPipLayout(on) {
  pip = on;
  const root = document.documentElement;
  document.querySelectorAll("[data-sb-pip-target]").forEach((el) => el.removeAttribute("data-sb-pip-target"));
  if (on) {
    const f = lastFrame || activeFrame();
    if (f) f.setAttribute("data-sb-pip-target", "1");
    root.setAttribute("data-pip", "1");
  } else {
    root.removeAttribute("data-pip");
  }
}

export function enterPip() {
  return P.enterPip().catch(() => ({ ok: false }));
}

/** cast handler is installed by electronShim (needs stream referer info) */
let castHandler = null;
export function setCastHandler(fn) {
  castHandler = fn;
}

if (isNative) {
  setInterval(poll, 1000);
  native.on("mediaCommand", (d) => command(d.cmd, d.value));
  native.on("pip", (d) => setPipLayout(!!d.value));

  // messages from the in-app player page (public/sb-player.html)
  window.addEventListener("message", async (ev) => {
    const d = ev.data;
    if (!d || d.__sb !== "player") return;
    const reply = (msg) => {
      try { ev.source?.postMessage(msg, "*"); } catch {}
    };
    switch (d.cmd) {
      case "brightness":
        P.setBrightness({ value: Number(d.value) }).catch(() => {});
        break;
      case "volume":
        P.setVolume({ value: Number(d.value) }).catch(() => {});
        break;
      case "getLevels":
        try {
          const l = await P.getLevels();
          reply({ __sb: "playerLevels", brightness: l.brightness, volume: l.volume });
        } catch {
          reply({ __sb: "playerLevels", brightness: 0.5, volume: 1 });
        }
        break;
      case "pip":
        enterPip();
        break;
      case "cast": {
        // which frame asked? local player → its file; embedded → current stream
        const frame = [...document.querySelectorAll("iframe")].find((f) => f.contentWindow === ev.source);
        castHandler?.({ frame, t: Number(d.t) || 0 });
        break;
      }
    }
  });

  // Leaving the player → screen brightness back to the system value
  const restoreBrightness = () => P.setBrightness({ value: -1 }).catch(() => {});
  window.addEventListener("sb:local-player-closed", restoreBrightness);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) poll();
  });
}

export function getPlaybackState() {
  return { ...last, pip };
}
