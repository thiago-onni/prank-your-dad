# Prank Your Dad - AI Voice Demo

An AI-powered voice assistant demo that showcases seamless transitions between an AI agent and yourself during a phone call, built with Vapi.

## Demo Concept

This application was built to demonstrate the Vapi platform's capabilities through a Father's Day scenario:

"This Father's Day, I decided to build an AI agent to call dad for me... using my voice. And he didn't notice."

## Features

- **Voice AI Integration**: Uses Vapi with pre-configured voice cloning
- **Real-time Control**: Mute and unmute the AI assistant during live calls
- **Live Transcript**: View conversation in real-time
- **Simple Setup**: Clear instructions for demonstration purposes

## Prerequisites

- Vapi account with API credentials
- Pre-configured assistant with voice clone (Assistant ID)
- Node.js 18 or higher
- Two devices: phone and computer with speakers

## Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/VapiAI/prank-your-dad.git
   cd prank-your-dad
   ```

2. **Install dependencies**
   ```bash
   npm install
   ```

3. **Configure environment variables**
   
   Create a `.env.local` file with your Vapi credentials:
   ```
   NEXT_PUBLIC_VAPI_PUBLIC_KEY=your-public-key
   VAPI_PRIVATE_KEY=your-private-key
   NEXT_PUBLIC_VAPI_ASSISTANT_ID=your-assistant-id
   ```

4. **Run the development server**
   ```bash
   npm run dev
   ```

5. **Access the application**
   Open [http://localhost:3000](http://localhost:3000) in your browser

## Usage Instructions

1. Call your dad from a phone and enable speaker mode
2. Ensure your computer audio is also set to speaker output
3. Click "Start AI Assistant" to begin the demonstration
4. Use the mute/unmute controls to switch between AI and personal speech
5. Monitor the live conversation transcript

## Technical Implementation

- **Framework**: Next.js 15 with TypeScript
- **Styling**: Tailwind CSS with shadcn/ui components
- **Voice AI**: Vapi Web SDK, ElevenLabs API
- **Architecture**: Client-side voice control with server-side validation

## Important Notes

- Both devices must use speaker output for proper demonstration
- Requires stable internet connection for optimal performance
- Test the setup before live demonstrations
- The AI uses your pre-configured voice clone from Vapi

## Contributing

Issues and pull requests are welcome. Please ensure any changes maintain the demo's simplicity and effectiveness.

## License

MIT License - See LICENSE file for details

---

Built with ❤️ for Father's Day using [Vapi AI](https://vapi.ai/)
