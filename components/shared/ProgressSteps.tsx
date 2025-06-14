import React from 'react';
import { ArrowRight } from 'lucide-react';
import type { Step } from '@/types';

interface ProgressStepsProps {
  steps: Step[];
  currentStep: number;
}

export function ProgressSteps({ steps, currentStep }: ProgressStepsProps) {
  return (
    <div className="flex justify-center mb-8">
      <div className="flex items-center space-x-4">
        {steps.map(({ number, key, title }, index) => (
          <div key={key} className="flex items-center">
            <div className={`flex items-center gap-2 px-3 py-2 rounded-lg transition-all ${
              currentStep === number 
                ? 'bg-purple-500/20 text-purple-300 border border-purple-500/30' 
                : currentStep > number
                ? 'bg-green-500/20 text-green-300 border border-green-500/30'
                : 'bg-gray-800/50 text-gray-500 border border-gray-700'
            }`}>
              <span className="text-sm font-medium">{number}</span>
              <div className="hidden sm:block">
                <div className="text-sm font-medium">{title}</div>
              </div>
            </div>
            {index < steps.length - 1 && (
              <ArrowRight className="h-4 w-4 text-gray-600 ml-4" />
            )}
          </div>
        ))}
      </div>
    </div>
  );
} 