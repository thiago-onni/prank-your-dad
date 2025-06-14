import React from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import type { TranscriptEntry } from '@/types';

interface LiveTranscriptProps {
  transcript: TranscriptEntry[];
  partialTranscript?: TranscriptEntry | null;
}

export function LiveTranscript({ transcript, partialTranscript }: LiveTranscriptProps) {
  if (transcript.length === 0 && !partialTranscript) {
    return null;
  }

  return (
    <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl mt-8">
      <CardHeader className="pb-4">
        <CardTitle className="text-xl text-white">Live Conversation</CardTitle>
      </CardHeader>
      <CardContent>
        <ScrollArea className="h-[400px] w-full rounded-lg bg-gray-900/50 border border-gray-700 p-4">
          <div className="space-y-4">
            {transcript.map((entry, index) => (
              <div key={index} className="flex gap-3 items-start">
                <Badge 
                  variant="outline"
                  className={`min-w-[60px] justify-center mt-0.5 ${
                    entry.role === 'assistant' 
                      ? 'bg-purple-500/20 text-purple-300 border-purple-500/30' 
                      : 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
                  }`}
                >
                  {entry.role === 'assistant' ? 'You' : 'Dad'}
                </Badge>
                <p className="text-gray-200 flex-1 leading-relaxed">{entry.text}</p>
              </div>
            ))}
            
            {partialTranscript && (
              <div className="flex gap-3 items-start opacity-60">
                <Badge 
                  variant="outline" 
                  className={`min-w-[60px] justify-center mt-0.5 animate-pulse ${
                    partialTranscript.role === 'assistant' 
                      ? 'bg-purple-500/20 text-purple-300 border-purple-500/30' 
                      : 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
                  }`}
                >
                  {partialTranscript.role === 'assistant' ? 'You' : 'Dad'}
                </Badge>
                <p className="text-gray-400 flex-1 italic leading-relaxed">{partialTranscript.text}...</p>
              </div>
            )}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
} 