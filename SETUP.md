# Prank Your Dad - AI Voice Demo App

A web app that lets users clone their voice and prank call their dad for Father's Day using AI.

## Features

- **Voice Cloning**: Clone user's voice using ElevenLabs API
- **Outbound Calls**: Make prank calls to dad's phone using Vapi
- **Pre-defined Scenarios**: 5 believable excuses for calling from random number
- **Call Transfer**: Automatically transfer to real person after AI reveal
- **Viral Ready**: 100% free for users, optimized for social sharing

## Setup Instructions

### 1. Environment Variables

Create a `.env.local` file with:

```bash
# Vapi Configuration
VAPI_PRIVATE_KEY=your_vapi_private_key_here
VAPI_PHONE_NUMBER_ID=your_vapi_phone_number_id_here

# ElevenLabs Configuration  
ELEVENLABS_API_KEY=your_elevenlabs_api_key_here
```

### 2. Get Vapi Credentials

1. Sign up at [vapi.ai](https://vapi.ai)
2. Get your private key from the dashboard
3. Purchase a phone number and get the phone number ID
4. Add both to your `.env.local` file

### 3. Get ElevenLabs API Key

1. Sign up at [elevenlabs.io](https://elevenlabs.io)
2. Get your API key from settings
3. Add to your `.env.local` file

### 4. Install Dependencies

```bash
bun install
# -or- 
npm install
```

### 5. Run the App

```bash
bun dev
# -or-
npm run dev
```

## How It Works

### 4-Step User Flow

1. **Clone Voice**: User records voice samples reading provided text
2. **Dad's Phone**: User enters dad's phone number
3. **Select Prank**: Choose from 5 pre-defined scenarios
4. **Your Phone**: Optionally provide phone number for call transfer

### Call Transfer Functionality

The app uses Vapi's `transferCall` tool with warm transfer capabilities to seamlessly hand off the call:

1. **AI Conversation**: AI has natural conversation using cloned voice
2. **Reveal**: After 2 exchanges, AI reveals it's an AI prank
3. **Transfer**: AI immediately calls `transferCall` function
4. **Warm Handoff**: Call transfers to user's phone with context message

### Prank Scenarios

Pre-defined scenarios explain why calling from random number:
- **Lost Phone**: "Calling from friend's phone because I lost mine"
- **Stuck on Highway**: "Using roadside assistance phone, flat tire"
- **Friend's Phone**: "Borrowing friend's phone, mine died"
- **Work Phone**: "Using work phone, personal phone broken"
- **New Number**: "Temporary number while sorting phone issues"

Each scenario has a custom system prompt that:
1. Opens with the excuse and Father's Day wishes
2. Has natural conversation for 2 exchanges
3. Reveals it's an AI prank
4. **Immediately calls transferCall function to transfer to real person**
5. Stops talking after transfer

## Technical Architecture

- **Frontend**: Next.js 15, TypeScript
- **UI**: Tailwind CSS + shadcn/ui components
- **Voice Cloning**: ElevenLabs API
- **Phone Calls**: Vapi API
- **Audio Recording**: Web Audio API with speech recognition
