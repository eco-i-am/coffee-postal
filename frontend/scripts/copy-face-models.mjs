// Copies the three small face models the time clock uses from the installed package into public/models,
// so they are served from our own site (no third party, and the tablet can keep them for offline use).
// Runs before dev and before build. public/models is not committed.
import { cpSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const from = join(root, "node_modules", "@vladmandic", "face-api", "model");
const to = join(root, "public", "models");
const files = [
  "tiny_face_detector_model",
  "face_landmark_68_tiny_model",
  "face_recognition_model",
];

if (!existsSync(from)) {
  console.error("face-api models not found. Run npm install first.");
  process.exit(1);
}
mkdirSync(to, { recursive: true });
for (const f of files) {
  cpSync(join(from, `${f}-weights_manifest.json`), join(to, `${f}-weights_manifest.json`));
  cpSync(join(from, `${f}.bin`), join(to, `${f}.bin`));
}
console.log(`face models copied to public/models (${files.length})`);
