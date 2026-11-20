import { createContext, useContext } from "react";
import { api } from "./api.js";

/**
 * Which `api` a screen talks to. Normally the real one. A practice screen wraps its children in
 * <ApiContext.Provider value={fakeApi}> so the real components can be used for a pretend run that never reaches the server.
 */
export const ApiContext = createContext(api);
export const useApi = () => useContext(ApiContext);
