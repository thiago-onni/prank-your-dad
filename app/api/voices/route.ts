import { NextResponse } from 'next/server';

export async function GET() {
  try {
    // Check for ElevenLabs API key
    const elevenLabsApiKey = process.env.ELEVENLABS_API_KEY;
    if (!elevenLabsApiKey) {
      return NextResponse.json(
        { error: 'ElevenLabs API key not configured' },
        { status: 500 }
      );
    }

    // Fetch voices from ElevenLabs
    const response = await fetch('https://api.elevenlabs.io/v1/voices', {
      method: 'GET',
      headers: {
        'xi-api-key': elevenLabsApiKey,
      },
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('ElevenLabs voices error:', {
        status: response.status,
        statusText: response.statusText,
        error: errorText
      });
      
      return NextResponse.json(
        { error: 'Failed to fetch voices', details: errorText },
        { status: response.status }
      );
    }

    const result = await response.json();
    
    // Filter out default/premade voices - only show user-created voices
    const defaultVoiceNames = [
      'Ana',
      'Hope - upbeat and clear',
      'Hope'
    ];
    
    const userVoices = (result.voices || []).filter((voice: { category?: string; name?: string }) => {
      // Keep voices that are NOT default/premade by category
      const isNotDefaultCategory = voice.category !== 'premade' && voice.category !== 'default';
      
      // Also filter out specific default voice names
      const isNotDefaultName = !defaultVoiceNames.includes(voice.name || '');
      
      return isNotDefaultCategory && isNotDefaultName;
    });
    
    return NextResponse.json({
      success: true,
      voices: userVoices,
    });
  } catch (error) {
    console.error('Voices fetch error:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
} 