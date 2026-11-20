// Face check for the time clock. Runs on the tablet: the models are small files from our own site and no picture
// is sent anywhere for matching. The tablet turns a face into 128 numbers (a "descriptor"); the server compares
// those with the enrolled ones and only ever flags a punch for a manager to look at.
//
//   readFace(video) -> { state: "ok" | "none" | "multi" | "off", descriptor?: number[128] }
//     ok    one clear face
//     none  no face found      multi  more than one person in front of the camera
//     off   the check could not run (models not loaded, no camera picture)

const MODELS = "/models";
export const FACE_SIZE = 128;

let loading = null;

/** Loads the models once. Resolves to the face-api module, or null if it cannot load (the clock still works without it). */
export function loadFace() {
  if (!loading) {
    loading = (async () => {
      const faceapi = await import("@vladmandic/face-api");
      await faceapi.nets.tinyFaceDetector.loadFromUri(MODELS);
      await faceapi.nets.faceLandmark68TinyNet.loadFromUri(MODELS);
      await faceapi.nets.faceRecognitionNet.loadFromUri(MODELS);
      return faceapi;
    })().catch(() => { loading = null; return null; });
  }
  return loading;
}

const area = (d) => d.detection.box.width * d.detection.box.height;

/**
 * From everything the detector saw, the person at the tablet: the largest face. If another face is nearly as large
 * (more than 40% of its area) it is unclear who is punching, so that is "multi". People far behind are ignored.
 */
export function pickFace(detections) {
  if (!detections || detections.length === 0) return { state: "none" };
  const sorted = [...detections].sort((a, b) => area(b) - area(a));
  if (sorted.length > 1 && area(sorted[1]) > 0.4 * area(sorted[0])) return { state: "multi" };
  return { state: "ok", face: sorted[0] };
}

const sleep = (ms) => new Promise((r) => setTimeout(() => r(null), ms));

/** Reads the face in a camera preview (video) or in a loaded photo (img). Never throws and never waits long: on any trouble the state is "off". */
export async function readFace(video, { loadMs = 3000 } = {}) {
  try {
    if (!video || !(video.videoWidth || video.naturalWidth) || !(video.videoHeight || video.naturalHeight)) return { state: "off" };
    const faceapi = await Promise.race([loadFace(), sleep(loadMs)]);
    if (!faceapi) return { state: "off" };
    const found = await faceapi
      .detectAllFaces(video, new faceapi.TinyFaceDetectorOptions({ inputSize: 320, scoreThreshold: 0.5 }))
      .withFaceLandmarks(true)
      .withFaceDescriptors();
    const picked = pickFace(found);
    if (picked.state !== "ok") return { state: picked.state };
    return { state: "ok", descriptor: Array.from(picked.face.descriptor) };
  } catch {
    return { state: "off" };
  }
}

export function distance(a, b) {
  let s = 0;
  for (let i = 0; i < a.length; i++) { const x = a[i] - b[i]; s += x * x; }
  return Math.sqrt(s);
}

/** The average of several readings of the same face, which is steadier than any single one. */
export function averageDescriptors(list) {
  const n = list.length;
  const out = new Array(FACE_SIZE).fill(0);
  for (const d of list) for (let i = 0; i < FACE_SIZE; i++) out[i] += d[i] / n;
  return out;
}

/** Readings of one enrollment must be alike; a big spread means the camera saw a different person or a poor frame. */
export function consistent(list, max = 0.4) {
  for (let i = 0; i < list.length; i++) for (let j = i + 1; j < list.length; j++) if (distance(list[i], list[j]) > max) return false;
  return true;
}
