// Both halves of one contract, kept in the same file on purpose: qs() encodes every
// value, so decodeQuery() decodes every value.
//
// They used to live apart — qs() copy-pasted into three pages, the decode side a
// hand-written list of field names inside each onLoad — and the lists drifted. `start`
// was on none of them, so "10:00" arrived at the confirm page as "10%3A00" and rendered
// that way. Adding one more name to those lists would have repeated the mistake; the
// fix is to stop maintaining a list.
function qs(obj) {
  return Object.keys(obj)
    .filter((k) => obj[k] !== undefined && obj[k] !== '')
    .map((k) => `${k}=${encodeURIComponent(obj[k])}`)
    .join('&')
}

// onLoad hands back raw query values — the mini-program framework does not decode them.
// Guarded because a page can also be reached by a share link or scene QR whose params
// never went through qs(); a stray "%" there would otherwise throw inside onLoad and
// take the whole page down, and showing the raw value beats showing nothing.
function decodeQuery(query) {
  const out = {}
  Object.keys(query || {}).forEach((k) => {
    const v = query[k]
    if (typeof v !== 'string') {
      out[k] = v
      return
    }
    try {
      out[k] = decodeURIComponent(v)
    } catch (e) {
      out[k] = v
    }
  })
  return out
}

module.exports = {
  qs,
  decodeQuery,
}
