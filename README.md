# TellMe

An Android app that gives you scheduled daily briefings powered by an on-device AI.

## What it does

You pick a time, choose which days, and write a prompt like "What's the weather in Brasov" or "Bitcoin price today" or "NFL scores this week". About two minutes before each scheduled time, TellMe searches the web, pulls live data from free APIs, runs a small language model on your device to write a short brief, and sends you a notification at the exact time you set.

Tap the notification to see the full brief along with all the sources it used. You can tap any source to read the original article in your browser.

## How it works

TellMe uses a two-pass approach:

1. **First pass**: The on-device LLM reads your prompt and decides which free APIs to call (weather, crypto prices, exchange rates, dictionary definitions, etc.)
2. **Data fetch**: TellMe calls those APIs and searches Google News for relevant headlines
3. **Second pass**: The LLM writes a short, natural brief using the real data it just gathered

The whole thing takes about 10 to 20 seconds on most phones, and the model is only loaded during generation. It unloads right after so it doesn't sit in memory.

## APIs used

- **Open-Meteo** for weather and forecasts
- **CoinGecko** for cryptocurrency prices
- **ExchangeRate-API** for currency conversion
- **Free Dictionary API** for word definitions
- **NASA** for the astronomy picture of the day
- **Hacker News** for top tech stories
- **TVMaze** for TV show info
- **Open Library** for book searches
- **Nager.Date** for public holidays
- **Wikipedia** for encyclopedia lookups
- **Joke API** for random jokes
- **ZenQuotes** for inspirational quotes
- **ipinfo.io** for IP location info
- **Google News RSS** for web search headlines

## Features

- On-device AI with Qwen2.5-1.5B-Instruct.
- Exact alarms that work even when the app is closed or the phone is asleep.
- Survives reboots. Alarms are re-armed automatically after a restart.
- Dark mode that follows your system setting.
- Brief history so you can look back at previous briefs.
- Swipe to delete old briefs from history.
- Clean, short notifications you can read at a glance.

## Prebuilt APK

Don't want to build from source? Grab the latest prebuilt APK from [GitHub Releases](https://github.com/TheShovel/tellme/releases). Download the `.apk` file, install it on your phone, and you're good to go.

## Building from source

You need Android Studio and a physical Android phone (the AI model only works on ARM chips, so emulators won't work).

```
# Make sure JAVA_HOME points to JDK 17+
export JAVA_HOME=/path/to/java-17

# Build
./gradlew assembleDebug

# Install on connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app downloads the model (about 1.5 GB over Wi-Fi). This only happens once.

## Permissions

- **Internet** for web search and API calls
- **Notifications** to show your briefs
- **Exact alarms** for on-time delivery (Android 12+)
- **Boot completed** to restore alarms after restart
- **Foreground service** to generate briefs in the background

## Troubleshooting

**Notifications arrive late or not at all**
Grant the "Alarms & reminders" permission in your phone's settings. TellMe will prompt you on first launch if it's missing.

**Model download fails**
Make sure you're on Wi-Fi. The model is about 1.5 GB. You can retry from the error dialog.

**Build fails**
Make sure you have JDK 17 or newer and the Android SDK installed. The project targets API 35.

## Tech stack

- Kotlin
- Jetpack Compose with Material 3
- MediaPipe GenAI for on-device LLM inference
- AlarmManager for exact scheduling
- Foreground services for background generation
- Jsoup for web scraping
- Gson for JSON parsing
