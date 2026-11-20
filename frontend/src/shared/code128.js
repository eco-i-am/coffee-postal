// Code 128 barcodes (character set B: letters, digits, space and punctuation), for printing labels. Pure functions.
// Each entry is the widths of bar, space, bar, space, bar, space (the stop pattern has a 7th, a closing bar).

const PATTERNS = [
  "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
  "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
  "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
  "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
  "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
  "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
  "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
  "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
  "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
  "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
  "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
];
const START_B = 104;
const STOP = 106;

/** True if every character can be written in Code 128 B (printable ASCII). */
export const canEncode = (text) => typeof text === "string" && text.length > 0 && text.length <= 40 && /^[\x20-\x7E]+$/.test(text);

/** The symbol values in order: start, one per character, check digit, stop. */
export function code128Values(text) {
  if (!canEncode(text)) throw new Error("This text cannot be printed as a barcode.");
  const values = [START_B, ...[...text].map((c) => c.charCodeAt(0) - 32)];
  const sum = values.reduce((s, v, i) => s + (i === 0 ? v : v * i), 0);
  values.push(sum % 103, STOP);
  return values;
}

/** The barcode as a run of modules: "1" = bar, "0" = space. */
export function code128Modules(text) {
  let bar = true;
  let out = "";
  for (const v of code128Values(text)) {
    for (const w of PATTERNS[v]) {
      out += (bar ? "1" : "0").repeat(Number(w));
      bar = !bar;
    }
    bar = true; // every symbol starts with a bar
  }
  return out;
}

/** Bars as [x, width] runs, for drawing as SVG rectangles. */
export function code128Bars(text) {
  const m = code128Modules(text);
  const bars = [];
  for (let i = 0; i < m.length;) {
    if (m[i] === "1") { let j = i; while (j < m.length && m[j] === "1") j++; bars.push([i, j - i]); i = j; } else i++;
  }
  return { bars, width: m.length };
}
