# 🚀 Blurr — OpenRouter Edition — Setup Guide

> Modified by Claude for Muhd. Original app by the Blurr team (open source).
> All AI calls now go through **OpenRouter** (free tier available).
> No Gemini API key or proxy needed. Just your own OpenRouter key.

---

## What changed
- ❌ Removed: Gemini API proxy (the original rate-limited cloud gateway)
- ✅ Added: Direct OpenRouter calls (you control your key)
- ✅ Added: Model picker in Settings (pick any free model)
- ✅ Added: Local login (no Google account needed to use the app)
- 🔑 API key is entered **inside the app** — no coding required

---

## Step 1 — Get your free OpenRouter API key

1. Go to **https://openrouter.ai**
2. Click **Sign Up** (free, no credit card for free models)
3. Go to **Keys** in your dashboard
4. Click **Create Key** → copy the key (starts with `sk-or-...`)

---

## Step 2 — Build the APK (GitHub Actions — no Android Studio needed)

### First time only: Upload to GitHub

1. Go to **https://github.com** → Sign Up (free)
2. Click **New repository** → name it `blurr-openrouter` → Public → Create
3. On your computer:
   - Extract this zip
   - Open the folder in a file manager
4. Upload all files to GitHub:
   - Click **uploading an existing file** on the repo page
   - Drag and drop the entire `blurr-main` folder contents
   - Click **Commit changes**

### Trigger the build

5. In your GitHub repo, click the **Actions** tab
6. Click **Build Debug APK** → click **Run workflow** → **Run workflow**
7. Wait about 5-10 minutes for it to finish
8. When it says ✅, click on the run → scroll down to **Artifacts**
9. Download **blurr-openrouter-debug.zip** → extract it → you'll get `app-debug.apk`

---

## Step 3 — Install the APK on your phone

1. Send the APK to your phone (WhatsApp, email, USB cable — any way)
2. Open it on your phone
3. Android will ask **"Allow from this source"** → tap **Settings** → enable **Install unknown apps**
4. Go back → tap **Install**
5. Open **Blurr**

---

## Step 4 — First time setup

1. App opens → enters a **"Quick Setup"** dialog
2. Type your **name** and any **email** (stored locally, no account created)
3. Tap **"Let's Go!"**
4. Go through the permissions screen (important: allow Accessibility and Overlay)
5. Open **Settings** (⚙️ icon in the app)
6. Scroll to **"AI Settings (OpenRouter)"**
7. Paste your `sk-or-...` key
8. Choose a model (default: `google/gemma-3-27b-it:free` — good and free)
9. Tap **Save AI Settings**

Done! The app will now use OpenRouter for all AI calls 🎉

---

## Free models you can use

| Model | Speed | Quality |
|-------|-------|---------|
| `google/gemma-3-27b-it:free` | Fast | ⭐⭐⭐⭐ |
| `meta-llama/llama-3.1-8b-instruct:free` | Very fast | ⭐⭐⭐ |
| `qwen/qwen3-30b-a3b:free` | Medium | ⭐⭐⭐⭐⭐ |
| `deepseek/deepseek-r1:free` | Slow | ⭐⭐⭐⭐⭐ (reasoning) |
| `mistralai/mistral-7b-instruct:free` | Fast | ⭐⭐⭐ |

---

## Troubleshooting

**App crashes on start?**
- Make sure you completed the permissions screen
- Reinstall the APK

**"No OpenRouter API key set"?**
- Go to Settings → AI Settings → paste your key → Save

**Build fails in GitHub Actions?**
- Check the Actions tab → click the failed run → read the error
- Most common: Java version issue (it's set to 17 — should auto-work)

**Want to change your model later?**
- Settings → AI Settings → pick a different model → Save

---

## Notes

- The "Sign In" / Google account screen is bypassed — you go straight to a local name/email setup
- Firebase features (crash reports, cloud memory sync) won't work — but the core AI agent, screen interaction, voice, and all local features work fine
- Google TTS (voice) still needs a Google TTS API key if you want premium voices. The app falls back to Android's built-in TTS if it fails
- OpenRouter free tier has rate limits but they're generous for personal use
