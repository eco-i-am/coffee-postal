import { useState } from "react";

/** A password box with a Show / Hide button. Takes the same props as an <input>. */
export default function PasswordInput({ className = "", ...props }) {
  const [shown, setShown] = useState(false);
  return (
    <span className={`pwbox ${className}`.trim()}>
      <input {...props} type={shown ? "text" : "password"} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
      <button type="button" className="pwtoggle" aria-pressed={shown} aria-label={shown ? "Hide password" : "Show password"} onClick={() => setShown((s) => !s)}>
        {shown ? "Hide" : "Show"}
      </button>
    </span>
  );
}
