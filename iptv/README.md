# Pi TV

An IPTV player for the Pi with a Netflix-style interface, in French. It plays live channels,
films and series from an **Xtream Codes** login or an **M3U playlist**, with a programme guide.

## Use

| Page | What's on it |
|---|---|
| **Accueil** | Continuer à regarder, channels watched lately, Ma liste, recently added films and series |
| **TV en direct** | Categories, their channels with what's on now, and the highlighted channel's guide |
| **Films**, **Séries** | A row per category, the most recent first; OK opens the details page |
| **Ma liste** | Favourite channels, films and series (Pause on a card adds or removes it) |
| **Recherche** | Channels, films and series whose name contains what you type |
| **Paramètres** | The provider, and the address for filling it in from a phone |

In the player:

| Key | Live channel | Film or episode |
|---|---|---|
| Up / Down | Previous / next channel in the category | Shows the controls |
| OK | List of the category's channels | Pause / play, or the controls |
| Left / Right | Controls (Audio, Sous-titres, Format) | Back 10 s / forward 10 s (faster when held) |
| Play / Pause | Shows the info | Pause / play |
| Back | Hides the controls, then leaves | Same; the position is saved |

## Providers

- **Xtream Codes**: server (`http://host:port`), user name and password. Pi TV downloads the
  lists of channels, films and series (`player_api.php`). It fetches a film's plot and backdrop,
  or a series' episodes, when you open them. The guide comes from the provider per channel
  (`get_short_epg`), unless an XMLTV link is set.
- **M3U**: the playlist link. Groups become categories; `Movies;News` puts the channel in both. An
  entry is a film when its address has `/movie/`, or a video file extension in a group named like
  films. Names like `Show S01E02` are grouped into series. The guide comes from the playlist's
  `url-tvg` / `x-tvg-url`, or the XMLTV link in Paramètres.

The phone form runs only while Paramètres is on screen, on port 8765.

## Reliability

| What | How |
|---|---|
| Opening fast | The lists are kept on disk as the provider sent them; Pi TV opens on that copy and refreshes after 12 hours, or with Actualiser. A failed refresh keeps the old copy |
| Large lists | Read as a stream (tens of thousands of entries); tested with iptv-org's 11,399 channels: parsed in about 5 s, 90 MB of memory |
| A stream drops | The player reconnects by itself, up to 8 times with a growing pause, and says so on screen |
| A channel refused | A refused address (HTTP 4xx) isn't retried in place. An Xtream channel switches between MPEG-TS (`.ts`) and HLS (`.m3u8`), and the format that worked is remembered for the next channels |
| A live stream stalls | Restarted after 15 s of buffering; "behind live window" jumps back to live |
| Quality switches | The Pi's decoder fails when a stream changes quality mid-way, so the best quality is taken at once and kept |
| Sound | Hardware decoding first, FFmpeg (Jellyfin's Media3 build) for AC3, E-AC3, DTS and MP2 |
| Memory | The buffer is capped at 64 MB; posters are decoded at the size they're shown, with a 300 MB disk cache |
| Providers that check the app | The User-Agent is VLC's by default, and can be changed |

## Geo-blocked channels

Some channels only play from one country. Pi TV can send those through an HTTP proxy in that
country (Paramètres → Proxy, `host:port`), and only those: everything else stays direct.

- Channels marked `[Geo-blocked]` in their name (iptv-org's playlists do) go through the proxy.
- Any channel that fails directly is tried through the proxy, and back direct if the proxy is out
  of reach. A channel that only plays through the proxy is remembered.
- The channel's info line says « via le proxy ».

The proxy here is tinyproxy on the Hetzner server (Germany, so German and EU channels), on port
8888, reachable only over Tailscale: the Pi runs the Tailscale app, and the server's firewall
lets 8888 in on `tailscale0` only. Setting it up on another Ubuntu server:

```sh
apt-get install tinyproxy
# /etc/tinyproxy/tinyproxy.conf: Port 8888, Allow 127.0.0.1, Allow 100.64.0.0/10,
# DisableViaHeader Yes, and no ConnectPort lines (streams use many ports)
systemctl restart tinyproxy
```

Then `100.x.y.z:8888` (the server's Tailscale address) in Pi TV. A channel uses about 1–2 GB an
hour through it. US, UK or French channels need a proxy in those countries.

## Build and install

```sh
./install.sh        # builds, installs on the Pi (PI_HOST, default 192.168.1.116:5555), allows its network
```

It needs Android Studio's JDK and SDK (`local.properties`). The first build creates
`pitv.keystore`; back it up, since updates must be signed with the same key. It's left out of
Git.

## Code

| File | What it does |
|---|---|
| `MainActivity` | The menu, the pages, the hero at the top |
| `Rows` | Rows of posters that scroll sideways, the highlight pinned at the start |
| `DetailsView` | A film's or series' page: play, resume, Ma liste, seasons and episodes |
| `LivePage` | TV en direct: categories, channels, guide |
| `PlayerActivity` | Media3 playback, reconnects, zapping, tracks, resume points |
| `SettingsPage`, `SetupServer` | The provider's details, on the TV or from a phone |
| `Catalog` | Loads and caches the lists; search |
| `Xtream`, `M3u`, `Epg` | The provider formats and the XMLTV guide |
| `Library` | Favourites, resume points and recent channels, one file per provider |
| `Images` | Poster and logo loading with memory and disk caches |
