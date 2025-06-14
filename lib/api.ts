import type { PrankCallRequest, CallApiResponse, CallSummaryResponse } from '@/types';

class ApiError extends Error {
  constructor(message: string, public status?: number) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function startPrankCall(request: PrankCallRequest): Promise<CallApiResponse> {
  const response = await fetch('/api/prank-call', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  const data = await response.json();

  if (!response.ok) {
    throw new ApiError(data.error || 'Failed to start call', response.status);
  }

  return data;
}

export async function getCallSummary(callId: string): Promise<CallSummaryResponse> {
  const response = await fetch(`/api/call-summary?callId=${callId}`);
  const data = await response.json();

  if (!response.ok) {
    throw new ApiError(data.error || 'Failed to get call summary', response.status);
  }

  return data;
}

export { ApiError }; 