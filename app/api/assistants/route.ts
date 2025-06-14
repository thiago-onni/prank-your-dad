import { NextResponse } from 'next/server';

export async function GET() {
  try {
    const vapiToken = process.env.VAPI_PRIVATE_KEY;
    
    if (!vapiToken) {
      return NextResponse.json(
        { error: 'VAPI_PRIVATE_KEY not configured' },
        { status: 500 }
      );
    }

    const response = await fetch('https://api.vapi.ai/assistant', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${vapiToken}`,
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('Vapi API error:', response.status, errorText);
      return NextResponse.json(
        { error: `Failed to fetch assistants: ${response.status}` },
        { status: response.status }
      );
    }

    const assistants = await response.json();
    
    return NextResponse.json({
      assistants: assistants || []
    });

  } catch (error) {
    console.error('Error fetching assistants:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
} 