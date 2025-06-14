import { useState, useEffect, useRef } from 'react';
import Vapi from '@vapi-ai/web';
import type { TranscriptEntry } from '@/types';

export function useVapiCall() {
  const [transcript, setTranscript] = useState<TranscriptEntry[]>([]);
  const [partialTranscript, setPartialTranscript] = useState<TranscriptEntry | null>(null);
  const vapiRef = useRef<Vapi | null>(null);

  useEffect(() => {
    // Initialize Vapi client
    const vapi = new Vapi(process.env.NEXT_PUBLIC_VAPI_PUBLIC_KEY || '');
    vapiRef.current = vapi;

    // Set up event listeners
    vapi.on('speech-start', () => {
      console.log('Speech started');
    });

    vapi.on('speech-end', () => {
      console.log('Speech ended');
    });

    vapi.on('call-start', () => {
      console.log('Call started');
    });

    vapi.on('call-end', () => {
      console.log('Call ended');
    });

    vapi.on('message', (message) => {
      console.log('Message:', message);
      
      // Handle transcript messages
      if (message.type === 'transcript') {
        const entry: TranscriptEntry = {
          role: message.role,
          text: message.transcript
        };

        if (message.transcriptType === 'final') {
          setTranscript(prev => [...prev, entry]);
          setPartialTranscript(null);
        } else {
          setPartialTranscript(entry);
        }
      }
    });

    return () => {
      if (vapi) {
        vapi.stop();
      }
    };
  }, []);

  const resetTranscript = () => {
    setTranscript([]);
    setPartialTranscript(null);
  };

  const stopCall = () => {
    if (vapiRef.current) {
      vapiRef.current.stop();
    }
  };

  return {
    transcript,
    partialTranscript,
    vapiRef,
    resetTranscript,
    stopCall,
  };
} 