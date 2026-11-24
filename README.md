# Tablet Dashboard

An always-on home dashboard for an old Android tablet that stays plugged in (built for a 1280×800, Android 10 tablet in landscape).

Clock · weather · Google Calendar · news cards · scoreboard · stock quotes · top stories · internet radio and an hourly newscast. Everything lives on one page, so nothing ever switches apps.

## What's public and what isn't
- This repo and the page it publishes are public, so they hold **nothing personal**: no location, news sources, teams, stocks or family dates.
- Personal settings live in your own **Google bridge** (`calendar-bridge.gs`, deployed from your Google account) as the script property `DASH_CONFIG`. The tablet fetches them with its private bridge link and merges them over `config.json`.
- Without the bridge link the page shows only the clock, public holidays, general headlines and the radio.

## How updates work
- `index.html` is the app. `config.json` holds the shared, non-personal setup: holidays, radio stations, general headlines, refresh times, night hours.
- The tablet re-reads `config.json` and the private settings every 5 minutes (`refresh.configMin`) and applies changes on its own. The page also reloads itself once a night (`reloadDailyAt`).
- `preview.html` + `config-preview.json` are a staging copy for trying out layout changes before they go live.
- Settings on the tablet (⚙, bottom right) are optional. Local overrides there win over everything, on that tablet only.

## The tablet app
- `android/` is a small full-screen WebView app (keeps the screen on, hides Android's bars, can be the Home app, dims the backlight at night, reloads the page if it ever freezes). GitHub Actions builds it on every change under `android/` and publishes it as the latest release: `TabletDashboard.apk`.
- The signing key is **not** in the repo. The build reads it from the repository secret `KEYSTORE_B64` (a base64-encoded PKCS12 keystore, alias `dashboard`).
- Hidden way out of the app: tap the top-left corner 5 times within 3 seconds to open Android Settings.

## Google bridge (one-time, about 5 minutes)
- Follow the steps at the top of `calendar-bridge.gs`. You deploy it from your Google account at script.google.com; Google asks once for permission to read your calendars and fetch web data.
- That gives you a private "Google bridge link". On the tablet, tap ⚙ and paste it into **Google bridge link**. It's saved on the tablet only.
- To choose which calendars appear, edit `SHOW_CALENDARS` in the script (a trailing `*` matches a name prefix).
- `DASH_CONFIG` example: `{"location":{"name":"Home","lat":0,"lon":0,"units":"fahrenheit"},"stocks":["SPY"],"sections":[...],"games":{...},"personalDates":[{"label":"Grandpa's birthday","date":"2027-06-15","yearly":true}]}`

## Notes
- Weather and sunrise/sunset come from Open-Meteo, alerts from the National Weather Service (no keys needed).
- Most news sites block direct requests from browsers, so feeds fall back to rss2json.com, then the Google bridge.
- Countdowns: `{label, date, yearly}` for fixed dates, `{label, rule: "easter"}`, or `{label, rule: "4 thu 11"}` (4th Thursday of November; `-1 mon 5` = last Monday of May).
- Each panel keeps a copy of its last good data, so the screen never goes blank when the Wi-Fi drops.
- Battery: a worn battery kept on the charger all the time can swell. Check the case now and then.
