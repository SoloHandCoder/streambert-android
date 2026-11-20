// Build a signed release APK and publish it as a GitHub release.
//
//   node scripts/release.mjs 2.7.1 [--notes "text" | --notes-file notes.md] [--no-publish]
//
// Steps: bump version (package.json + android/app/build.gradle) → vite build →
// cap sync → gradlew assembleRelease → copy APK to release/ → git commit, tag,
// push → `gh release create v<version>-android` with the APK attached.
// Requires: android/keystore.properties (signing), gh CLI logged in.
import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const version = args[0];
if (!/^\d+\.\d+\.\d+$/.test(version || "")) {
  console.error("usage: node scripts/release.mjs <x.y.z> [--notes text | --notes-file file] [--no-publish]");
  process.exit(1);
}
const opt = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
};
const publish = !args.includes("--no-publish");
const run = (cmd, cwd = root) => {
  console.log(`\n$ ${cmd}`);
  execSync(cmd, { cwd, stdio: "inherit", env: process.env });
};

if (!fs.existsSync(path.join(root, "android", "keystore.properties"))) {
  console.error("android/keystore.properties is missing: the release APK would be unsigned.");
  process.exit(1);
}

// ── bump version ──
const pkgPath = path.join(root, "package.json");
const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
pkg.version = version;
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + "\n");

const gradlePath = path.join(root, "android", "app", "build.gradle");
const [maj, min, pat] = version.split(".").map(Number);
const code = maj * 10000 + min * 100 + pat;
let gradle = fs.readFileSync(gradlePath, "utf8");
gradle = gradle
  .replace(/versionCode \d+/, `versionCode ${code}`)
  .replace(/versionName "[^"]*"/, `versionName "${version}"`);
fs.writeFileSync(gradlePath, gradle);
console.log(`version → ${version} (versionCode ${code})`);

// ── build ──
run("npx vite build");
run("npx cap sync android");
const androidDir = path.join(root, "android");
const gradlew = path.join(androidDir, process.platform === "win32" ? "gradlew.bat" : "gradlew");
run(`"${gradlew}" assembleRelease --no-daemon -q`, androidDir);

const built = path.join(root, "android", "app", "build", "outputs", "apk", "release", "app-release.apk");
const outDir = path.join(root, "release");
fs.mkdirSync(outDir, { recursive: true });
const apkName = `Vidra-Android-${version}.apk`;
const apk = path.join(outDir, apkName);
fs.copyFileSync(built, apk);
console.log(`\nAPK: ${apk}`);

if (!publish) process.exit(0);

// ── commit, tag, push, release ──
const tag = `v${version}-android`;
run("git add -A");
try {
  run(`git commit -m "Release ${version}"`);
} catch {
  console.log("(nothing to commit)");
}
run(`git tag -f ${tag}`);
run("git push origin HEAD");
run(`git push -f origin ${tag}`);

let notesArg;
const notesFile = opt("--notes-file");
const notes = opt("--notes");
if (notesFile) notesArg = `--notes-file "${path.resolve(notesFile)}"`;
else if (notes) notesArg = `--notes "${notes.replace(/"/g, '\\"')}"`;
else notesArg = "--generate-notes";
run(`gh release create ${tag} "${apk}" --title "Vidra Android ${version}" ${notesArg}`);
console.log(`\nPublished ${tag}`);
