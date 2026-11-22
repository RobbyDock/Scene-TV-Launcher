# Scene 0.2.0 (alpha)

The second alpha of Scene, a calm, ad-free home screen for Android TV and Google TV. This release brings a full redesign, two more watch sources, Kids profiles and updates from inside Scene.

**This is still an alpha.** Expect rough edges and changes between releases. Export your settings before you update, and report anything that breaks in Issues.

### New look
- Home, Settings and setup are redesigned, with the Outfit font, a cinema style Home and a split screen setup with a live picture of each choice
- Every Settings pane shows a live preview of what it changes
- Scene fits any screen size and works with touch: tap, swipe and fling

### Watch with Stremio, Jellyfin or Plex
- Choose one app for Continue watching, the Spotlight, search and your library. Stremio is recommended, Jellyfin and Plex are experimental
- Your Library: rows from your library on Home, and a full library screen
- Continue watching art loads as you scroll, and five cards fit the screen at 16:9

### Apps and rows
- App Rows: make your own rows, or turn on ready-made rows that fill themselves by kind of app
- Long rows and All apps show two lines, then a See all tile that opens the full grid
- An empty row offers an Add app tile
- Lock apps behind a passcode during set hours
- Open an app when the TV starts, and remap the app buttons on your remote
- Show the TV's HDMI inputs as a row

### Profiles
- Kids profiles: no Spotlight and no Settings. Turning one on needs a passcode on every other profile and an admin's passcode. Leaving needs a passcode, and any admin's works, so nobody gets stuck
- Admin and standard roles, with recovery codes for admins

### Now Playing
- See what's playing on Home, with synced lyrics from LRCLIB and a visualizer
- A music screensaver while music plays. Video art is cropped to a square, and long titles wrap

### Updates
- Scene checks GitHub for a new release once a day, only while Home is open. A quiet dot on the settings button shows when one is ready, and you can skip a version
- Updates install only when the download is Scene, newer, and signed with the same key. Android asks you to confirm

### Smaller changes
- Weather forecast flyout with colour, and a Temperature Units choice
- Tags in Settings line up at the end of each row, and pane rows drop their subtitles
- Faster image loading and smaller cached art

### Install
Download `Scene-0.2.0.apk` below and install it with `adb install -r Scene-0.2.0.apk` or a file manager on the TV. It installs over 0.1.0 and keeps your settings. Requires Android TV or Google TV 8.0 or newer. See the README for setting Scene as your Home app.

Tested on an onn 4K streaming box running Android 14.
