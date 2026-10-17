import { catalogue } from "phosphor:catalog";
import { mount } from "phosphor:mount";
import type { Frame, Piece } from "ascii.rest";

// Both virtual imports are resolved by build-ascii.ts to pinned local modules.
const pieces: Record<string, Piece> = catalogue;
const canvas = document.getElementById("art") as HTMLCanvasElement;
const errorView = document.getElementById("error") as HTMLPreElement;
let config = { piece: "night-coast", mono: false, ink: "#b6ffb0", bg: "#080b12", paused: false, fps: 12 };
let source = pieces[config.piece];
let frame: Frame | null = null;
let unmount: (() => void) | null = null;
let frames = 0;
let error: string | null = null;
let initialized = false;
let playOffset = 0;
let started = performance.now();
let playing = false;

function playTime(now = performance.now()) {
  return playOffset + (playing ? (now - started) / 1000 : 0);
}

function retainTime(now = performance.now()) {
  playOffset = playTime(now);
  started = now;
}

function updateClock() {
  started = performance.now();
  playing = !config.paused && source.meta.fps > 0 && !document.hidden && !error;
}

function stop() {
  unmount?.();
  unmount = null;
}

function fail(cause: unknown) {
  retainTime();
  playing = false;
  stop();
  error = cause instanceof Error ? cause.message : String(cause);
  canvas.style.display = "none";
  errorView.textContent = `ASCII art could not be rendered.\n${error}\nUse Retry in the native artwork controls.`;
  errorView.style.display = "block";
}

function fit() {
  const ratio = source.meta.cols / (source.meta.rows * (source.meta.cell ?? 2));
  const width = Math.max(0, Math.min(window.innerWidth, window.innerHeight * ratio));
  canvas.style.width = `${width}px`;
  canvas.style.height = `${width / ratio}px`;
}

function status() {
  return { piece: config.piece, mono: config.mono, paused: config.paused, frames, error };
}

function configure(options: Partial<typeof config> = {}) {
  const requested = { ...config, ...options };
  if (!Object.hasOwn(pieces, requested.piece)) {
    fail(`Unknown bundled artwork: ${requested.piece}`);
    return status();
  }
  const color = (value: string, previous: string) => /^#[\da-f]{6}$/i.test(value) ? value : previous;
  const next = {
    piece: requested.piece,
    mono: Boolean(requested.mono),
    ink: color(requested.ink, config.ink),
    bg: color(requested.bg, config.bg),
    paused: Boolean(requested.paused),
    // Native passes 4 in battery saver; it can never raise the normal 12 FPS cap.
    fps: Number.isFinite(requested.fps) ? Math.max(1, Math.min(12, requested.fps)) : config.fps,
  };
  const switched = next.piece !== config.piece;
  const changed = switched || next.mono !== config.mono || next.ink !== config.ink || next.bg !== config.bg ||
    next.paused !== config.paused || next.fps !== config.fps;
  if (initialized && !changed) return status();
  retainTime();
  stop();
  config = next;
  source = pieces[config.piece];
  if (switched) {
    playOffset = 0;
    frames = 0;
    frame = null;
  }
  error = null;
  errorView.style.display = "none";
  canvas.style.display = "block";
  canvas.style.color = config.ink;
  errorView.style.color = config.ink;
  document.body.style.backgroundColor = config.bg;
  fit();
  try {
    // Reuse the real factory's closure across remounts: simulations keep their state.
    frame ??= source.default({ ...source.meta.options });
    updateClock();
    const original = frame;
    const wrapped: Piece = {
      meta: {
        ...source.meta,
        palette: config.mono ? undefined : source.meta.palette,
        ground: config.mono ? config.bg : source.meta.ground,
      },
      default: () => (_mountTime, env) => {
        // mount owns RAF, visibility and the glyph atlas. This independent offset
        // preserves actual play time even at 4 FPS (mount itself caps frame dt).
        const text = original(playTime(), env);
        frames++;
        return text;
      },
    };
    unmount = mount(canvas, wrapped, {
      fps: config.paused ? 0 : Math.min(source.meta.fps, config.fps),
      motion: true,
    });
    initialized = true;
  } catch (cause) {
    fail(cause);
  }
  return status();
}

window.addEventListener("resize", fit);
document.addEventListener("visibilitychange", () => {
  retainTime();
  updateClock();
});
// Errors from an upstream animation/resize callback remain explicit, not a
// silently frozen frame. Native retry recreates the local WebView document.
window.addEventListener("error", (event) => fail(event.error ?? event.message));
Object.defineProperty(window, "PhosphorArt", { value: Object.freeze({ configure, status }) });
