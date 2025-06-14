import React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Sparkles, CheckCircle2, ArrowLeft, ArrowRight } from 'lucide-react';
import { PRANK_SCENARIOS } from '@/constants/prank-scenarios';

interface PrankSelectionStepProps {
  selectedPrank: string;
  onPrankSelect: (prankId: string) => void;
  onBack: () => void;
  onNext: () => void;
}

export function PrankSelectionStep({
  selectedPrank,
  onPrankSelect,
  onBack,
  onNext
}: PrankSelectionStepProps) {
  return (
    <Card className="bg-gray-900/50 border-gray-700">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-purple-400" />
          Choose Your Prank
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3">
          {PRANK_SCENARIOS.map((prank) => (
            <button
              key={prank.id}
              className={`p-4 rounded-lg border text-left transition-all ${
                selectedPrank === prank.id
                  ? 'bg-purple-500/20 border-purple-500/50 text-purple-200'
                  : 'bg-gray-900/50 border-gray-600 text-gray-300 hover:bg-gray-800/50 hover:border-gray-500'
              }`}
              onClick={() => onPrankSelect(prank.id)}
            >
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="font-medium">{prank.title}</h3>
                  <p className="text-sm opacity-75 mt-1">{prank.description}</p>
                </div>
                {selectedPrank === prank.id && (
                  <CheckCircle2 className="h-5 w-5 text-purple-400" />
                )}
              </div>
            </button>
          ))}
        </div>
        <div className="flex gap-3">
          <Button 
            variant="outline" 
            onClick={onBack}
            className="flex-1"
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back
          </Button>
          <Button 
            onClick={onNext}
            disabled={!selectedPrank}
            className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600"
          >
            Continue
            <ArrowRight className="h-4 w-4 ml-2" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
} 