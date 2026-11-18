// The time clock takes a small photo with every time in and time out. Needs a secure page (https or localhost)
// and the person's permission; if either is missing the punch still works, without a photo.

/** Opens the front camera into a <video> element. Resolves to the stream, or null if the camera cannot be used. */
export async function startCamera(video) {
  try {
    if (!navigator.mediaDevices?.getUserMedia) return null;
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } }, audio: false });
    video.srcObject = stream;
    await video.play();
    return stream;
  } catch {
    return null;
  }
}

export function stopCamera(stream) {
  if (stream) stream.getTracks().forEach((t) => t.stop());
}

/** The current frame as a small JPEG (about 320 px wide), as base64 with no prefix. Null if there is no picture yet. */
export function captureJpeg(video, width = 320, quality = 0.6) {
  try {
    if (!video || !video.videoWidth || !video.videoHeight) return null;
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = Math.round((width * video.videoHeight) / video.videoWidth);
    canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);
    const url = canvas.toDataURL("image/jpeg", quality);
    return url.startsWith("data:image/jpeg") ? url.split(",")[1] : null;
  } catch {
    return null;
  }
}
