# Sahayak: a voice agent that operates the phone for my parents

My parents get stuck on their phones every week: a PDF won't open, a button does nothing, they don't know how
to send money on WhatsApp. Calling me or asking ChatGPT still means *they* have to follow the steps.
Sahayak removes that: press one floating button, say the problem in Hindi, and the agent looks at the
screen and does it on the phone itself.

## How it works

```
 🎤 tap ─► SpeechRecognizer (hi-IN) ─► "PDF nahi khul raha"
                                          │
          ┌───────────────────────────────▼───────────────────────────────┐
          │  loop (max 25 steps)                                          │
          │   observe: accessibility tree → numbered element list         │
          │            + 540px screenshot                                 │
          │   think:   Claude (tool use) picks the next actions           │
          │   act:     tap / type / scroll / back / open_app /            │
          │            ask_user (voice) / say                             │
          └───────────────────────────────┬───────────────────────────────┘
                                          ▼
          speaks the result to the parent + SMS report to the child
```

| File | Role |
|---|---|
| `AgentService.kt` | Accessibility service: floating mic bubble, session lifecycle |
| `ScreenReader.kt` | Accessibility tree → compact `[id] Button "Send" {click} @(x,y)` list, plus screenshot |
| `PhoneActions.kt` | Taps (node click → gesture fallback), typing, swipes, global keys, launching apps |
| `ToolRunner.kt` | Executes actions + safety gates + system prompt, shared by both brains |
| `BridgeAgent.kt` | Brain #1: asks Claude Code on the child's computer via `bridge/bridge.py` (no API key on the phone) |
| `Agent.kt` | Brain #2: calls the Claude API directly with a key |
| `bridge/bridge.py` | Localhost server that turns each screen into a `claude -p` call with a JSON-schema'd action reply |
| `Voice.kt` | Speech-to-text and text-to-speech in Hindi / English / Punjabi |
| `ChildNotifier.kt` | SMS to the son/daughter: "Papa got stuck on X, here's what happened" |

## Product decisions

- **The parent has to press a button.** Auto-detecting "stuck" (long idle, repeated taps) sounds magical but
  misfires: interrupting someone who is just reading slowly is worse than not helping.
- **Voice in their language.** Typing a question into ChatGPT is exactly the skill they don't have.
- **Act, don't instruct.** Step-by-step guidance still fails at "where is the three-dot menu?".
- **Money is human-in-the-loop, enforced in code.** The prompt tells the agent to confirm amount + recipient
  out loud, *and* `Agent.kt` blocks any tap on a pay/send-looking button unless the last spoken answer was a yes.
- **Never types PINs/passwords/OTPs.** Password fields are refused at the action layer; the agent asks the
  parent to enter the UPI PIN themselves. It is also told to stop and warn on scam patterns.
- **Every request ends with the job done, not advice.** "What does this mean?" ends with the reply sent;
  "the PDF won't open" ends with the PDF open.
- **A family profile** (name, address, phone, email, preferences) is filled once by the child, so forms and
  bookings don't need 20 questions. The agent asks only for what's missing (date, destination) and picks
  sensible defaults for the rest.
- **One confirmation gate for everything irreversible.** Pay, send, book, place order, submit: the phone
  blocks those buttons (including coordinate taps, and labels nested deep inside a button) until the
  parent says yes to a spoken summary.
- **Logins:** may read an OTP from SMS and enter it into the app the parent asked to log into; never says it
  aloud or sends it anywhere. Passwords and UPI PINs are always typed by the parent.
- **The child is the real customer.** They install it, set it up, and get an SMS after every session,
  so they know what's going wrong at home without a phone call.

## Limits (honest list)

- Android only. iOS doesn't let one app control another.
- Banking/UPI screens often block screenshots (FLAG_SECURE); the agent falls back to the element list.
- Play Store restricts Accessibility-API apps, so this ships as a sideloaded APK.
- Bookings (cabs, flights, groceries, tickets) use the same generic loop but haven't been demoed end to end:
  the emulator has neither those apps nor real accounts, and payments need the parent's UPI PIN.
- The API key is stored in plain app preferences, fine for a family prototype, not for production
  (a real version would proxy through a backend).

## Demos (emulator, Claude Code as the brain)

| Video | Request (spoken in Hindi) | What happened |
|---|---|---|
| [`youtube-bhajan`](demo/sahayak-youtube-bhajan.mp4) | "YouTube pe bhajan lagao" | Opened YouTube, dismissed a notification prompt, searched, played a bhajan. 10 steps, ~100 s |
| [`explain-and-reply`](demo/sahayak-explain-and-reply.mp4) | "Beti ka message samajh nahi aa raha, matlab batao aur reply kar do" | Explained ETA/TTYL/BTW/ASAP/IMO, wrote a reply in his voice, read it back, sent it after "haan". **Unprompted, flagged that the sender was an unsaved number** (the classic "new number, beta here" scam) and told the child |
| [`fill-form`](demo/sahayak-fill-form.mp4) | "Pizza ka form bhar do, medium, cheese aur mushroom, 7 baje" | Got through Chrome's first-run screens, filled name/phone/email/address from the family profile, set size, toppings and time, read the order back, submitted after "haan" |

The emulator has no microphone, so the parent's spoken "haan" is supplied through a test hook and shown in the captions as 👤 "haan".

## Install it on a phone (Android 11+)

Usually the son/daughter does this once, in about 5 minutes:

1. **Download** `Sahayak-v0.1.apk` from the [latest release](../../releases/latest) on the phone.
2. **Install:** open the file. Android asks to allow installs from your browser; allow it. Play Protect may
   say the app is from an unknown developer; tap *More details → Install anyway* (it's not on the Play
   Store because Google restricts apps that use Accessibility to control the phone).
3. **Get a Claude API key** at [console.anthropic.com](https://console.anthropic.com) → API Keys, and add
   some credit. Each help session uses credits; long tasks like bookings cost more than quick fixes.
4. **Open Sahayak** and fill in: the API key, what you call your parent (Papa, Mummy), your phone number for
   SMS reports, their details for forms and bookings, and their language. Tap **Save**.
5. Tap **Allow microphone and SMS**, then **Turn on Sahayak** → *Accessibility → Sahayak helper → On*.
   On some phones this is under *Installed apps / Downloaded apps*. If the toggle is greyed out, open
   *Settings → Apps → Sahayak → ⋮ → Allow restricted settings* first (Android 13+ does this for sideloaded apps).
6. A blue 🎤 bubble now floats on every screen. Your parent taps it and speaks.

The API key is stored only on that phone, in plain app storage. Use a key with a spending limit.

## Build from source

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Developer mode: Claude Code as the brain** (no API key; the phone stays connected to your computer):

```bash
python3 bridge/bridge.py          # uses your existing `claude` login
adb reverse tcp:8765 tcp:8765     # phone/emulator → computer, over USB only
```

Then pick *Claude Code on my computer* in the app. Debug builds also accept a test hook for demos without a mic
(ignored in release builds, since any app could otherwise trigger it):
`adb shell am start -n com.sahayak.app/.MainActivity --es task "YouTube pe bhajan lagao" --es answers "haan"`
