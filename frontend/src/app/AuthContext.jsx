import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { getMe, login as apiLogin, logout as apiLogout, setUnauthorizedHandler } from "../shared/api.js";

const Ctx = createContext(null);
export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }) {
  const [me, setMe] = useState(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setUnauthorizedHandler(() => setMe(null));
    getMe()
      .then(setMe)
      .catch(() => setMe(null))
      .finally(() => setReady(true));
  }, []);

  const signIn = useCallback(async (u, p) => {
    await apiLogin(u, p);
    setMe(await getMe());
  }, []);
  const signOut = useCallback(async () => {
    try {
      await apiLogout();
    } finally {
      setMe(null);
    }
  }, []);
  const can = useCallback((perm) => !!me && me.permissions.includes(perm), [me]);

  const value = useMemo(() => ({ me, ready, signIn, signOut, can }), [me, ready, signIn, signOut, can]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
