import { NextRequest, NextResponse } from 'next/server';
import { VapiClient } from '@vapi-ai/server-sdk';

export async function POST(request: NextRequest) {
  try {
    const { assistantId } = await request.json();

    if (!assistantId) {
      return NextResponse.json(
        { error: 'Assistant ID is required' },
        { status: 400 }
      );
    }

    // Initialize Vapi client with private key
    const vapi = new VapiClient({
      token: process.env.VAPI_PRIVATE_KEY!
    });

    try {
      // Try to fetch the assistant to validate it exists
      const assistant = await vapi.assistants.get(assistantId);
      
      return NextResponse.json({
        success: true,
        assistant: {
          id: assistant.id,
          name: assistant.name,
          // Don't expose sensitive details
        }
      });
    } catch (vapiError) {
      console.error('Vapi validation error:', vapiError);
      return NextResponse.json(
        { 
          success: false,
          error: 'Assistant not found or invalid credentials',
          details: process.env.NODE_ENV === 'development' ? String(vapiError) : undefined
        },
        { status: 404 }
      );
    }
  } catch (error) {
    console.error('Validation error:', error);
    return NextResponse.json(
      { error: 'Failed to validate assistant' },
      { status: 500 }
    );
  }
} 