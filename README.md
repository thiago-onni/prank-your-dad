# Prank Your Dad - AI Voice Demo

An AI-powered Father's Day surprise app that allows you to clone your voice on the spot and use it with an AI assistant to prank your dad.

## 🎬 Demo Script Reference

This app was built for the Vapi Father's Day ad:

> "This Father's Day, I decided to build an AI agent to call dad for me… using my voice. And he didn't notice."

## 🚀 Features

- **Instant Voice Cloning**: Clone your voice directly in the web app using ElevenLabs
- **Real-time AI Assistant**: Creates a Vapi assistant on the fly with your cloned voice
- **Seamless Control**: Mute/unmute AI assistant at any time
- **Real-time Transcript**: See the conversation as it happens
- **Simple Setup**: Clear instructions for the speaker-based prank

## 📋 Prerequisites

- Vapi account with API keys
- ElevenLabs account with API key
- Node.js 18+ installed (or Bun)
- Two devices: your phone and computer
- Microphone for voice recording

## 🛠️ Setup

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd prank-your-dad
   ```

2. **Install dependencies**
   ```bash
   bun install
   # or npm install
   ```

3. **Configure environment variables**
   
   Create a `.env.local` file with your API credentials:
   ```
   # Vapi API Keys
   NEXT_PUBLIC_VAPI_PUBLIC_KEY=your-vapi-public-key
   VAPI_PRIVATE_KEY=your-vapi-private-key
   
   # ElevenLabs API Key
   ELEVENLABS_API_KEY=your-elevenlabs-api-key
   
   # Optional: Pre-defined voice ID for demo purposes
   NEXT_PUBLIC_PREDEFINED_VOICE_ID=your-elevenlabs-voice-id
   ```

   Get your API keys from:
   - Vapi: https://dashboard.vapi.ai/
   - ElevenLabs: https://elevenlabs.io/
   
   For the pre-defined voice ID:
   - Create or clone a voice in ElevenLabs dashboard
   - Copy the voice ID from the voice settings
   - This is useful for demos where you want to skip the cloning step

4. **Run the development server**
   ```bash
   bun run dev
   # or npm run dev
   ```

5. **Open the app**
   Navigate to [http://localhost:3000](http://localhost:3000)

## 🎭 How to Use

### Option 1: Clone Your Voice (Default Tab)
1. Enter a name for your voice (e.g., "My Voice")
2. Record 1-3 voice samples (10-30 seconds each)
3. Click "Clone Voice" to create your voice clone
4. Proceed to make the call

### Option 2: Use Demo Voice Tab
1. Switch to "Use Demo Voice" tab
2. Skip voice cloning and use a pre-configured voice
3. Perfect for quick demos

### Making the Call
1. **Call Dad from Your Phone**: Use your regular phone and put it on speaker
2. **Computer on Speaker**: Make sure your computer audio is also on speaker
3. **Start AI Assistant**: Click the button to activate the AI with your cloned voice
4. **Control the Conversation**: 
   - Click "AI Muted" when you want to speak (dad only hears you)
   - Click "AI Active" to let the AI continue speaking
   - The transcript shows in real-time

## 🔧 Technical Details

- **Frontend**: Next.js 15 with TypeScript
- **UI Components**: shadcn/ui with Tailwind CSS v4
- **Voice Cloning**: ElevenLabs Instant Voice Cloning API
- **Voice AI**: Vapi Web SDK with inline assistant configuration
- **Architecture**: Client-side voice control with server-side voice cloning

## 📝 Important Notes

- Both devices must be on speaker for the prank to work
- The AI uses your freshly cloned voice from ElevenLabs
- Record clear voice samples without background noise for best results
- Ensure good internet connection for real-time voice synthesis
- Test the setup before the actual call

## 🤝 Contributing

Feel free to submit issues and enhancement requests!

## 📄 License

MIT License - feel free to use this for your own pranks!

---

Built with ❤️ for Father's Day using [Vapi AI](https://vapi.ai) and [ElevenLabs](https://elevenlabs.io)
