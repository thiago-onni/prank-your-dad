# Prank Your Dad - AI Voice Demo

An AI-powered Father's Day surprise app that allows seamless transitions between an AI assistant (using your cloned voice) and yourself during a phone call.

## 🎬 Demo Script Reference

This app was built for the Vapi Father's Day ad:

> "This Father's Day, I decided to build an AI agent to call dad for me… using my voice. And he didn't notice."

## 🚀 Features

- **Voice Cloning**: Uses Vapi AI with your pre-cloned voice
- **Seamless Control**: Mute/unmute AI assistant at any time
- **Real-time Transcript**: See the conversation as it happens
- **Simple Setup**: Clear instructions for the speaker-based prank

## 📋 Prerequisites

- Vapi account with API keys
- Pre-configured assistant with voice clone (Assistant ID)
- Node.js 18+ installed
- Two devices: your phone and computer

## 🛠️ Setup

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd prank-your-dad
   ```

2. **Install dependencies**
   ```bash
   npm install
   ```

3. **Configure environment variables**
   
   Create a `.env.local` file with your Vapi credentials:
   ```
   # Vapi API Keys
   NEXT_PUBLIC_VAPI_PUBLIC_KEY=your-public-key
   VAPI_PRIVATE_KEY=your-private-key
   
   # Assistant ID (with voice clone)
   NEXT_PUBLIC_VAPI_ASSISTANT_ID=your-assistant-id
   ```

4. **Run the development server**
   ```bash
   npm run dev
   ```

5. **Open the app**
   Navigate to [http://localhost:3000](http://localhost:3000)

## 🎭 How to Use

1. **Call Dad from Your Phone**: Use your regular phone and put it on speaker
2. **Computer on Speaker**: Make sure your computer audio is also on speaker
3. **Start AI Assistant**: Click the button to activate the AI voice
4. **Control the Conversation**: 
   - Click "AI Muted" when you want to speak
   - Click "AI Active" to let the AI continue
   - The transcript shows in real-time

## 🔧 Technical Details

- **Frontend**: Next.js 15 with TypeScript
- **Styling**: Tailwind CSS
- **Voice AI**: Vapi Web SDK
- **Architecture**: Client-side voice control with optional server API

## 📝 Important Notes

- Both devices must be on speaker for the prank to work
- The AI uses your pre-cloned voice from Vapi
- Ensure good internet connection for best results
- Test the setup before the actual call

## 🤝 Contributing

Feel free to submit issues and enhancement requests!

## 📄 License

MIT License - feel free to use this for your own pranks!

---

Built with ❤️ for Father's Day using [Vapi AI](https://vapi.ai)
