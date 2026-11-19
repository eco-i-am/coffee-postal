import { describe, expect, it } from "vitest";
import { averageDescriptors, consistent, distance, FACE_SIZE, pickFace, readFace } from "./face.js";

const box = (w, h) => ({ detection: { box: { width: w, height: h } } });
const vec = (seed, nudge = 0) => Array.from({ length: FACE_SIZE }, (_, i) => Math.sin(seed + i) * 0.1 + (i === 0 ? nudge : 0));

describe("picking the person at the tablet", () => {
  it("says none when nobody is seen", () => {
    expect(pickFace([]).state).toBe("none");
    expect(pickFace(null).state).toBe("none");
  });
  it("takes the one face, or the largest when others are far behind", () => {
    const near = box(200, 200);
    expect(pickFace([near]).face).toBe(near);
    expect(pickFace([box(40, 40), near]).face).toBe(near);   // a person far behind is ignored
  });
  it("says multi when two faces are about the same size", () => {
    expect(pickFace([box(200, 200), box(180, 180)]).state).toBe("multi");
  });
});

describe("face numbers", () => {
  it("averages readings and measures distance", () => {
    const a = vec(1);
    const b = vec(1, 0.2);
    const avg = averageDescriptors([a, b]);
    expect(avg[0]).toBeCloseTo(a[0] + 0.1, 6);
    expect(distance(a, a)).toBe(0);
    expect(distance(a, b)).toBeCloseTo(0.2, 6);
  });
  it("only accepts readings of the same face", () => {
    expect(consistent([vec(1), vec(1, 0.05), vec(1, 0.1)])).toBe(true);
    expect(consistent([vec(1), vec(9)])).toBe(false);
  });
});

describe("when the check cannot run", () => {
  it("reports off instead of throwing, so a punch still goes through", async () => {
    expect((await readFace(null)).state).toBe("off");
    expect((await readFace({ videoWidth: 0, videoHeight: 0 })).state).toBe("off");
  });
});
