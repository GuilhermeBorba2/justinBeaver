function isGoogleLogin(value) {
  try {
    const url = new URL(value)
    return url.origin === 'https://accounts.google.com' && url.pathname === '/o/oauth2/v2/auth'
      && !url.username && !url.password
  } catch {
    return false
  }
}

function isAppPage(value, origin) {
  try {
    return new URL(value).origin === origin
  } catch {
    return false
  }
}

module.exports = { isGoogleLogin, isAppPage }
