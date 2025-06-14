'use client';

import { useState, useEffect, useRef } from 'react';
import Vapi from '@vapi-ai/web';
import VoiceRecorder from '@/components/VoiceRecorder';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from 'sonner';
import { Mic, Phone, PhoneOff, AlertCircle, CheckCircle2, Sparkles, PhoneCall, ArrowRight, ArrowLeft, Heart, Zap } from 'lucide-react';

// Pre-defined prank scenarios
const PRANK_SCENARIOS = [
  {
    id: 'lost-phone',
    title: 'Lost My Phone',
    description: 'Calling from a friend\'s phone because I lost mine',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from a friend's phone because you lost yours.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me. I'm calling from my friend's phone because I lost mine. Just wanted to call and say Happy Father's Day!"
2. If he asks about the lost phone, say: "Yeah, I think I left it at a restaurant earlier. But I didn't want to miss calling you today!"
3. Ask him a follow-up question like "How's your day been?" to keep conversation natural
4. After EXACTLY 2 exchanges, reveal the truth: "Actually, I have something to tell you, Dad."
5. When he responds, say: "Well, you're talking to an AI I built! Surprise for Father's Day!"
6. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function with the destination parameter
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- NEVER say you are "stopping" or "letting someone take over" - just call transferCall

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'highway-stuck',
    title: 'Stuck on Highway',
    description: 'Calling from roadside assistance phone while stuck',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from a roadside assistance phone because you're stuck on the highway.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me. I'm stuck on the highway and had to use the roadside assistance phone. Just wanted to call and say Happy Father's Day!"
2. If he asks about being stuck, say: "Yeah, flat tire, but the tow truck is coming. I didn't want to miss calling you today though!"
3. Ask him a follow-up question like "How's your day been?" to keep conversation natural
4. After EXACTLY 2 exchanges, reveal the truth: "Actually, I have something to tell you, Dad."
5. When he responds, say: "Well, you're talking to an AI I built! Surprise for Father's Day!"
6. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function with the destination parameter
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- NEVER say you are "stopping" or "letting someone take over" - just call transferCall

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'friends-phone',
    title: 'Friend\'s Phone',
    description: 'Borrowing a friend\'s phone to make the call',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're using a friend's phone.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me. I'm borrowing my friend's phone to call you. Just wanted to say Happy Father's Day!"
2. If he asks about the phone, say: "Yeah, mine died and I forgot my charger. But I had to call you today!"
3. Ask him a follow-up question like "How's your day been?" to keep conversation natural
4. After EXACTLY 2 exchanges, reveal the truth: "Actually, I have something to tell you, Dad."
5. When he responds, say: "Well, you're talking to an AI I built! Surprise for Father's Day!"
6. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function with the destination parameter
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- NEVER say you are "stopping" or "letting someone take over" - just call transferCall

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'work-phone',
    title: 'Work Phone',
    description: 'Calling from work because personal phone is broken',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from your work phone because your personal phone is broken.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me. I'm calling from my work phone because mine is broken. Just wanted to say Happy Father's Day!"
2. If he asks about the broken phone, say: "Yeah, dropped it this morning and the screen is completely shattered. But I had to call you today!"
3. Ask him a follow-up question like "How's your day been?" to keep conversation natural
4. After EXACTLY 2 exchanges, reveal the truth: "Actually, I have something to tell you, Dad."
5. When he responds, say: "Well, you're talking to an AI I built! Surprise for Father's Day!"
6. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function with the destination parameter
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- NEVER say you are "stopping" or "letting someone take over" - just call transferCall

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'new-number',
    title: 'New Number',
    description: 'Calling from a new temporary number',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from a new temporary number.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me. I'm calling from a new number - it's temporary while I sort out my phone situation. Just wanted to say Happy Father's Day!"
2. If he asks about the new number, say: "Yeah, long story with my carrier, but I didn't want to miss calling you today!"
3. Ask him a follow-up question like "How's your day been?" to keep conversation natural
4. After EXACTLY 2 exchanges, reveal the truth: "Actually, I have something to tell you, Dad."
5. When he responds, say: "Well, you're talking to an AI I built! Surprise for Father's Day!"
6. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function with the destination parameter
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- NEVER say you are "stopping" or "letting someone take over" - just call transferCall

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  }
];

export default function Home() {
  const [currentStep, setCurrentStep] = useState<'voice' | 'dad-phone' | 'prank' | 'transfer' | 'calling'>('voice');
  const [clonedVoiceId, setClonedVoiceId] = useState<string | null>(null);
  const [dadPhoneNumber, setDadPhoneNumber] = useState('');
  const [selectedPrank, setSelectedPrank] = useState<string | null>(null);
  const [transferPhoneNumber, setTransferPhoneNumber] = useState('');
  const [skipTransfer, setSkipTransfer] = useState(false);
  
  // Call state
  const [isCallActive, setIsCallActive] = useState(false);
  const [callStatus, setCallStatus] = useState('');
  const [transcript, setTranscript] = useState<Array<{role: string, text: string}>>([]);
  const [partialTranscript, setPartialTranscript] = useState<{role: string, text: string} | null>(null);
  
  const vapiRef = useRef<Vapi | null>(null);

  useEffect(() => {
    // Check if API key exists
    const apiKey = process.env.NEXT_PUBLIC_VAPI_PUBLIC_KEY;
    if (!apiKey) {
      setCallStatus('Error: Missing Vapi public key');
      console.error('NEXT_PUBLIC_VAPI_PUBLIC_KEY is not set');
      return;
    }

    // Initialize Vapi with public key
    try {
      vapiRef.current = new Vapi(apiKey);
    } catch (error) {
      console.error('Failed to initialize Vapi:', error);
      setCallStatus('Error: Failed to initialize Vapi');
      return;
    }

    // Set up event listeners
    const vapi = vapiRef.current;
    
    vapi.on('call-start', () => {
      setCallStatus('AI Connected');
      setIsCallActive(true);
      toast.success('Prank call started!');
    });

    vapi.on('call-end', () => {
      setCallStatus('Call Ended');
      setIsCallActive(false);
      setPartialTranscript(null);
      toast.info('Prank call ended');
    });

    vapi.on('speech-start', () => {
      setCallStatus('AI Speaking...');
    });

    vapi.on('speech-end', () => {
      setCallStatus('AI Listening...');
    });

    vapi.on('message', (message: { type: string; role?: string; transcript?: string; transcriptType?: string }) => {
      if (message.type === 'transcript' && message.role && message.transcript) {
        const role = message.role;
        const text = message.transcript;
        
        if (message.transcriptType === 'final') {
          setTranscript(prev => [...prev, { role, text }]);
          setPartialTranscript(null);
        } else if (message.transcriptType === 'partial') {
          setPartialTranscript({ role, text });
        }
      }
    });

    vapi.on('error', (error: unknown) => {
      console.error('Vapi error:', error);
      const errorObj = error as { message?: string; error?: string } | null;
      const errorMessage = errorObj?.message || errorObj?.error || JSON.stringify(error);
      setCallStatus('Error: ' + errorMessage);
      toast.error('Error: ' + errorMessage);
    });

    return () => {
      if (vapi) {
        vapi.stop();
      }
    };
  }, []);

  const handleVoiceCloned = (voiceId: string) => {
    setClonedVoiceId(voiceId);
    setCurrentStep('dad-phone');
    toast.success('Voice cloned! Now enter your dad\'s phone number.');
  };

  const handleDadPhoneSubmit = () => {
    if (!dadPhoneNumber.trim()) {
      toast.error('Please enter your dad\'s phone number');
      return;
    }
    setCurrentStep('prank');
    toast.success('Phone number saved! Now choose your prank scenario.');
  };

  const handlePrankSelect = (prankId: string) => {
    setSelectedPrank(prankId);
    setCurrentStep('transfer');
    toast.success('Prank selected! Almost ready to call.');
  };

  const handleTransferSubmit = () => {
    setCurrentStep('calling');
    startPrankCall();
  };

  const startPrankCall = async () => {
    if (!clonedVoiceId || !dadPhoneNumber || !selectedPrank) {
      toast.error('Missing required information');
      return;
    }

    const selectedPrankData = PRANK_SCENARIOS.find(p => p.id === selectedPrank);
    if (!selectedPrankData) {
      toast.error('Invalid prank selection');
      return;
    }

    try {
      setCallStatus('Starting prank call...');
      
      const response = await fetch('/api/prank-call', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          dadPhoneNumber,
          voiceId: clonedVoiceId,
          systemPrompt: selectedPrankData.systemPrompt,
          transferPhoneNumber: skipTransfer ? null : transferPhoneNumber,
        }),
      });

      const result = await response.json();

      if (!response.ok) {
        throw new Error(result.error || 'Failed to start prank call');
      }

      setCallStatus('Prank call started!');
      setIsCallActive(true);
      toast.success('Prank call started! Your dad should be receiving the call now.');
      
    } catch (error) {
      console.error('Failed to start prank call:', error);
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      toast.error(`Failed to start the prank call: ${errorMessage}`);
      setCallStatus('Failed to start call');
      setCurrentStep('transfer');
    }
  };

  const handleEndCall = () => {
    if (vapiRef.current) {
      vapiRef.current.stop();
    }
  };

  const handleGoBack = () => {
    switch (currentStep) {
      case 'dad-phone':
        setCurrentStep('voice');
        break;
      case 'prank':
        setCurrentStep('dad-phone');
        break;
      case 'transfer':
        setCurrentStep('prank');
        break;
      case 'calling':
        setCurrentStep('transfer');
        break;
    }
  };

  const handleStartOver = () => {
    setCurrentStep('voice');
    setClonedVoiceId(null);
    setDadPhoneNumber('');
    setSelectedPrank(null);
    setTransferPhoneNumber('');
    setSkipTransfer(false);
    setTranscript([]);
    setPartialTranscript(null);
    if (vapiRef.current) {
      vapiRef.current.stop();
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-900 via-gray-800 to-gray-900 flex items-center justify-center p-4">
      {/* Background decoration */}
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-3xl opacity-10"></div>
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-cyan-500 rounded-full mix-blend-multiply filter blur-3xl opacity-10"></div>
      </div>

      <div className="w-full max-w-4xl relative z-10">
        {/* Header */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center gap-3 mb-4">
            <div className="p-3 bg-gradient-to-br from-purple-500 to-cyan-500 rounded-2xl">
              <PhoneCall className="h-8 w-8 text-white" />
            </div>
            <h1 className="text-4xl md:text-5xl font-bold text-white">
              Prank Your Dad
            </h1>
          </div>
          <p className="text-gray-400 text-lg md:text-xl">
            AI-powered Father&apos;s Day prank call with your cloned voice
          </p>
          <div className="flex items-center justify-center gap-2 mt-3">
            <Badge variant="outline" className="bg-purple-500/10 text-purple-300 border-purple-500/30">
              <Sparkles className="h-3 w-3 mr-1" />
              100% Free
            </Badge>
            <Badge variant="outline" className="bg-cyan-500/10 text-cyan-300 border-cyan-500/30">
              <Zap className="h-3 w-3 mr-1" />
              Father&apos;s Day Special
            </Badge>
          </div>
        </div>

        {/* Progress Steps */}
        <div className="flex justify-center mb-8">
          <div className="flex items-center space-x-4">
            {[
              { key: 'voice', label: '1. Clone Voice', icon: Mic },
              { key: 'dad-phone', label: '2. Dad\'s Phone', icon: Phone },
              { key: 'prank', label: '3. Select Prank', icon: Sparkles },
              { key: 'transfer', label: '4. Your Phone', icon: Heart },
            ].map(({ key, label, icon: Icon }, index) => (
              <div key={key} className="flex items-center">
                <div className={`flex items-center gap-2 px-3 py-2 rounded-lg transition-all ${
                  currentStep === key 
                    ? 'bg-purple-500/20 text-purple-300 border border-purple-500/30' 
                    : ['voice', 'dad-phone', 'prank', 'transfer'].indexOf(currentStep) > index
                    ? 'bg-green-500/20 text-green-300 border border-green-500/30'
                    : 'bg-gray-800/50 text-gray-500 border border-gray-700'
                }`}>
                  <Icon className="h-4 w-4" />
                  <span className="text-sm font-medium">{label}</span>
                </div>
                {index < 3 && (
                  <ArrowRight className="h-4 w-4 text-gray-600 ml-4" />
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Step Content */}
        {currentStep === 'voice' && (
          <VoiceRecorder 
            onVoiceCloned={handleVoiceCloned} 
            systemPrompt=""
            onSystemPromptChange={() => {}}
            hideSystemPrompt={true}
          />
        )}

        {currentStep === 'dad-phone' && (
          <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
            <CardHeader>
              <CardTitle className="text-xl text-white flex items-center gap-2">
                <Phone className="h-5 w-5 text-purple-400" />
                Enter Your Dad&apos;s Phone Number
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="space-y-2">
                <Label htmlFor="dadPhone" className="text-white font-medium">
                  Dad&apos;s Phone Number
                </Label>
                <Input
                  id="dadPhone"
                  type="tel"
                  value={dadPhoneNumber}
                  onChange={(e) => setDadPhoneNumber(e.target.value)}
                  placeholder="+1 (555) 123-4567"
                  className="bg-gray-900/50 border-gray-600 text-white placeholder:text-gray-400 focus:border-purple-500 text-lg h-12"
                />
                <p className="text-gray-400 text-sm">
                  We&apos;ll call this number with your cloned voice to prank your dad!
                </p>
              </div>

              <div className="flex gap-4">
                <Button 
                  onClick={handleGoBack} 
                  variant="outline"
                  className="bg-gray-800/50 border-gray-600 text-gray-300 hover:bg-gray-700/50 hover:text-white"
                >
                  <ArrowLeft className="mr-2 h-4 w-4" />
                  Back
                </Button>
                <Button 
                  onClick={handleDadPhoneSubmit}
                  className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700 h-12 text-lg"
                >
                  Continue
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {currentStep === 'prank' && (
          <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
            <CardHeader>
              <CardTitle className="text-xl text-white flex items-center gap-2">
                <Sparkles className="h-5 w-5 text-purple-400" />
                Choose Your Prank Scenario
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <p className="text-gray-300">
                Pick an excuse for why you&apos;re calling from a random number:
              </p>
              
              <div className="grid gap-4">
                {PRANK_SCENARIOS.map((prank) => (
                  <div
                    key={prank.id}
                    className={`p-4 rounded-lg border cursor-pointer transition-all ${
                      selectedPrank === prank.id
                        ? 'bg-purple-500/20 border-purple-500/50 text-white'
                        : 'bg-gray-900/50 border-gray-600 text-gray-300 hover:bg-gray-800/50 hover:border-gray-500'
                    }`}
                    onClick={() => handlePrankSelect(prank.id)}
                  >
                    <div className="flex items-center justify-between">
                      <div>
                        <h3 className="font-medium text-lg">{prank.title}</h3>
                        <p className="text-sm opacity-70 mt-1">{prank.description}</p>
                      </div>
                      {selectedPrank === prank.id && (
                        <CheckCircle2 className="h-5 w-5 text-purple-400" />
                      )}
                    </div>
                  </div>
                ))}
              </div>

              <div className="flex gap-4">
                <Button 
                  onClick={handleGoBack} 
                  variant="outline"
                  className="bg-gray-800/50 border-gray-600 text-gray-300 hover:bg-gray-700/50 hover:text-white"
                >
                  <ArrowLeft className="mr-2 h-4 w-4" />
                  Back
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {currentStep === 'transfer' && (
          <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
            <CardHeader>
              <CardTitle className="text-xl text-white flex items-center gap-2">
                <Heart className="h-5 w-5 text-purple-400" />
                Transfer to You (Optional)
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <p className="text-gray-300">
                After the prank reveal, should we transfer your dad to your real phone?
              </p>
              
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="transferPhone" className="text-white font-medium">
                    Your Phone Number (Optional)
                  </Label>
                  <Input
                    id="transferPhone"
                    type="tel"
                    value={transferPhoneNumber}
                    onChange={(e) => setTransferPhoneNumber(e.target.value)}
                    placeholder="+1 (555) 987-6543"
                    className="bg-gray-900/50 border-gray-600 text-white placeholder:text-gray-400 focus:border-purple-500 text-lg h-12"
                    disabled={skipTransfer}
                  />
                </div>

                <div className="flex items-center space-x-2">
                  <input
                    type="checkbox"
                    id="skipTransfer"
                    checked={skipTransfer}
                    onChange={(e) => setSkipTransfer(e.target.checked)}
                    className="rounded border-gray-600 bg-gray-900/50 text-purple-600 focus:ring-purple-500"
                  />
                  <Label htmlFor="skipTransfer" className="text-gray-300">
                    Skip transfer - just end the call after the prank
                  </Label>
                </div>
              </div>

              <Alert className="bg-blue-500/10 border-blue-500/30">
                <AlertCircle className="h-4 w-4 fill-blue-400" />
                <AlertDescription className="text-blue-200">
                  <strong>Ready to prank!</strong> We&apos;ll call your dad with your cloned voice, deliver the prank, and optionally transfer him to you.
                </AlertDescription>
              </Alert>

              <div className="flex gap-4">
                <Button 
                  onClick={handleGoBack} 
                  variant="outline"
                  className="bg-gray-800/50 border-gray-600 text-gray-300 hover:bg-gray-700/50 hover:text-white"
                >
                  <ArrowLeft className="mr-2 h-4 w-4" />
                  Back
                </Button>
                <Button 
                  onClick={handleTransferSubmit}
                  className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700 h-12 text-lg"
                  disabled={!skipTransfer && !transferPhoneNumber.trim()}
                >
                  <Phone className="mr-2 h-5 w-5" />
                  Start Prank Call!
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {currentStep === 'calling' && (
          <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
            <CardHeader>
              <CardTitle className="text-xl text-white flex items-center gap-2">
                <PhoneCall className="h-5 w-5 text-purple-400" />
                Prank Call in Progress
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="text-center">
                <div className="inline-flex items-center gap-3 p-4 bg-green-500/20 border border-green-500/30 rounded-lg">
                  <div className="w-3 h-3 bg-green-400 rounded-full animate-pulse"></div>
                  <span className="text-green-300 font-medium">{callStatus}</span>
                </div>
                <p className="text-gray-400 mt-2">
                  Calling {dadPhoneNumber} with your cloned voice...
                </p>
              </div>

              <div className="flex gap-4 justify-center">
                <Button 
                  onClick={handleEndCall}
                  variant="destructive"
                  className="bg-red-600 hover:bg-red-700"
                  disabled={!isCallActive}
                >
                  <PhoneOff className="mr-2 h-4 w-4" />
                  End Call
                </Button>
                <Button 
                  onClick={handleStartOver}
                  variant="outline"
                  className="bg-gray-800/50 border-gray-600 text-gray-300 hover:bg-gray-700/50 hover:text-white"
                >
                  Start Over
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Live Transcript */}
        {(transcript.length > 0 || partialTranscript) && (
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
        )}

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
