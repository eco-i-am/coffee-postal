import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { getMe, login as apiLogin, logout as apiLogout, setUnauthorizedHandler } from "../shared/api.js";
import { clearMe, loadMe, saveMe } from "../shared/offline.js";

const Ctx = createContext(null);
export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }) {
  const [me, setMe] = useState(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setUnauthorizedHandler(() => { clearMe(); setMe(null); });
    getMe()
      .then((m) => { saveMe(m); setMe(m); })
      // no internet: open as the person who was signed in, so the till still works.
      // A signed-out or expired session answers 401, which lands in the else branch as a normal sign-out.
      .catch((e) => { if (e.status === 0) setMe(loadMe()); else { clearMe(); setMe(null); } })
      .finally(() => setReady(true));
  }, []);

  const signIn = useCallback(async (u, p) => {
    await apiLogin(u, p);
    const m = await getMe();
    saveMe(m);
    setMe(m);
  }, []);
  const signOut = useCallback(async () => {
    try {
      await apiLogout();
    } finally {
      clearMe();
      setMe(null);
    }
  }, []);
  const can = useCallback((perm) => !!me && me.permissions.includes(perm), [me]);

  const value = useMemo(() => ({ me, ready, signIn, signOut, can }), [me, ready, signIn, signOut, can]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
