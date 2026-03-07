# Cover Image DNS Issues

## Problem

Book cover images may fail to load in certain browsers depending on the user's
DNS configuration. The symptom is cover images showing `?` placeholders instead
of the actual cover art, with `net::ERR_NAME_NOT_RESOLVED` errors in the browser
console.

## Root Cause

Cover images are served from `covers.openlibrary.org`, which redirects to
Internet Archive URLs like:

```
https://ia800502.us.archive.org/view_archive.php?archive=/31/items/m_covers_0013/m_covers_0013_17.zip&file=0013174792-M.jpg
```

Some DNS filtering services (e.g., Clean Browsing family filter) block these
`*.us.archive.org` domains, likely flagging the `archive.org` subdomain or the
URL pattern as suspicious. The initial `covers.openlibrary.org` request succeeds,
but the redirect target fails DNS resolution.

## Why It Affects Chrome but Not Safari

Chrome uses its own DNS resolver, which respects DNS-level filters configured on
the network or in Chrome's secure DNS settings (`chrome://settings/security`).
Safari uses the macOS system DNS resolver, which may be configured differently.

## Diagnosis

1. Open the browser console — look for `net::ERR_NAME_NOT_RESOLVED` errors
   pointing to `archive.org` or `*.us.archive.org` domains
2. Check the Network tab for failed image requests (red entries)
3. Check DNS settings: `chrome://settings/security` > "Use secure DNS"

## Workarounds

- Disable or change the DNS filtering service
- Switch Chrome's secure DNS provider (`chrome://settings/security`)
- Clear Chrome's DNS cache at `chrome://net-internals/#dns`

## Potential Future Fix

Proxy cover images through the application server to avoid client-side DNS
issues with third-party domains. Not currently implemented.
