import { build } from "esbuild";
import { copyFile, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

// Pin both the source catalogue and renderer; never retrieve artwork at runtime.
const version = "0.4.0";
const root = fileURLToPath(new URL("../", import.meta.url));
const packageRoot = path.dirname(path.dirname(fileURLToPath(import.meta.resolve("ascii.rest"))));
const installed = JSON.parse(await readFile(path.join(packageRoot, "package.json"), "utf8"));
if (installed.version !== version) throw new Error(`Expected ascii.rest ${version}, found ${installed.version}`);
const pieceDir = path.join(packageRoot, "dist", "pieces");
const files = (await readdir(pieceDir)).filter((file) => file.endsWith(".js") && file !== "index.js").sort();
if (!files.length) throw new Error("The published ascii.rest catalogue is empty");
const pieces = [];
const imports = [];
const entries = [];
for (const [index, file] of files.entries()) {
  const id = file.slice(0, -3);
  const location = path.join(pieceDir, file);
  // The catalogue filenames are discovered from the installed package, not known at author time.
  const piece = await import(pathToFileURL(location).href);
  const { name, category, note, cols, rows, fps } = piece.meta;
  if (typeof piece.default !== "function" || typeof name !== "string" || typeof category !== "string" ||
      typeof note !== "string" || !Number.isInteger(cols) || cols <= 0 || !Number.isInteger(rows) || rows <= 0 ||
      !Number.isFinite(fps) || fps < 0) throw new Error(`Invalid upstream metadata: ${file}`);
  pieces.push({ id, name, category, note, cols, rows, fps });
  imports.push(`import * as p${index} from ${JSON.stringify(location)};`);
  entries.push(`[${JSON.stringify(id)}]:p${index}`);
}
const outputDir = path.join(root, "app", "src", "main", "assets", "ascii");
await mkdir(outputDir, { recursive: true });
await build({
  absWorkingDir: root,
  entryPoints: ["scripts/ascii-player.ts"],
  outfile: path.join(outputDir, "player.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome100",
  minify: true,
  legalComments: "inline",
  charset: "utf8",
  banner: { js: `/*! ascii.rest ${version} by bas3line (https://github.com/bas3line), MIT. See LICENSE. */` },
  plugins: [{
    name: "pinned-ascii-catalogue",
    setup(builder) {
      builder.onResolve({ filter: /^phosphor:catalog$/ }, () => ({ path: "catalog", namespace: "phosphor" }));
      builder.onLoad({ filter: /.*/, namespace: "phosphor" }, () => ({
        loader: "js",
        resolveDir: root,
        contents: `${imports.join("\n")}\nexport const catalogue = Object.freeze({${entries.join(",")}});`,
      }));
      builder.onResolve({ filter: /^phosphor:mount$/ }, () => ({ path: path.join(packageRoot, "dist", "mount.js") }));
    },
  }],
});
await writeFile(path.join(outputDir, "catalog.json"), JSON.stringify({ version, pieces }, null, 2) + "\n");
await copyFile(path.join(packageRoot, "LICENSE"), path.join(outputDir, "LICENSE"));
await writeFile(path.join(outputDir, "index.html"), `<!doctype html>
<!-- ascii.rest ${version}, copyright 2026 bas3line; MIT license in LICENSE. -->
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'none'; img-src 'none'; font-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
<title>Phosphor offline ASCII art — ascii.rest</title>
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden}body{display:flex;align-items:center;justify-content:center;background:#080b12;pointer-events:none;user-select:none}canvas{display:block;flex:none}#error{display:none;position:absolute;inset:0;margin:0;padding:12px;box-sizing:border-box;white-space:pre-wrap;font:14px monospace}</style>
</head><body><canvas id="art" aria-hidden="true"></canvas><pre id="error"></pre><script src="player.js"></script></body></html>
`);
console.log(`Bundled all ${pieces.length} ascii.rest ${version} pieces into ${path.relative(root, outputDir)}`);
