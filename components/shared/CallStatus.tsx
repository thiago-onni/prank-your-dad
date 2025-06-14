import React from 'react';
import { Card, CardContent } from '@/components/ui/card';
import { PhoneCall } from 'lucide-react';

interface CallStatusProps {
  status: string;
  callId?: string;
}

export function CallStatus({ status, callId }: CallStatusProps) {
  if (!status) return null;

  return (
    <Card className="bg-gray-900/50 border-gray-700 mt-6">
      <CardContent className="p-6">
        <div className="text-center">
          <div className="animate-pulse">
            <PhoneCall className="h-8 w-8 text-purple-400 mx-auto mb-2" />
          </div>
          <p className="text-white font-medium">{status}</p>
          {callId && (
            <p className="text-sm text-gray-400 mt-1">Call ID: {callId}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
} 