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
  for (const [index,name] of ["ada","grace","nadia","omar"].entries()) {
    const skin=["#d6a383","#c48765","#8c5d46","#b48362"][index];
    const base64=await page.evaluate(async ({index,skin})=>{
      const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><rect width="512" height="512" fill="${["#446279","#5f4978","#496d69","#635a45"][index]}"/><path d="M60 512Q85 345 256 345Q430 345 452 512" fill="#172a39"/><ellipse cx="256" cy="224" rx="132" ry="162" fill="${skin}"/><path d="M119 210Q110 60 256 48Q405 68 389 202L351 133Q251 181 158 134Z" fill="#202027"/><ellipse cx="207" cy="218" rx="14" ry="10" fill="#202027"/><ellipse cx="302" cy="218" rx="14" ry="10" fill="#202027"/><path d="M208 292Q256 325 307 290" fill="none" stroke="#64392f" stroke-width="10" stroke-linecap="round"/><path d="M252 233L241 265L267 265" fill="none" stroke="#8c5d46" stroke-width="7"/></svg>`;
      const image=new Image();image.src="data:image/svg+xml;charset=utf-8,"+encodeURIComponent(svg);await image.decode();
      const canvas=document.createElement("canvas");canvas.width=512;canvas.height=512;canvas.getContext("2d").drawImage(image,0,0);return canvas.toDataURL("image/jpeg",.95).split(",")[1];
    },{index,skin});
    await writeFile(resolve(output,name+".jpg"),Buffer.from(base64,"base64"));
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
// Two named audio tracks and two text tracks exercise the real player pickers.
await writeFile(resolve(output, "full.srt"), "1\n00:00:00,000 --> 00:00:29,000\nFixture dialogue\n");
await writeFile(resolve(output, "signs.srt"), "1\n00:00:00,000 --> 00:00:29,000\nFixture sign\n");
execFileSync("ffmpeg", [
  "-loglevel", "error", "-i", resolve(output, "sample.mp4"),
  "-i", resolve(output, "full.srt"), "-i", resolve(output, "signs.srt"),
  "-map", "0:v", "-map", "0:a", "-map", "0:a", "-map", "1", "-map", "2",
  "-c:v", "copy", "-c:a", "copy", "-c:s", "srt",
  "-metadata:s:a:0", "language=jpn", "-metadata:s:a:0", "title=Original stereo",
  "-metadata:s:a:1", "language=jpn", "-metadata:s:a:1", "title=Commentary",
  "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=Full subtitles",
  "-metadata:s:s:1", "language=eng", "-metadata:s:s:1", "title=Signs and songs",
  "-disposition:a:0", "default", "-disposition:a:1", "0",
  "-disposition:s:0", "default", "-disposition:s:1", "0",
  "-y", resolve(output, "tracks.mkv"),
]);
// MP4 handler names are not exposed as labels by Media3; retain a duplicate-name case.
execFileSync("ffmpeg", [
  "-loglevel", "error", "-i", resolve(output, "tracks.mkv"), "-map", "0",
  "-c", "copy", "-c:s", "mov_text", "-movflags", "+faststart",
  "-y", resolve(output, "tracks.mp4"),
]);
// A WMV file in an ASF container, which Media3 cannot open, exercises "When a file can't play
// directly". The HLS copy of sample.mp4 stands in for Plex's universal transcoder output.
execFileSync("ffmpeg", [
  "-hide_banner", "-loglevel", "error", "-i", resolve(output, "sample.mp4"),
  "-vf", "scale=640:360", "-c:v", "wmv2", "-b:v", "600k", "-c:a", "wmav2", "-b:a", "96k",
  "-y", resolve(output, "unplayable.wmv"),
]);
await mkdir(resolve(output, "conversion"), { recursive: true });
execFileSync("ffmpeg", [
  "-hide_banner", "-loglevel", "error", "-i", resolve(output, "sample.mp4"),
  "-c", "copy", "-bsf:v", "h264_mp4toannexb", "-f", "hls", "-hls_time", "6",
  "-hls_list_size", "0", "-hls_segment_filename", resolve(output, "conversion", "segment-%d.ts"),
  "-y", resolve(output, "conversion", "index.m3u8"),
]);
console.log(output);
