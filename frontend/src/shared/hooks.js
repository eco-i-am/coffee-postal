import { useCallback, useEffect, useState } from "react";

/** Loads data from the API and lets the page reload it after a change. */
export function useLoad(loader) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setData(await loader());
      setError("");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [loader]);

  useEffect(() => {
    reload();
  }, [reload]);

  return { data, error, loading, reload };
}
