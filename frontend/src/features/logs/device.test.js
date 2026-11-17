import { describe, it, expect } from "vitest";
import { deviceName, prettyJson, actionName } from "./device.js";

describe("log helpers", () => {
  it("names devices", () => {
    expect(deviceName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")).toBe("Chrome on Windows");
    expect(deviceName("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537 Edg/120")).toBe("Edge on Windows");
    expect(deviceName("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit Version/17 Safari/604")).toBe("Safari on iOS");
    expect(deviceName("Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537")).toBe("Chrome on Android");
    expect(deviceName(null)).toBe("Unknown");
    expect(deviceName("curl/8.0")).toBe("Other");
  });
  it("pretty-prints JSON and tolerates junk", () => {
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyJson("not json")).toBe("not json");
    expect(prettyJson(null)).toBe("");
  });
  it("names actions", () => {
    expect(actionName("USER_UNLOCK")).toBe("Account unlocked");
    expect(actionName("PRICE_CHANGE")).toBe("Price change");
  });
});
