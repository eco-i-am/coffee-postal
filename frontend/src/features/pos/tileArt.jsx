// Drawings for the menu tiles. Static, bundled markup (never built from user input).
const BROWN = "#6B4333";
const CREMA = "#C99A6B";
const S = 'stroke="#205966" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round"';

const ART = {
  hot: `<ellipse cx="22" cy="43" rx="17" ry="3" fill="#205966" opacity=".16"/><path d="M8 21h28v8c0 8-6 13-14 13S8 37 8 29z" fill="#FFFEF8" ${S}/><ellipse cx="22" cy="21" rx="14" ry="4.2" fill="${BROWN}" ${S}/><path d="M22 24c-5-2-7-3-7-5 0-2 3-2 4-.6 1-1.4 2-1.4 3 0 1-1.4 4-1.4 4 .6 0 2-2 3-4 5z" fill="${CREMA}"/><path d="M36 24h2a3.6 3.6 0 010 7.2h-3" fill="none" ${S}/><path d="M16 11c-2.4 3 2.4 4 0 7.5M24 8c-2.4 3 2.4 4 0 8" fill="none" ${S}/>`,
  iced: `<path d="M12 14h24l-3.2 28H15.2z" fill="#FFFEF8"/><path d="M13.2 23h21.6l-2 19H15.2z" fill="${BROWN}"/><rect x="16.5" y="25" width="7" height="7" rx="1.5" fill="#F9F6E3" opacity=".85" transform="rotate(-8 20 28)"/><rect x="26" y="31" width="6" height="6" rx="1.5" fill="#F9F6E3" opacity=".85"/><path d="M12 14h24l-3.2 28H15.2z" fill="none" ${S}/><path d="M26 14l6-11" fill="none" ${S}/>`,
  frap: `<path d="M13 22h22l-3.2 20H16.2z" fill="${CREMA}"/><path d="M13 22h22l-3.2 20H16.2z" fill="none" ${S}/><path d="M10.5 22c0-7.5 6-11 13.5-11s13.5 3.5 13.5 11z" fill="#FFFEF8" ${S}/><path d="M15 17.5c5 2.4 13 2.4 18 0" stroke="${BROWN}" stroke-width="2.6" fill="none" stroke-linecap="round"/><path d="M24 11c-1.6-3 2-4 0-8" fill="none" ${S}/>`,
  pastry: `<path d="M5 31c2-13 10-19 19-19s17 6 19 19c-4 4-8 2-12 4-3 2-9 2-14 0-4-2-8 0-12-4z" fill="#FFC74A" ${S}/><path d="M16 17l3 16M24 14v20M32 17l-3 16" fill="none" ${S}/>`,
  cookie: `<circle cx="24" cy="25" r="16" fill="#FFC74A" ${S}/><circle cx="18" cy="20" r="2.8" fill="${BROWN}"/><circle cx="29" cy="23" r="2.8" fill="${BROWN}"/><circle cx="22" cy="31" r="2.8" fill="${BROWN}"/><circle cx="32" cy="32" r="2.2" fill="${BROWN}"/>`,
  sand: `<path d="M5 21l19-9 19 9v4H5z" fill="#FFC74A" ${S}/><path d="M5 25h38l-3 8H8z" fill="#F9F6E3" ${S}/><path d="M5 33h38v4H5z" fill="#FFA076" ${S}/>`,
};

export default function TileArt({ kind }) {
  const inner = ART[kind] || ART.hot;
  return (
    <svg
      width="56" height="56" viewBox="0 0 48 48" aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: inner }}
    />
  );
}
