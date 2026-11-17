# Pi TV

An IPTV player for the Pi with a Netflix-style interface, in French. It plays live channels,
films and series from an **Xtream Codes** login or an **M3U playlist**, with a programme guide.

## Use

| Page | What's on it |
|---|---|
| **Accueil** | Continuer à regarder, channels watched lately, Ma liste, recently added films and series |
| **TV en direct** | Categories, their channels with what's on now, and the highlighted channel's guide. The highlighted channel plays in a small muted preview once the highlight rests on it for a second |
| **Films**, **Séries** | A row per category, the most recent first, and the add-ons' catalogues; OK opens the details page |
| **Animes** | The anime add-ons' catalogues (Kitsu by default) |
| **Ma liste** | Favourite channels, films and series (Pause on a card adds or removes it) |
| **Recherche** | Channels, films and series whose name contains what you type, then the add-ons' results |
| **Paramètres** | Three tabs: **IPTV** (the provider, and the address for filling it in from a phone), **Add-ons** (the Stremio add-ons and what each brings) and **Plugins** (the Nuvio plugins) |

In the player:

| Key | Live channel | Film or episode |
|---|---|---|
| Up / Down | Previous / next channel in the category | Shows the controls |
| OK | List of the category's channels | Pause / play, or the controls |
| Left / Right | Controls (Pause, Audio, Sous-titres, Format); once paused, back 10 s / forward 10 s | Back 10 s / forward 10 s (faster when held) |
| Play / Pause | Pauses the channel, then resumes it where it was | Pause / play |
| Back | Hides the controls, then leaves | Same; the position is saved |

Audio lists the tracks of the link playing (a MULTI file has several), then the title's other
links by the language their names show (« Autre lien en VOSTFR (3 liens) »). Choosing one switches
to the best of them at the same position, and that language is preferred for later titles and
episodes.

On the Sources page, « Trier » (up from the list) orders the links by quality, by language (the
one chosen in the player first, then VF, MULTI, VOSTFR, VO) or by source, best quality first in
each group; the order is kept for next time. Each link shows its language and quality at the right.

On Pi Home, highlighting Pi TV shows its row: the films and episodes of Continuer à regarder,
then the recent channels. Choosing one opens Pi TV on it: a film's page, ready to resume, or the
channel playing. The app publishes them to the Android TV provider each time it comes back to the
front or the player stops; `install.sh` makes the channel visible (a stock launcher would ask).

### Pausing live TV

Pause on a channel records it on the Pi from that moment, and the picture stays on the moment
paused. Lecture resumes from there, behind live (« En différé », with how far behind). The arrows
then go back or forward 10 s, from the pause up to live; past the end, or with the Direct button,
it's live again. The recording keeps up to 2 GB (about an hour), and is dropped when you change
channel or leave the player.

- Normal viewing doesn't change: the recording starts only on Pause, and takes over the
  connection (providers often allow just one).
- It works on MPEG-TS streams: Xtream channels, `.ts` addresses, and HLS whose segments are
  MPEG-TS (most of iptv-org). An encrypted HLS stream, or one with separate audio, says « Pause
  indisponible sur cette chaîne » and resumes live.

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

## Add-ons

Pi TV speaks the [Stremio add-on protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md):
plain JSON at `{base}/manifest.json`, then `{base}/{catalog|meta|stream|subtitles}/{type}/{id}.json`.
Add-ons are added in Paramètres → Add-ons (a `manifest.json` link; `stremio://` works too) or on
the phone page (one link per line). Each one has a switch: switched off, it stays listed (greyed)
but brings no rows, search results, pages, links or subtitles until switched back on.

| Add-on resource | What Pi TV does with it |
|---|---|
| `catalog` | Rows in Films, Séries and Animes (up to 4 per add-on and kind); catalogues that take a search answer Recherche |
| `meta` | The details page: story, backdrop, cast, seasons and episodes |
| `stream` | OK on Lecture asks every add-on at once; one stream plays, several are offered. The next episode takes the same kind of stream (`bingeGroup`) by itself |
| `subtitles` | Added to the player, one per language: French and English, and the language chosen last |

The defaults play nothing: **Cinemeta** (films and series), **Anime Kitsu** (anime) and
**OpenSubtitles**. Streams come only from add-ons the user adds; Pi TV plays direct HTTP and HLS
links, not torrents. Add-on titles work like the provider's: Ma liste, resume points and
« Continuer à regarder » (which reopens the title's page, since stream links expire). Catalogues
and manifests are cached on disk for 6 hours; searches always ask the add-ons again.

## Plugins

The Plugins page takes repositories of [Nuvio](https://github.com/nuvionline) scrapers — small
JavaScript files that find French film, series and anime streams. Paste a `manifest.json` link
(Gowaru's repo: `https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json`).
The scripts download to `files/plugins/`, are listed with their versions, and can be switched off
one by one.

Each script exports `getStreams(tmdbId, mediaType, season, episode)`; Pi TV runs it in a QuickJS
engine (`quickjs-kt`) on a worker, with `fetch`, `console`, `atob`/`btoa`, `URL` and
`AbortController` wired to Java, and no `setTimeout` (the scripts busy-wait instead). The
collection stops after 32 s of results or 75 s per script; torrents and dead links are dropped.
A stream's `Referer`/`User-Agent` headers go to the player, and the next episode is looked up in
the same script (`bingeGroup = plugin:<repo>#<scraper>`). A scraper's answer for a title is kept
in memory for an hour (most links stay valid for hours; the cache drops when a script changes or
a repo is removed), so asking again is instant. The id that scripts want (a TMDB id)
is resolved by Cinemeta: the meta's `moviedb_id`, or a search on title and year.

Streams from plugins are asked at the same time as the add-ons'; on Lecture the two lists merge,
plugins after the add-ons, best quality first.

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
| `Preview` | The highlighted channel, small and muted; released before full screen, which needs the Pi's video decoder |
| `PlayerActivity` | Media3 playback, reconnects, zapping, tracks, resume points |
| `Timeshift` | A paused channel recorded to a ring file, and read back as a stream |
| `SettingsPage`, `SetupServer` | The provider's details, on the TV or from a phone |
| `Catalog` | Loads and caches the lists; search |
| `Addons` | Stremio add-ons: manifests, catalogues, details, streams, subtitles |
| `Plugins`, `PluginRuntime`, `PluginsPage` | Nuvio JS scrapers: repositories, the QuickJS engine, the Plugins page |
| `Tmdb` | Cinemeta ids for the scrapers (moviedb_id, search by title) |
| `Xtream`, `M3u`, `Epg` | The provider formats and the XMLTV guide |
| `Library` | Favourites, resume points and recent channels, one file per provider |
| `TvHome` | The launcher's row: Continuer à regarder (watch-next) and Chaînes récentes (a preview channel) |
| `Images` | Poster and logo loading with memory and disk caches |
