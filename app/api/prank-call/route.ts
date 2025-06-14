import { NextRequest, NextResponse } from 'next/server';

export async function POST(request: NextRequest) {
  try {
    const { dadPhoneNumber, voiceId, systemPrompt, transferPhoneNumber } = await request.json();
    
    const vapiToken = process.env.VAPI_PRIVATE_KEY;
    
    if (!vapiToken) {
      return NextResponse.json(
        { error: 'VAPI_PRIVATE_KEY not configured' },
        { status: 500 }
      );
    }

    const phoneNumberId = process.env.VAPI_PHONE_NUMBER_ID;
    if (!phoneNumberId) {
      return NextResponse.json(
        { error: 'VAPI_PHONE_NUMBER_ID not configured. Please add a phone number ID from your Vapi dashboard.' },
        { status: 500 }
      );
    }

    if (!dadPhoneNumber || !voiceId || !systemPrompt) {
      return NextResponse.json(
        { error: 'Missing required fields: dadPhoneNumber, voiceId, systemPrompt' },
        { status: 400 }
      );
    }

    // Create the assistant configuration for the prank call
    const assistantConfig = {
      name: 'Father\'s Day Prank Assistant',
      transcriber: {
        model: 'nova-2',
        language: 'en-US',
        provider: 'deepgram'
      },
      model: {
        model: 'gpt-4',
        messages: [
          {
            role: 'system',
            content: systemPrompt
          }
        ],
        provider: 'openai'
      },
      voice: {
        model: 'eleven_monolingual_v1',
        voiceId: voiceId,
        provider: '11labs',
        stability: 0.5,
        similarityBoost: 0.5
      },
      // Disable background noise for cleaner audio
      backgroundSound: 'off',
      // Add transfer tool if transfer phone number is provided
      ...(transferPhoneNumber && {
        tools: [{
          type: 'transferCall',
          destinations: [{
            type: 'number',
            number: transferPhoneNumber,
            message: 'Great news! I\'m now connecting you with the real person behind this prank. Hold on just a moment!',
            transferPlan: {
              mode: 'warm-transfer-with-message',
              message: 'Hey! This is a Father\'s Day prank call that was just transferred to you. Your dad was just talking to an AI using your cloned voice!'
            }
          }],
          function: {
            name: 'transferCall',
            description: 'Transfer the call to the real person after revealing the AI prank',
            parameters: {
              type: 'object',
              properties: {
                destination: {
                  type: 'string',
                  description: 'The phone number to transfer to'
                }
              },
              required: ['destination']
            }
          }
        }]
      })
    };

    // Make the outbound call using Vapi
    const response = await fetch('https://api.vapi.ai/call', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${vapiToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        type: 'outboundPhoneCall',
        assistant: assistantConfig,
        phoneNumberId: phoneNumberId,
        customer: {
          number: dadPhoneNumber,
        },
      }),
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('Vapi call API error:', response.status, errorText);
      return NextResponse.json(
        { error: `Failed to start prank call: ${response.status}` },
        { status: response.status }
      );
    }

    const callData = await response.json();
    
    return NextResponse.json({
      success: true,
      callId: callData.id,
      message: 'Prank call started successfully!'
    });

  } catch (error) {
    console.error('Error starting prank call:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
} 