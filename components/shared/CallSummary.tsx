import React from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { CheckCircle2, Twitter, RotateCcw } from "lucide-react";

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
          <div className="flex flex-col gap-3">
            <Button
              onClick={() => {
                const tweetText = encodeURIComponent(
                  "I just prank called my dad with my AI voice clone... you can prank your dad too at call-dad.vapi.ai 🤖📞"
                );
                window.open(
                  `https://twitter.com/intent/tweet?text=${tweetText}`,
                  "_blank"
                );
              }}
              className="w-full bg-gradient-to-r from-blue-500 to-blue-600 hover:from-blue-600 hover:to-blue-700"
            >
              <Twitter className="h-4 w-4 mr-2" />
              Share on X/Twitter
            </Button>
            <Button
              onClick={onStartOver}
              variant="outline"
              className="w-full bg-gray-700/50 border-gray-600 text-gray-300 hover:bg-gray-600 hover:text-white"
            >
              <RotateCcw className="h-4 w-4 mr-2" />
              Start Another Prank
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
