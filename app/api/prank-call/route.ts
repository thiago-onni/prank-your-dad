import { NextRequest, NextResponse } from 'next/server';
import { normalizePhoneNumber } from '@/lib/validation';

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

    // Normalize phone numbers to E164 format for international support
    const normalizedDadPhone = normalizePhoneNumber(dadPhoneNumber);
    const normalizedTransferPhone = transferPhoneNumber && transferPhoneNumber.trim() 
      ? normalizePhoneNumber(transferPhoneNumber) 
      : null;
    
    console.log('Transfer Phone Debug:', {
      transferPhoneNumber,
      normalizedTransferPhone,
      willIncludeTransferTool: !!normalizedTransferPhone
    });

    // Create the assistant configuration for the prank call
    const baseConfig = {
      name: 'Father\'s Day Prank Assistant',
      transcriber: {
        model: 'nova-2',
        language: 'en-US',
        provider: 'deepgram'
      },
      model: {
        model: 'gpt-4o-2024-11-20',
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
      backgroundSound: 'off'
    };

    // Only add transfer tool if transfer phone number is provided (user didn't choose skip transfer)
    const assistantConfig = normalizedTransferPhone ? {
      ...baseConfig,
      tools: [{
        type: 'transferCall',
        destinations: [{
          type: 'number',
          number: normalizedTransferPhone,
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
    } : baseConfig;

    console.log('Transfer tool included:', !!normalizedTransferPhone);
    console.log('Assistant config tools:', 'tools' in assistantConfig ? 'YES' : 'NO');
    console.log('Voice ID being used:', voiceId);
    console.log('System prompt length:', systemPrompt.length);
    console.log('Full assistant config being sent to Vapi:', JSON.stringify(assistantConfig, null, 2));

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
          number: normalizedDadPhone,
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