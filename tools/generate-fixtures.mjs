// Requires Playwright Core 1.64.0 and ffmpeg on the fixture-generation host.
import { createRequire } from "node:module";
import { mkdir, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright-core");
const output = resolve(process.argv[2] || "__screenshots__/fixtures");
const executablePath = process.env.CHROMIUM_EXECUTABLE;
if (!executablePath)
  throw new Error("Set CHROMIUM_EXECUTABLE to the installed Chromium path");
await mkdir(output, { recursive: true });
const browser = await chromium.launch({
  executablePath,
  args: ["--no-sandbox"],
});
try {
  const page = await browser.newPage();
  for (const [filename, width] of [
    ["low.jpg", 240],
    ["high.jpg", 1440],
  ]) {
    const base64 = await page.evaluate(async (width) => {
      const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="2160"><rect width="1440" height="2160" fill="#112c45"/><circle cx="990" cy="760" r="480" fill="#527f89"/><ellipse cx="990" cy="760" rx="740" ry="115" transform="rotate(-24 990 760)" fill="none" stroke="#c9e0ce" stroke-width="24"/><path d="M0 1900L650 1450L1050 1760L1440 1480V2160H0Z" fill="#102332"/><text x="100" y="1900" fill="#f0f2ed" font-family="sans-serif" font-size="140" font-weight="bold">Orbit Nine</text><text x="100" y="2050" fill="#bfc9cf" font-family="sans-serif" font-size="36">QUEUEPILOT FICTIONAL FIXTURE</text></svg>';
      const image = new Image();
      image.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
      await image.decode();
      const canvas = document.createElement("canvas");
      canvas.width = width;
      canvas.height = width * 1.5;
      canvas
        .getContext("2d")
        .drawImage(image, 0, 0, canvas.width, canvas.height);
      return canvas
        .toDataURL("image/jpeg", width === 240 ? 0.55 : 0.94)
        .split(",")[1];
    }, width);
    await writeFile(resolve(output, filename), Buffer.from(base64, "base64"));
  }
} finally {
  await browser.close();
}
execFileSync("ffmpeg", [
  "-hide_banner",
  "-loglevel",
  "error",
  "-f",
  "lavfi",
  "-i",
  "testsrc2=size=1280x720:rate=24",
  "-f",
  "lavfi",
  "-i",
  "sine=frequency=440:sample_rate=48000",
  "-t",
  "30",
  "-c:v",
  "libx264",
  "-preset",
  "ultrafast",
  "-crf",
  "28",
  "-pix_fmt",
  "yuv420p",
  "-c:a",
  "aac",
  "-ac",
  "2",
  "-movflags",
  "+faststart",
  "-y",
  resolve(output, "sample.mp4"),
]);
console.log(output);
