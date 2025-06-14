import React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { CheckCircle2 } from 'lucide-react';

interface CallSummaryProps {
  summary: string;
  onStartOver: () => void;
}

export function CallSummary({ summary, onStartOver }: CallSummaryProps) {
  return (
    <Card className="bg-gray-900/50 border-gray-700 mt-6">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <CheckCircle2 className="h-5 w-5 text-green-400" />
          Call Complete!
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          <div>
            <Label className="text-white font-medium">Call Summary:</Label>
            <p className="text-gray-300 mt-2 leading-relaxed">{summary}</p>
          </div>
          <Button 
            onClick={onStartOver}
            className="w-full bg-gradient-to-r from-purple-600 to-cyan-600"
          >
            Start Another Prank
          </Button>
        </div>
      </CardContent>
    </Card>
  );
} 