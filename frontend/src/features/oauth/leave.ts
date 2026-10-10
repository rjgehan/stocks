/** Leaves the SPA for the connecting app's redirect URI (a full navigation; its own module so tests can stub it). */
export function leaveTo(url: string): void {
  window.location.assign(url)
}
