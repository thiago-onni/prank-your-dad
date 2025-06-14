import { useState } from 'react';
import { toast } from 'sonner';
import { startPrankCall as apiStartPrankCall, getCallSummary } from '@/lib/api';
import type { PrankCallRequest } from '@/types';

export function usePrankCall() {
  const [callStatus, setCallStatus] = useState<string>('');
  const [callId, setCallId] = useState<string>('');
  const [callSummary, setCallSummary] = useState<string>('');
  const [isCallComplete, setIsCallComplete] = useState(false);

  const startPrankCall = async (request: PrankCallRequest) => {
    try {
      setCallStatus('Starting prank call...');
      
      const data = await apiStartPrankCall(request);

      setCallId(data.callId);
      setCallStatus('Call in progress...');
      toast.success('Prank call started!');

      // Poll for call completion
      pollCallStatus(data.callId);

    } catch (error) {
      console.error('Error starting prank call:', error);
      toast.error(error instanceof Error ? error.message : 'Failed to start prank call');
      setCallStatus('');
    }
  };

  const pollCallStatus = async (callId: string) => {
    const maxAttempts = 60; // Poll for up to 5 minutes
    let attempts = 0;

    const poll = async () => {
      try {
        const data = await getCallSummary(callId);

        if (data.status === 'ended') {
          setCallStatus('');
          setCallSummary(data.summary || 'Call completed');
          setIsCallComplete(true);
          toast.success('Prank call completed!');
          return;
        }

        attempts++;
        if (attempts < maxAttempts) {
          setTimeout(poll, 5000); // Poll every 5 seconds
        } else {
          setCallStatus('Call status unknown - check manually');
        }
      } catch (error) {
        console.error('Error polling call status:', error);
        attempts++;
        if (attempts < maxAttempts) {
          setTimeout(poll, 5000);
        }
      }
    };

    // Start polling after a short delay
    setTimeout(poll, 10000); // Wait 10 seconds before first poll
  };

  const resetCall = () => {
    setCallStatus('');
    setCallId('');
    setCallSummary('');
    setIsCallComplete(false);
  };

  return {
    callStatus,
    callId,
    callSummary,
    isCallComplete,
    startPrankCall,
    resetCall,
  };
} 