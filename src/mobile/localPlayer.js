// Full-screen in-app player for downloaded files (desktop opens VLC/mpv instead).
// Plays local HLS folders (index.m3u8 + segments) with hls.js and mp4 natively.
import Hls from "hls.js";
import { native, fileSrc } from "./native.js";

const P = native.plugin;

let current = null;

export function closeLocalPlayer() {
  if (!current) return false;
  const { root, hls, video, onClose } = current;
  try { onClose?.(video.currentTime || 0, video.duration || 0); } catch {}
  try { hls?.destroy(); } catch {}
  try { video.pause(); video.removeAttribute("src"); video.load(); } catch {}
  root.remove();
  current = null;
  try { P.setFullscreen({ on: false }); } catch {}
  try { P.setKeepScreenOn({ on: false }); } catch {}
  return true;
}
window.__sbLocalPlayerClose = closeLocalPlayer;

export function openLocalPlayer({ filePath, seconds = 0, subtitlePaths = [], title = "" }) {
  closeLocalPlayer();
  const root = document.createElement("div");
  root.className = "sb-local-player";
  root.style.cssText =
    "position:fixed;inset:0;z-index:2147483600;background:#000;display:flex;align-items:center;justify-content:center";
  const video = document.createElement("video");
  video.controls = true;
  video.autoplay = true;
  video.playsInline = true;
  video.setAttribute("playsinline", "");
  video.style.cssText = "width:100%;height:100%;object-fit:contain;background:#000";
  for (const [i, s] of (subtitlePaths || []).entries()) {
    if (!s?.path || !/\.vtt$/i.test(s.path)) continue; // <track> supports WebVTT only
    const t = document.createElement("track");
    t.kind = "subtitles";
    t.src = fileSrc(s.path);
    t.srclang = (s.lang || "und").slice(0, 8);
    t.label = s.lang || `Subtitle ${i + 1}`;
    if (i === 0) t.default = true;
    video.appendChild(t);
  }
  const close = document.createElement("button");
  close.setAttribute("aria-label", "Close player");
  close.innerHTML =
    '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>';
  close.style.cssText =
    "position:absolute;top:calc(12px + env(safe-area-inset-top));left:12px;width:44px;height:44px;border-radius:50%;" +
    "border:1px solid rgba(255,255,255,.15);background:rgba(0,0,0,.55);color:#fff;display:flex;align-items:center;" +
    "justify-content:center;z-index:2;transition:opacity .3s";
  close.onclick = () => closeLocalPlayer();
  if (title) {
    const label = document.createElement("div");
    label.textContent = title;
    label.style.cssText =
      "position:absolute;top:calc(22px + env(safe-area-inset-top));left:68px;right:16px;color:#fff;font:600 14px 'DM Sans',sans-serif;" +
      "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-shadow:0 1px 4px #000;pointer-events:none";
    root.appendChild(label);
  }
  root.appendChild(video);
  root.appendChild(close);
  document.body.appendChild(root);

  // auto-hide the close button while playing
  let hideT;
  const poke = () => {
    close.style.opacity = "1";
    clearTimeout(hideT);
    hideT = setTimeout(() => { if (!video.paused) close.style.opacity = "0"; }, 3000);
  };
  root.addEventListener("touchstart", poke, { passive: true });
  video.addEventListener("pause", poke);
  video.addEventListener("play", poke);

  const src = fileSrc(filePath);
  let hls = null;
  if (/\.m3u8$/i.test(filePath) && Hls.isSupported()) {
    hls = new Hls({ startPosition: seconds > 0 ? seconds : -1, maxBufferLength: 60 });
    hls.loadSource(src);
    hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
    hls.on(Hls.Events.ERROR, (_, d) => {
      if (d.fatal && d.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
    });
  } else {
    video.src = src;
    if (seconds > 0)
      video.addEventListener("loadedmetadata", () => (video.currentTime = seconds), { once: true });
    video.play().catch(() => {});
  }
  current = { root, hls, video };
  try { P.setFullscreen({ on: true }); } catch {}
  try { P.setKeepScreenOn({ on: true }); } catch {}
}
