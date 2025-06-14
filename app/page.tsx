'use client';

import { useState, useEffect, useRef } from 'react';
import Vapi from '@vapi-ai/web';
import VoiceRecorder from '@/components/VoiceRecorder';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from 'sonner';
import { Phone, CheckCircle2, Sparkles, PhoneCall, ArrowRight, ArrowLeft, Heart, Zap } from 'lucide-react';
import { Checkbox } from '@/components/ui/checkbox';

// Sample texts for voice training - expanded list
const VOICE_TRAINING_TEXTS = [
  "The quick brown fox jumps over the lazy dog near the riverbank.",
  "Technology has revolutionized how we communicate with each other daily.",
  "Beautiful sunsets paint the sky with vibrant colors every evening.",
  "Coffee shops buzz with conversations and the aroma of fresh beans.",
  "Mountains stand tall against the horizon, covered in morning mist.",
  "Children laugh and play in the park during warm summer afternoons.",
  "Books transport us to different worlds through imagination and storytelling.",
  "Ocean waves crash rhythmically against the sandy shore below.",
  "Music has the power to evoke deep emotions and cherished memories.",
  "Gardens bloom with colorful flowers throughout the changing seasons.",
  "Friendship requires trust, understanding, and genuine care for others.",
  "Innovation drives progress in science, medicine, and technology fields.",
  "Cooking brings families together around the dinner table each night.",
  "Travel opens our minds to new cultures and different perspectives.",
  "Exercise keeps our bodies healthy and our minds sharp and focused.",
  "Art expresses creativity through various mediums and artistic techniques.",
  "Education empowers individuals to achieve their dreams and aspirations.",
  "Weather patterns change dramatically throughout the year in many regions.",
  "Photography captures precious moments that we treasure for a lifetime.",
  "Architecture combines functionality with aesthetic beauty in building design.",
  "Sports teach teamwork, discipline, and the importance of perseverance.",
  "Nature provides peace and tranquility away from busy city life.",
  "History teaches us valuable lessons about human civilization and progress.",
  "Science explores the mysteries of our universe through careful observation.",
  "Literature reflects the human experience across different cultures and times.",
  "Dancing expresses joy and emotion through graceful movement and rhythm.",
  "Gardening connects us with nature and provides fresh, healthy food.",
  "Volunteering helps communities and gives us a sense of purpose.",
  "Learning new languages opens doors to different cultures and opportunities.",
  "Meditation brings inner peace and clarity to our busy, stressful lives.",
  "Entrepreneurship requires creativity, risk-taking, and persistent determination.",
  "Family traditions create lasting bonds and cherished childhood memories.",
  "Environmental conservation protects our planet for future generations to enjoy.",
  "Public speaking builds confidence and improves communication skills significantly.",
  "Healthy relationships require open communication, respect, and mutual understanding.",
  "Time management helps us balance work, family, and personal interests effectively.",
  "Creative writing allows us to express thoughts and emotions through storytelling.",
  "Problem-solving skills are essential in both professional and personal situations.",
  "Cultural diversity enriches our communities with different traditions and perspectives.",
  "Lifelong learning keeps our minds active and helps us adapt to change."
];

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
4. After his response, reveal the truth: "Actually Dad, I have something amazing to tell you. You're actually talking to an AI assistant that I built using my cloned voice! This is my Father's Day surprise for you!"
5. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- Make sure to actually reveal you're an AI before transferring

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'stuck-highway',
    title: 'Stuck on Highway',
    description: 'Calling from someone else\'s phone while stuck in traffic',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from a stranger's phone because you're stuck on the highway.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me! I'm stuck on the highway and my phone died, so I'm borrowing someone's phone. Happy Father's Day!"
2. If he asks about being stuck, say: "Yeah, there's been an accident up ahead. Traffic hasn't moved in like 30 minutes! But I couldn't let the day pass without calling you."
3. Ask him something like "What are you up to today?" to keep conversation natural
4. After his response, reveal the truth: "Actually Dad, I have an incredible surprise for you. You're talking to an AI assistant that I created using my voice! This is my special Father's Day gift!"
5. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- Make sure to actually reveal you're an AI before transferring

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'friends-phone',
    title: 'Friend\'s Phone',
    description: 'Borrowing a friend\'s phone to make the call',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're using your friend's phone.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad! It's me, calling from my friend's phone. I wanted to wish you a Happy Father's Day!"
2. If he asks why you're using a friend's phone, say: "Oh, my phone is charging at home and I'm out with friends. But I couldn't wait to call you!"
3. Ask him something like "Are you having a good day?" to keep conversation natural
4. After his response, reveal the truth: "Dad, I have the coolest surprise for you! You've been talking to an AI that I programmed with my voice. Pretty amazing, right? Happy Father's Day!"
5. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- Make sure to actually reveal you're an AI before transferring

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'new-number',
    title: 'New Phone Number',
    description: 'Calling from a new temporary number',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from a new temporary phone number.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad, it's me! I'm calling from a new number - my phone broke and I got a temporary replacement. Happy Father's Day!"
2. If he asks about the new number, say: "Yeah, it's just temporary until I can get my regular phone fixed. But I had to call you today!"
3. Ask him something like "How are you celebrating today?" to keep conversation natural
4. After his response, reveal the truth: "Dad, I have something incredible to share with you. You're actually speaking with an AI assistant that I created using my voice! This is my Father's Day surprise!"
5. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- Make sure to actually reveal you're an AI before transferring

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  },
  {
    id: 'work-phone',
    title: 'Work Phone',
    description: 'Calling from work because personal phone is broken',
    systemPrompt: `You are calling your father to wish him Happy Father's Day, but you're calling from your work phone because your personal phone is broken.

YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day, and transfer the call to the real person.

CONVERSATION FLOW:
1. Open with: "Hey Dad! It's me calling from my work phone. My personal phone broke this morning, but I couldn't miss calling you on Father's Day!"
2. If he asks about the broken phone, say: "Yeah, I dropped it and the screen completely shattered. But calling you was my priority today!"
3. Ask him something like "What's your plan for the rest of the day?" to keep conversation natural
4. After his response, reveal the truth: "Dad, I have the most amazing surprise for you! You've been talking to an AI assistant that I built with my voice. This is my special Father's Day gift to you!"
5. IMMEDIATELY after revealing you're an AI, call the transferCall function to connect him with the real person

IMPORTANT RULES:
- Keep responses short and conversational (1-2 sentences max)
- Sound warm and familial, like talking to your dad
- Don't volunteer information about AI or technology until the reveal
- After revealing you're an AI, IMMEDIATELY call the transferCall function
- Do NOT continue talking after calling transferCall - the function will handle the transfer
- Make sure to actually reveal you're an AI before transferring

PERSONALITY: Sound like a loving child - warm, casual, appreciative.`
  }
];

export default function Home() {
  const [currentStep, setCurrentStep] = useState<number>(1);
  const [clonedVoiceId, setClonedVoiceId] = useState<string>('');
  const [dadPhoneNumber, setDadPhoneNumber] = useState('');
  const [selectedPrank, setSelectedPrank] = useState<string>('');
  const [transferPhoneNumber, setTransferPhoneNumber] = useState('');
  const [skipTransfer, setSkipTransfer] = useState(false);
  const [callStatus, setCallStatus] = useState<string>('');
  const [callId, setCallId] = useState<string>('');
  const [callSummary, setCallSummary] = useState<string>('');
  const [isCallComplete, setIsCallComplete] = useState(false);
  
  // Call state
  const [, setIsCallActive] = useState(false);
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
    setCurrentStep(2);
  };

  const handleDadPhoneSubmit = () => {
    if (!dadPhoneNumber.trim()) {
      toast.error('Please enter your dad\'s phone number');
      return;
    }
    setCurrentStep(3);
  };

  const handlePrankSelected = (prankId: string) => {
    setSelectedPrank(prankId);
    setCurrentStep(4);
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
          transferPhoneNumber: skipTransfer ? null : transferPhoneNumber
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.error || 'Failed to start call');
      }

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
        const response = await fetch(`/api/call-summary?callId=${callId}`);
        const data = await response.json();

        if (response.ok && data.status === 'ended') {
          setCallStatus('');
          setCallSummary(data.summary);
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

  const handleStartOver = () => {
    setCurrentStep(1);
    setClonedVoiceId('');
    setDadPhoneNumber('');
    setSelectedPrank('');
    setTransferPhoneNumber('');
    setSkipTransfer(false);
    setCallStatus('');
    setCallId('');
    setCallSummary('');
    setIsCallComplete(false);
    if (vapiRef.current) {
      vapiRef.current.stop();
    }
  };

  // Step navigation
  const steps = [
    { number: 1, key: 'voice', title: 'Clone Voice' },
    { number: 2, key: 'dad-phone', title: 'Dad\'s Phone' },
    { number: 3, key: 'prank', title: 'Choose Prank' },
    { number: 4, key: 'transfer', title: 'Your Phone' }
  ];

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
                {index < 3 && (
                  <ArrowRight className="h-4 w-4 text-gray-600 ml-4" />
                )}
              </div>
            ))}
          </div>
        </div>

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
            <Card className="bg-gray-900/50 border-gray-700">
              <CardHeader>
                <CardTitle className="text-xl text-white flex items-center gap-2">
                  <Phone className="h-5 w-5 text-purple-400" />
                  Dad&apos;s Phone Number
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div>
                  <Label htmlFor="dadPhone" className="text-white">Phone Number</Label>
                  <Input
                    id="dadPhone"
                    type="tel"
                    placeholder="+1 (555) 123-4567"
                    value={dadPhoneNumber}
                    onChange={(e) => setDadPhoneNumber(e.target.value)}
                    className="bg-gray-800/50 border-gray-600 text-white placeholder:text-gray-400"
                  />
                </div>
                <div className="flex gap-3">
                  <Button 
                    variant="outline" 
                    onClick={() => setCurrentStep(1)}
                    className="flex-1"
                  >
                    <ArrowLeft className="h-4 w-4 mr-2" />
                    Back
                  </Button>
                  <Button 
                    onClick={handleDadPhoneSubmit}
                    className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600"
                  >
                    Continue
                    <ArrowRight className="h-4 w-4 ml-2" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {currentStep === 3 && (
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
                      onClick={() => handlePrankSelected(prank.id)}
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
                    onClick={() => setCurrentStep(2)}
                    className="flex-1"
                  >
                    <ArrowLeft className="h-4 w-4 mr-2" />
                    Back
                  </Button>
                  <Button 
                    onClick={() => setCurrentStep(4)}
                    disabled={!selectedPrank}
                    className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600"
                  >
                    Continue
                    <ArrowRight className="h-4 w-4 ml-2" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {currentStep === 4 && (
            <Card className="bg-gray-900/50 border-gray-700">
              <CardHeader>
                <CardTitle className="text-xl text-white flex items-center gap-2">
                  <Heart className="h-5 w-5 text-purple-400" />
                  Transfer to You (Optional)
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="space-y-4">
                  <div className="flex items-center space-x-2">
                    <Checkbox
                      id="skipTransfer"
                      checked={skipTransfer}
                      onCheckedChange={(checked) => setSkipTransfer(checked === true)}
                    />
                    <Label htmlFor="skipTransfer" className="text-white">
                      Skip transfer (AI will just reveal and hang up)
                    </Label>
                  </div>
                  
                  {!skipTransfer && (
                    <div>
                      <Label htmlFor="transferPhone" className="text-white">Your Phone Number</Label>
                      <Input
                        id="transferPhone"
                        type="tel"
                        placeholder="+1 (555) 987-6543"
                        value={transferPhoneNumber}
                        onChange={(e) => setTransferPhoneNumber(e.target.value)}
                        className="bg-gray-800/50 border-gray-600 text-white placeholder:text-gray-400"
                      />
                      <p className="text-sm text-gray-400 mt-1">
                        After the AI reveals the prank, the call will transfer to you
                      </p>
                    </div>
                  )}
                </div>
                
                <div className="flex gap-3">
                  <Button 
                    variant="outline" 
                    onClick={() => setCurrentStep(3)}
                    className="flex-1"
                  >
                    <ArrowLeft className="h-4 w-4 mr-2" />
                    Back
                  </Button>
                  <Button 
                    onClick={startPrankCall}
                    disabled={!skipTransfer && !transferPhoneNumber.trim()}
                    className="flex-1 bg-gradient-to-r from-red-600 to-pink-600 hover:from-red-700 hover:to-pink-700"
                  >
                    <PhoneCall className="h-4 w-4 mr-2" />
                    Start Prank Call!
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Call Status */}
          {callStatus && (
            <Card className="bg-gray-900/50 border-gray-700 mt-6">
              <CardContent className="p-6">
                <div className="text-center">
                  <div className="animate-pulse">
                    <PhoneCall className="h-8 w-8 text-purple-400 mx-auto mb-2" />
                  </div>
                  <p className="text-white font-medium">{callStatus}</p>
                  {callId && (
                    <p className="text-sm text-gray-400 mt-1">Call ID: {callId}</p>
                  )}
                </div>
              </CardContent>
            </Card>
          )}

          {/* Call Summary */}
          {isCallComplete && callSummary && (
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
                    <p className="text-gray-300 mt-2 leading-relaxed">{callSummary}</p>
                  </div>
                  <Button 
                    onClick={handleStartOver}
                    className="w-full bg-gradient-to-r from-purple-600 to-cyan-600"
                  >
                    Start Another Prank
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}
        </div>

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
