/**
 * Accept only same-origin application paths from a redirect query parameter.
 * Auth redirects are user-controlled input and must never become an open redirect.
 */
export const resolveSafeRedirect = (value: unknown, fallback = '/app') => {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) {
    return fallback
  }

  try {
    const resolved = new URL(value, window.location.origin)
    if (resolved.origin !== window.location.origin || resolved.username || resolved.password) {
      return fallback
    }
    return `${resolved.pathname}${resolved.search}${resolved.hash}`
  } catch {
    return fallback
  }
}
