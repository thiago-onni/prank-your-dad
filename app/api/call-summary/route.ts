import { NextRequest, NextResponse } from 'next/server';

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const callId = searchParams.get('callId');
    
    if (!callId) {
      return NextResponse.json(
        { error: 'Call ID is required' },
        { status: 400 }
      );
    }
    
    const vapiToken = process.env.VAPI_PRIVATE_KEY;
    
    if (!vapiToken) {
      return NextResponse.json(
        { error: 'VAPI_PRIVATE_KEY not configured' },
        { status: 500 }
      );
    }

    // Fetch call details from Vapi
    const response = await fetch(`https://api.vapi.ai/call/${callId}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${vapiToken}`,
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      const errorData = await response.json();
      console.error('Vapi API error:', errorData);
      return NextResponse.json(
        { error: 'Failed to fetch call details' },
        { status: response.status }
      );
    }

    const callData = await response.json();
    
    // Extract summary from analysis
    const summary = callData.analysis?.summary || 'No summary available';
    const status = callData.status;
    const endedReason = callData.endedReason;
    
    return NextResponse.json({
      summary,
      status,
      endedReason,
      callData: {
        id: callData.id,
        startedAt: callData.startedAt,
        endedAt: callData.endedAt,
        cost: callData.cost
      }
    });

  } catch (error) {
    console.error('Error fetching call summary:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
} 