import { useEffect } from "react";

/** A simple dialog: click outside or press Escape to close. */
export default function Modal({ title, onClose, wide, children, onSubmit }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  const cls = `modal${wide ? " wide" : ""}`;
  const inner = <><h2>{title}</h2>{children}</>;
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      {onSubmit
        ? <form className={cls} role="dialog" aria-modal="true" aria-label={title} onSubmit={onSubmit} noValidate>{inner}</form>
        : <div className={cls} role="dialog" aria-modal="true" aria-label={title}>{inner}</div>}
    </div>
  );
}
