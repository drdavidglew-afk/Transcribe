# VidScribe

An Android app. Give it a link to a video from any site and it extracts the audio and returns an easy-to-read transcript.

## What it does

1. **Takes a link.** You can paste one, or tap *Share → VidScribe* in YouTube, a browser, Instagram, TikTok or any other app.
2. **Downloads only the audio.** It uses yt-dlp, which supports well over a thousand sites. yt-dlp updates itself daily, so it keeps up as sites change.
3. **Converts the audio to small mono speech MP3.** Recordings too big for one upload (roughly over 3 hours) are split into parts automatically, and the timestamps still run continuously.
4. **Transcribes it** with Whisper, through Groq or OpenAI using your own API key.
5. **Formats the result** into paragraphs, splitting at natural pauses. Timestamps are optional, the text is large and well spaced, and you can copy, save it as a .txt file or share it.

## Podcasts

Open the **Podcasts** tab and search by name. The search uses Apple's free podcast directory, which lists almost every podcast, and needs no extra key. Tap a podcast to see its episodes, then tap an episode to transcribe it. You can also paste a podcast's RSS feed link into the search box.

Episode links from podcast apps also work in the **Video link** tab, for example Apple Podcasts, Overcast, SoundCloud or YouTube.

Spotify-only shows are protected (DRM), so they can't be downloaded or transcribed.

**Long episodes and free tiers:** Groq's free tier limits how much audio you can transcribe per hour. If a long episode hits that limit, the app waits for the limit to reset and then carries on by itself.

## If a link fails

**If a link can't be fetched** (the video is private, needs a sign-in, or the site blocks downloads), the app asks you to download the video to your phone and then tap **Choose file**. You can also share a video from Gallery or Files straight to VidScribe.

## Get the APK

Download it on your phone from https://github.com/drdavidglew-afk/Transcribe/releases/latest by tapping **VidScribe.apk**, then open it. Allow "install unknown apps" if Android asks.

Each change pushed to `main` builds a new release automatically.

If you use Android Studio instead: open this folder and press **Run**.

## First run

Tap ⚙ and enter an API key:

- **Groq** (the default) is fast and has a generous free tier. Get a key at https://console.groq.com/keys.
- **OpenAI** costs about $0.006 per minute of audio. Get a key at https://platform.openai.com/api-keys.

You can also set a language code (for example `en`). If you leave it blank, the language is detected automatically.

## Notes

- Keep the app open while it works. The screen stays awake on its own during a job.
- The APK is built for 64-bit phones (arm64), which covers almost every phone made since about 2017. For older phones, add `"armeabi-v7a"` to `abiFilters` in `app/build.gradle.kts`.
- Only download and transcribe videos you have the right to use.
