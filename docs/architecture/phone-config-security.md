# Configure from phone: design and threat model

Typing on a TV is slow, so Fetch serves small web pages from the TV that a phone opens by
scanning a QR code. This note covers what those pages can do and how they are protected.

## Pages

**Settings → Configure from phone** shows one QR code for the phone hub
(`core/server/PhoneHubServer.kt`, owned by `PhoneHubController`). Its landing page links to:

- **Remote and keyboard**: D-pad, OK, Back, play/pause, rewind and fast-forward. Text typed on
  the phone goes into the focused text field on the TV, and "Search on TV" opens search with
  the query. Commands go through `RemoteCommandBus` to `MainActivity`, which turns them into
  key events in Fetch's own window. Nothing is injected into other apps, and no accessibility
  or system permission is used.
- **Accounts**: TorBox and Premiumize API keys (checked with the provider before saving), the
  MDBList API key, Trakt and Simkl sign-in (the phone shows the device code; the TV finishes
  signing in) and Jellyfin/Emby sign-in.
- **Stream ranking**: the `StreamRankingRules` used by Best match.
- **Debrid name templates, stream badges and custom posters**: the existing pages, served under
  `/templates/`, `/badges/` and `/posters/`.

Addons, catalog order, collections and plugin repositories keep their own QR code on their TV
screen. Those changes decide what the TV loads, so each one is confirmed on the TV before it
is applied.

## Threat model

**Protected against**

- *Other devices on the same network* (housemates, guests, a compromised smart plug) reading
  or changing settings. Every server needs pairing: the QR code carries a random 128-bit
  one-time token (`?pair=…`). The first phone that opens it gets a random session cookie
  (`HttpOnly`, `SameSite=Strict`) and the token is spent. Requests without that cookie are
  refused. Scanning a spent code shows an error; reopening the screen on the TV makes a new
  server with a new code (`PairingGuard`).
- *Requests from outside the local network*, for example through a port forward or UPnP
  mapping. Servers only answer clients with loopback, link-local, private (RFC 1918), IPv6
  unique-local or carrier-grade NAT (100.64.0.0/10) addresses.
- *A web page in the phone's browser making requests to the TV* (cross-site request forgery
  and DNS rebinding). The session cookie is `SameSite=Strict` and never leaves the TV's
  origin, so other sites can't send it.
- *Long-lived exposure.* Servers run only while their TV screen is open. The hub keeps
  running after its screen closes only when a phone has paired, so the remote keeps working,
  and it stops after 10 minutes without requests.
- *Secrets leaking back out.* Account status only reports whether a key or sign-in exists;
  saved keys, tokens and passwords are never returned to the phone.
- *Children changing restricted profiles.* On a profile with parental limits, the hub screen
  needs the parent PIN, and account and ranking changes from the phone are refused unless the
  TV was unlocked in the last few minutes.

**Not protected against**

- *Someone who can watch the network traffic.* The pages use plain HTTP on the LAN, because a
  TV can't get a certificate a phone would trust. Wi-Fi encryption is the protection here. Keys
  typed on the phone travel to the TV unencrypted inside the local network.
- *Someone who can see the TV screen while the code is unused.* Anyone who scans the QR code
  first pairs. The TV shows how many phones are connected; close the screen to end access.
- *A compromised phone that has already paired.* It has the same access as the owner until the
  server stops.
