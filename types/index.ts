export interface TranscriptEntry {
  role: 'assistant' | 'user';
  text: string;
}

export interface Step {
  number: number;
  key: string;
  title: string;
}

export interface CallApiResponse {
  callId: string;
  error?: string;
}

export interface CallSummaryResponse {
  status: 'ended' | 'in-progress' | 'failed';
  summary?: string;
}

export interface PrankCallRequest {
  dadPhoneNumber: string;
  voiceId: string;
  systemPrompt: string;
  transferPhoneNumber?: string | null;
} 