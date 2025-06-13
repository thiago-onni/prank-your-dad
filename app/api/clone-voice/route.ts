import { NextRequest, NextResponse } from 'next/server';

export async function POST(request: NextRequest) {
  try {
    const formData = await request.formData();
    const name = formData.get('name') as string;
    const files = formData.getAll('files') as File[];
    
    if (!name || files.length === 0) {
      return NextResponse.json(
        { error: 'Name and at least one audio file are required' },
        { status: 400 }
      );
    }

    // Check for ElevenLabs API key
    const elevenLabsApiKey = process.env.ELEVENLABS_API_KEY;
    if (!elevenLabsApiKey) {
      return NextResponse.json(
        { error: 'ElevenLabs API key not configured' },
        { status: 500 }
      );
    }

    // Prepare form data for ElevenLabs
    const elevenLabsFormData = new FormData();
    elevenLabsFormData.append('name', name);
    elevenLabsFormData.append('remove_background_noise', 'true');
    elevenLabsFormData.append('description', 'Voice cloned for Prank Your Dad app');
    
    // Add all audio files - ElevenLabs expects 'files' not 'files[]'
    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      console.log(`Processing file ${i}:`, {
        name: file.name,
        type: file.type,
        size: file.size
      });
      
      // Convert WebM to a more compatible format name if needed
      const fileName = file.name.replace('.webm', '.wav');
      const newFile = new File([file], fileName, { 
        type: file.type.includes('webm') ? 'audio/wav' : file.type 
      });
      
      elevenLabsFormData.append('files', newFile);
    }
    
    // Log what we're sending
    console.log('Sending to ElevenLabs:', {
      name,
      fileCount: files.length,
      files: files.map(f => ({ name: f.name, type: f.type, size: f.size }))
    });

    // Make request to ElevenLabs
    const response = await fetch('https://api.elevenlabs.io/v1/voices/add', {
      method: 'POST',
      headers: {
        'xi-api-key': elevenLabsApiKey,
      },
      body: elevenLabsFormData,
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('ElevenLabs error response:', {
        status: response.status,
        statusText: response.statusText,
        error: errorText
      });
      
      let errorDetails;
      try {
        errorDetails = JSON.parse(errorText);
      } catch {
        errorDetails = errorText;
      }
      
      return NextResponse.json(
        { 
          error: 'Failed to clone voice', 
          details: errorDetails,
          status: response.status 
        },
        { status: response.status }
      );
    }

    const result = await response.json();
    
    return NextResponse.json({
      success: true,
      voiceId: result.voice_id,
      requiresVerification: result.requires_verification,
    });
  } catch (error) {
    console.error('Voice cloning error:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
} 