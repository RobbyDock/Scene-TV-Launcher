# Jellyfin and Plex as optional sources

Status: built in 0.2.0. Stremio is recommended; Jellyfin and Plex are experimental.

## Goal

Scene watches with exactly one app: Stremio, Jellyfin or Plex. Stremio stays the default. Continue watching, the Spotlight, Search and Popular all come from that one app, and titles open in it. Sources never mix.

## Design

- Settings: the "Stremio" group becomes "Watching", with two rows:
  - "Watch With" picks the app.
  - The second row is that app's account: Stremio Account, Jellyfin Account or Plex Account.
- The Watch With pane previews Continue watching with the chosen app's mark, and lists the three apps with a check on the current one.
- Switching asks first. Scene signs in to the new app, and signs out of the old one only once the new one works. Cancelling keeps the old app. Only one token is ever stored.
- Disconnecting Jellyfin or signing out of Plex goes back to Stremio.
- The Spotlight and the long-press menu use the app's own name and words:
  - Stremio: library.
  - Jellyfin: favourites.
  - Plex: Watchlist.
- New episode alerts and trailers stay Stremio only. Jellyfin and Plex have Next Up instead.
- Search shows "Popular on Stremio", or "Recently added to Jellyfin" or "Recently added to Plex".
- Jellyfin sign-in:
  - Pick a server found on the network, or enter an address.
  - Then a six-digit Quick Connect code is approved in any signed-in Jellyfin app.
  - Name and password is the fallback.
- Plex sign-in:
  - A four-character code entered at plex.tv/link.
  - Then pick a server when the account sees more than one.
- Account panes:
  - Switch: Include Next Up.
  - Buttons: Refresh Now and Disconnect, or Sign Out for Plex.
  - Plex adds a Server row.

## Library

On Jellyfin and Plex, the library is the reason to use the app, so Scene shows it. For Stremio it is just a list of saved titles, so it is off by default there.

- **Home:** library rows sit between Continue watching and the app rows. That makes them the top of Home's second page.
  - Jellyfin and Plex: Recently added, Movies and Shows.
  - Stremio: Your library.
  - Each row ends with a See all tile.
- **Library screen:**
  - Opened from See all, or from the Library setting.
  - Movies and Shows tabs, with a sort: Recently added, A to Z, Release year or Unwatched.
  - A seven-column poster grid, with watched ticks and progress bars.
  - Holding OK opens Play, Mark watched, favourite or Watchlist, and Details.
- **Settings:** a "Library on Home" row in the Watching group.
  - Per profile, with a preview of the rows.
  - Switches for Show on Home and for each row, plus Open Library.
- **Data:**
  - Jellyfin:
    - `GET /UserViews` lists the libraries.
    - `GET /Items/Latest?parentId=` gives Recently added.
    - `GET /Items?ParentId=&SortBy=&Filters=IsUnplayed` fills a library.
  - Plex:
    - `GET /library/sections` lists the libraries.
    - `GET /library/recentlyAdded` gives Recently added.
    - `GET /library/sections/{id}/all?sort=` fills a library.
  - Stremio: the library items Scene already downloads.
- **Paging:** the grid loads 100 titles at a time, as Search does. Posters use the existing image cache.
- **Later, maybe:** use the server's own library names instead of Movies and Shows, since Jellyfin users often name their libraries.

## Code shape

- A `Source` interface. Stremio, Jellyfin and Plex each implement it:
  - `signedIn`, `signOut`.
  - The link flow: `createLink`, `readLink`, `completeSignIn`.
  - `fetchContinue`, `meta`, `search`, `popular`.
  - `markWatched`, `setInLibrary`.
  - `playIntent`, `detailIntent`.
- `Stremio.kt` moves behind the interface first, with no change in behaviour.
- `Watch.current(ctx)` returns the one active source. Every caller that now names `Stremio` asks it instead.
- `CwItem` gets a `source` field. Ids get a source prefix (`jf:`, `px:`) so they never clash.
- One cache file per source. Switching apps clears the old cache, with no merging.
- Artwork goes through `Images` as now:
  - Jellyfin image URLs need no token.
  - Plex image URLs carry the server token, so they must stay out of logs and crash reports.

## Jellyfin

- Discovery: UDP broadcast "who is JellyfinServer?" on port 7359, read replies for 2 seconds. Manual address entry is the fallback.
- Every call sends the header `Authorization: MediaBrowser Client="Scene", Device=..., DeviceId=..., Version=..., Token=...`.
- Quick Connect:
  1. `GET /QuickConnect/Enabled`
  2. `POST /QuickConnect/Initiate`, which returns a code and a secret.
  3. Poll `GET /QuickConnect/Connect?secret=`.
  4. `POST /Users/AuthenticateWithQuickConnect`, which returns the access token and user.
- Password fallback: `POST /Users/AuthenticateByName`.
- Continue watching:
  - `GET /UserItems/Resume` on 10.9 and later, or `/Users/{id}/Items/Resume` on older servers.
  - Next Up comes from `GET /Shows/NextUp`.
- Details: `GET /Items/{id}`. Images: `/Items/{id}/Images/Backdrop`, `Primary` and `Logo`.
- Mark watched: `POST /UserPlayedItems/{id}`. Favourites: `POST` and `DELETE /UserFavoriteItems/{id}`.
- Search: `GET /Items?searchTerm=...&IncludeItemTypes=Movie,Series&Recursive=true`.
- Launch:
  - The app is `org.jellyfin.androidtv`. Opening one title through a VIEW intent with the item id is reported to work, but is not documented.
  - Check it on the TV with adb first. If it fails, open the app's home.
- Most home servers speak plain HTTP on the LAN. Android blocks that by default, so Scene needs a network security config that allows cleartext. That is a security change and needs your OK.

## Plex

- PIN sign-in:
  1. `POST https://plex.tv/api/v2/pins` with `X-Plex-Product: Scene` and a stable `X-Plex-Client-Identifier`.
  2. Show the code. The user enters it at plex.tv/link.
  3. Poll `GET /api/v2/pins/{id}` until `authToken` arrives.
- Servers: `GET https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1`. Each server comes with its own token. Connections are tried in this order: local, remote, relay.
- Continue watching: `GET /hubs/continueWatching`, falling back to `/library/onDeck`.
- Details: `GET /library/metadata/{ratingKey}`. Search: `GET /hubs/search?query=...`.
- Mark watched: `GET /:/scrobble?key={ratingKey}&identifier=com.plexapp.plugins.library`.
- Watchlist: lives on Plex's discover API, not the server. Leave it for a later phase, and offer only "Mark watched" at first.
- Launch:
  - The app is `com.plexapp.android`. The community-documented intent is VIEW `plex://server://{machineId}/com.plexapp.plugins.library/library/metadata/{ratingKey}`, with an extra that chooses play or details.
  - Plex updates have broken this before, so check it on the TV and fall back to opening the app.

## Tokens and privacy

- Tokens are stored like the Stremio key, encrypted with an Android Keystore AES key. Changing the session code needs your OK first.
- Backups never include tokens. After a restore, each source asks to sign in again.
- Scene only reads what is being watched. The only writes are the ones the user asks for: mark watched, favourites, and removing a title from Continue watching.

## Phases

1. Move Stremio behind `Source`. Home must look and act the same as before.
2. Jellyfin: discovery, Quick Connect, Continue watching, Spotlight and launch.
3. Plex: PIN, server choice, Continue watching, Spotlight and launch.
4. Watch With in Settings and setup ("What do you watch with?"), and the backup rules.
5. Library rows and the Library screen.
6. Test on the TV against real servers. Before that, test Jellyfin against a local server.

## Status

Phases 1 to 5 are in the app, except the setup step ("What do you watch with?").

Tested on the emulator against a local Jellyfin 12.4 server with synthetic clips:
discovery (finds nothing through the emulator's NAT, as expected), Enter Address, Quick Connect,
Continue watching with episode stills and Next Up, the library rows and the Library screen,
Mark episode watched, the account pane and Disconnect.

Not tested:

- Plex. There is no test account, so the PIN, server choice and the `plex://` deep link are unverified.
- Opening a title in the Jellyfin app. The Jellyfin app is not on the emulator.
- Server discovery on a real network.
- Library paging past 70 titles, and the Stremio library with watched ticks.

## Decisions

- Watch With is per device, like the Stremio sign-in. Library on Home is per profile.
- Plain HTTP is allowed in the network config, but `Net` refuses it for hosts outside the home network.
- Jellyfin and Plex tokens are encrypted with the same Keystore key as the Stremio session.
