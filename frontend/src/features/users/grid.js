// Permission grid logic. A grid is { [module]: { view, add, edit, delete } }.

export const ACTIONS = ["view", "add", "edit", "delete"];

export function gridFrom(permissions) {
  const g = {};
  for (const p of permissions || []) g[p.module] = { view: p.view, add: p.add, edit: p.edit, delete: p.delete };
  return g;
}

/** Turn one box on or off. Add/edit/delete need view; turning view off clears the others. */
export function toggle(grid, module, action, on) {
  const cur = grid[module] || { view: false, add: false, edit: false, delete: false };
  let next = { ...cur, [action]: on };
  if (on && action !== "view") next.view = true;
  if (!on && action === "view") next = { view: false, add: false, edit: false, delete: false };
  return { ...grid, [module]: next };
}

/** The list the API wants: only modules with something ticked. */
export function gridToPermissions(grid) {
  return Object.entries(grid)
    .filter(([, p]) => ACTIONS.some((a) => p[a]))
    .map(([module, p]) => ({ module, view: !!p.view, add: !!p.add, edit: !!p.edit, delete: !!p.delete }));
}

/** Everything in a module on, or off. */
export function setAll(grid, module, on) {
  return { ...grid, [module]: { view: on, add: on, edit: on, delete: on } };
}
