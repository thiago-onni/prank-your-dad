'use client';

import { useState } from 'react';
import VoiceRecorder from '@/components/VoiceRecorder';
import { PhoneNumberStep } from '@/components/steps/PhoneNumberStep';
import { PrankSelectionStep } from '@/components/steps/PrankSelectionStep';
import { TransferStep } from '@/components/steps/TransferStep';
import { ProgressSteps } from '@/components/shared/ProgressSteps';
import { AppHeader } from '@/components/shared/AppHeader';
import { CallStatus } from '@/components/shared/CallStatus';
import { CallSummary } from '@/components/shared/CallSummary';
import { LiveTranscript } from '@/components/shared/LiveTranscript';
import { usePrankCall } from '@/hooks/usePrankCall';
import { useVapiCall } from '@/hooks/useVapiCall';
import { PRANK_SCENARIOS } from '@/constants/prank-scenarios';
import { VOICE_TRAINING_TEXTS } from '@/constants/voice-training';
import type { Step } from '@/types';

const STEPS: Step[] = [
  { number: 1, key: 'voice', title: 'Clone Voice' },
  { number: 2, key: 'dad-phone', title: 'Dad\'s Phone' },
  { number: 3, key: 'prank', title: 'Choose Prank' },
  { number: 4, key: 'transfer', title: 'Your Phone' }
];

export default function Home() {
  // Step management
  const [currentStep, setCurrentStep] = useState<number>(1);
  
  // Form state
  const [clonedVoiceId, setClonedVoiceId] = useState<string>('');
  const [dadPhoneNumber, setDadPhoneNumber] = useState('');
  const [selectedPrank, setSelectedPrank] = useState<string>('');
  const [transferPhoneNumber, setTransferPhoneNumber] = useState('');
  const [skipTransfer, setSkipTransfer] = useState(false);

  // Custom hooks
  const {
    callStatus,
    callId,
    callSummary,
    isCallComplete,
    startPrankCall,
    resetCall,
  } = usePrankCall();

  const {
    transcript,
    partialTranscript,
    resetTranscript,
    stopCall,
  } = useVapiCall();

  // Event handlers
  const handleVoiceCloned = (voiceId: string) => {
    setClonedVoiceId(voiceId);
    setCurrentStep(2);
  };

  const handleStartPrankCall = async () => {
    if (!clonedVoiceId || !dadPhoneNumber || !selectedPrank) {
      return;
    }

    const selectedPrankData = PRANK_SCENARIOS.find(p => p.id === selectedPrank);
    if (!selectedPrankData) {
      return;
    }

    await startPrankCall({
      dadPhoneNumber,
      voiceId: clonedVoiceId,
      systemPrompt: selectedPrankData.systemPrompt,
      transferPhoneNumber: skipTransfer ? null : transferPhoneNumber
    });
  };

  const handleStartOver = () => {
    setCurrentStep(1);
    setClonedVoiceId('');
    setDadPhoneNumber('');
    setSelectedPrank('');
    setTransferPhoneNumber('');
    setSkipTransfer(false);
    resetCall();
    resetTranscript();
    stopCall();
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-900 via-gray-800 to-gray-900 flex items-center justify-center p-4">
      {/* Background decoration */}
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-3xl opacity-10"></div>
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-cyan-500 rounded-full mix-blend-multiply filter blur-3xl opacity-10"></div>
      </div>

      <div className="w-full max-w-4xl relative z-10">
        <AppHeader />
        
        <ProgressSteps steps={STEPS} currentStep={currentStep} />

        {/* Main Content */}
        <div className="max-w-2xl mx-auto">
          {currentStep === 1 && (
            <VoiceRecorder 
              onVoiceCloned={handleVoiceCloned}
              hideSystemPrompt={true}
              voiceTrainingTexts={VOICE_TRAINING_TEXTS}
            />
          )}

          {currentStep === 2 && (
            <PhoneNumberStep
              phoneNumber={dadPhoneNumber}
              onPhoneNumberChange={setDadPhoneNumber}
              onBack={() => setCurrentStep(1)}
              onNext={() => setCurrentStep(3)}
            />
          )}

          {currentStep === 3 && (
            <PrankSelectionStep
              selectedPrank={selectedPrank}
              onPrankSelect={setSelectedPrank}
              onBack={() => setCurrentStep(2)}
              onNext={() => setCurrentStep(4)}
            />
          )}

          {currentStep === 4 && (
            <TransferStep
              transferPhoneNumber={transferPhoneNumber}
              skipTransfer={skipTransfer}
              onTransferPhoneNumberChange={setTransferPhoneNumber}
              onSkipTransferChange={setSkipTransfer}
              onBack={() => setCurrentStep(3)}
              onStartCall={handleStartPrankCall}
              isDisabled={!!callStatus}
            />
          )}

          <CallStatus status={callStatus} callId={callId} />

          {isCallComplete && callSummary && (
            <CallSummary summary={callSummary} onStartOver={handleStartOver} />
          )}
        </div>

        <LiveTranscript 
          transcript={transcript} 
          partialTranscript={partialTranscript} 
        />

        {/* Footer */}
        <div className="mt-12 text-center">
          <p className="text-gray-400 text-sm">
            Built with ❤️ by{' '}
            <a 
              href="https://vapi.ai" 
              target="_blank" 
              rel="noopener noreferrer"
              className="text-purple-400 hover:text-purple-300 transition-colors font-medium"
            >
              Vapi
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
