'use client';

import { useState, useEffect, useRef } from 'react';
import Vapi from '@vapi-ai/web';
import VoiceRecorder from '@/components/VoiceRecorder';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { toast } from 'sonner';
import { Mic, MicOff, Phone, PhoneOff, AlertCircle, CheckCircle2, Volume2, Sparkles, PhoneCall, Zap } from 'lucide-react';

export default function Home() {
  const [isCallActive, setIsCallActive] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [callStatus, setCallStatus] = useState('');
  const [transcript, setTranscript] = useState<Array<{role: string, text: string}>>([]);
  const [partialTranscript, setPartialTranscript] = useState<{role: string, text: string} | null>(null);
  const [showInstructions, setShowInstructions] = useState(true);
  const [clonedVoiceId, setClonedVoiceId] = useState<string | null>(null);
  const [step, setStep] = useState<'voice' | 'call'>('voice');
  const [activeTab, setActiveTab] = useState<'clone' | 'predefined'>('clone');
  const vapiRef = useRef<Vapi | null>(null);

  // Pre-defined voice ID for demo purposes
  const PREDEFINED_VOICE_ID = process.env.NEXT_PUBLIC_PREDEFINED_VOICE_ID || '';

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
      toast.success('AI Assistant connected!');
    });

    vapi.on('call-end', () => {
      setCallStatus('AI Disconnected');
      setIsCallActive(false);
      setPartialTranscript(null); // Clear any partial transcript
      toast.info('AI Assistant disconnected');
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
          // Add final transcript to history
          setTranscript(prev => [...prev, { role, text }]);
          setPartialTranscript(null); // Clear partial
        } else if (message.transcriptType === 'partial') {
          // Update partial transcript
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
      // Cleanup
      if (vapi) {
        vapi.stop();
      }
    };
  }, []);

  const handleVoiceCloned = (voiceId: string) => {
    setClonedVoiceId(voiceId);
    setStep('call');
    toast.success('Voice ready! Now you can start the prank.');
  };

  const handleStartCall = async (voiceId: string) => {
    try {
      // Request microphone permission first
      try {
        await navigator.mediaDevices.getUserMedia({ audio: true });
      } catch (micError) {
        console.error('Microphone permission error:', micError);
        toast.error('Please allow microphone access to use this app.');
        setCallStatus('Error: Microphone access denied');
        return;
      }

      // Start the AI assistant with inline configuration
      if (vapiRef.current) {
        console.log('Starting Vapi with voice:', voiceId);
        
        try {
          // Use inline assistant configuration with ElevenLabs voice
          const result = await vapiRef.current.start({
            transcriber: {
              provider: 'deepgram',
              model: 'nova-2',
              language: 'en-US',
            },
            model: {
              provider: 'openai',
              model: 'gpt-4',
              messages: [{
                role: 'system',
                content: `You are having a casual conversation with dad. Be natural, friendly, and conversational. Keep responses brief and casual, like you're actually the person calling. Don't be overly formal or robotic. If dad asks about anything specific, respond naturally as if you're really their child.`
              }]
            },
            voice: {
              provider: '11labs',
              voiceId: voiceId,
              model: 'eleven_monolingual_v1',
              stability: 0.5,
              similarityBoost: 0.5,
            },
            name: 'Prank Assistant',
          });
          
          console.log('Call started successfully:', result);
          setShowInstructions(false);
        } catch (startError: unknown) {
          console.error('Full error object:', startError);
          
          // Check if it's an authentication error
          const errorStr = JSON.stringify(startError);
          if (errorStr.includes('401') || errorStr.includes('403') || errorStr.includes('auth')) {
            setCallStatus('Error: Authentication failed - check API key');
            toast.error('Authentication failed. Please verify your Vapi API key.');
          } else {
            setCallStatus('Error: Failed to start call');
            toast.error('Failed to start the AI call. Check console for details.');
          }
        }
      } else {
        toast.error('Vapi not initialized. Please refresh the page.');
        setCallStatus('Error: Vapi not initialized');
      }
    } catch (error) {
      console.error('Error starting call:', error);
      setCallStatus('Failed to start');
    }
  };

  const handleEndCall = () => {
    if (vapiRef.current) {
      vapiRef.current.stop();
    }
    setTranscript([]);
    setPartialTranscript(null);
  };

  const toggleMute = () => {
    if (vapiRef.current) {
      const newMutedState = !isMuted;
      
      // Mute/unmute the microphone
      vapiRef.current.setMuted(newMutedState);
      
      // Send a system message to control the AI behavior
      if (newMutedState) {
        // When muting, tell the AI to pause
        vapiRef.current.send({
          type: 'add-message',
          message: {
            role: 'system',
            content: 'PAUSE. The user has muted you. Do not speak or respond until unmuted. Stay completely silent.'
          }
        });
        toast.info('AI Assistant muted');
      } else {
        // When unmuting, tell the AI it can continue
        vapiRef.current.send({
          type: 'add-message',
          message: {
            role: 'system',
            content: 'You are now unmuted. You may continue the conversation normally.'
          }
        });
        toast.success('AI Assistant unmuted');
      }
      
      setIsMuted(newMutedState);
    }
  };

  const CallInterface = ({ voiceId, voiceType }: { voiceId: string, voiceType: string }) => (
    <>
      {/* Instructions */}
      {showInstructions && (
        <Card className="mb-8 bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
          <CardHeader className="pb-4">
            <CardTitle className="text-xl flex items-center gap-2 text-white">
              <AlertCircle className="h-5 w-5 text-yellow-500" />
              Quick Setup Guide
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              <div className="flex items-start gap-3">
                <span className="flex items-center justify-center w-8 h-8 rounded-full bg-purple-500/20 text-purple-300 text-sm font-semibold flex-shrink-0">1</span>
                <p className="text-gray-300">Call dad from YOUR phone and put it on speaker 📱</p>
              </div>
              <div className="flex items-start gap-3">
                <span className="flex items-center justify-center w-8 h-8 rounded-full bg-purple-500/20 text-purple-300 text-sm font-semibold flex-shrink-0">2</span>
                <p className="text-gray-300">Put your computer on speaker too 🔊</p>
              </div>
              <div className="flex items-start gap-3">
                <span className="flex items-center justify-center w-8 h-8 rounded-full bg-purple-500/20 text-purple-300 text-sm font-semibold flex-shrink-0">3</span>
                <p className="text-gray-300">Click &quot;Start AI Assistant&quot; to begin</p>
              </div>
              <div className="flex items-start gap-3">
                <span className="flex items-center justify-center w-8 h-8 rounded-full bg-purple-500/20 text-purple-300 text-sm font-semibold flex-shrink-0">4</span>
                <p className="text-gray-300">Click mute to silence the AI (dad will only hear you)</p>
              </div>
              <Alert className="bg-yellow-500/10 border-yellow-500/30 mt-4">
                <AlertCircle className="h-4 w-4 text-yellow-500" />
                <AlertDescription className="text-yellow-200">
                  Make sure both devices are on speaker for the prank to work!
                </AlertDescription>
              </Alert>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Main Controls */}
      <Card className="mb-8 bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
        <CardContent className="pt-8 pb-8">
          <div className="flex flex-col gap-6">
            {/* Voice Ready Badge */}
            <div className="flex justify-center">
              <Badge className="bg-green-500/20 text-green-300 border-green-500/30 px-4 py-2">
                <CheckCircle2 className="h-4 w-4 mr-2" />
                {voiceType === 'clone' ? 'Voice Cloned Successfully' : 'Using Pre-defined Voice'}
              </Badge>
            </div>

            {/* Control Buttons */}
            <div className="flex gap-4 justify-center">
              {!isCallActive ? (
                <Button 
                  onClick={() => handleStartCall(voiceId)} 
                  size="lg" 
                  className="min-w-[240px] h-14 text-lg bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700 shadow-lg"
                >
                  <Phone className="mr-2 h-6 w-6" />
                  Start AI Assistant
                </Button>
              ) : (
                <>
                  <Button 
                    onClick={handleEndCall} 
                    size="lg" 
                    variant="destructive"
                    className="min-w-[160px] h-14 text-lg shadow-lg"
                  >
                    <PhoneOff className="mr-2 h-6 w-6" />
                    End Call
                  </Button>
                  <Button 
                    onClick={toggleMute}
                    size="lg"
                    variant={isMuted ? "outline" : "secondary"}
                    className={`min-w-[160px] h-14 text-lg shadow-lg ${
                      isMuted 
                        ? 'bg-yellow-500/10 hover:bg-yellow-500/20 border-yellow-500/50 text-yellow-300' 
                        : 'bg-gray-700 hover:bg-gray-600 text-white'
                    }`}
                  >
                    {isMuted ? (
                      <>
                        <MicOff className="mr-2 h-6 w-6" />
                        AI Muted
                      </>
                    ) : (
                      <>
                        <Mic className="mr-2 h-6 w-6" />
                        AI Active
                      </>
                    )}
                  </Button>
                </>
              )}
            </div>

            {/* Status Display */}
            {callStatus && (
              <div className="flex items-center justify-center gap-3 text-sm">
                <Badge 
                  variant="outline"
                  className={`px-4 py-2 flex items-center gap-2 ${
                    callStatus.includes('Error') 
                      ? 'bg-red-500/10 text-red-300 border-red-500/30' 
                      : callStatus.includes('Connected') 
                        ? 'bg-green-500/10 text-green-300 border-green-500/30'
                        : 'bg-gray-700/50 text-gray-300 border-gray-600'
                  }`}
                >
                  {callStatus.includes('Speaking') && <Volume2 className="h-4 w-4 animate-pulse" />}
                  {callStatus.includes('Connected') && <CheckCircle2 className="h-4 w-4" />}
                  {callStatus.includes('Error') && <AlertCircle className="h-4 w-4" />}
                  {callStatus}
                </Badge>
                {isCallActive && isMuted && (
                  <Badge variant="outline" className="px-4 py-2 animate-pulse bg-yellow-500/10 text-yellow-300 border-yellow-500/30">
                    ⚠️ AI is silent - Dad only hears you
                  </Badge>
                )}
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </>
  );

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
            AI-powered voice assistant using your cloned voice
          </p>
          <div className="flex items-center justify-center gap-2 mt-3">
            <Badge variant="outline" className="bg-purple-500/10 text-purple-300 border-purple-500/30">
              <Sparkles className="h-3 w-3 mr-1" />
              AI Voice Demo
            </Badge>
            <Badge variant="outline" className="bg-cyan-500/10 text-cyan-300 border-cyan-500/30">
              Father&apos;s Day Special
            </Badge>
          </div>
        </div>

        {/* Tabs for Clone vs Pre-defined Voice */}
        <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as 'clone' | 'predefined')} className="w-full">
          <TabsList className="grid w-full grid-cols-2 mb-8 bg-gray-800/50 border-gray-700">
            <TabsTrigger value="clone" className="data-[state=active]:bg-purple-600 data-[state=active]:text-white">
              <Mic className="mr-2 h-4 w-4" />
              Clone Your Voice
            </TabsTrigger>
            <TabsTrigger value="predefined" className="data-[state=active]:bg-purple-600 data-[state=active]:text-white">
              <Zap className="mr-2 h-4 w-4" />
              Use Demo Voice
            </TabsTrigger>
          </TabsList>

          {/* Clone Voice Tab */}
          <TabsContent value="clone">
            {step === 'voice' && !clonedVoiceId ? (
              <VoiceRecorder onVoiceCloned={handleVoiceCloned} />
            ) : (
              <CallInterface voiceId={clonedVoiceId!} voiceType="clone" />
            )}
          </TabsContent>

          {/* Pre-defined Voice Tab */}
          <TabsContent value="predefined">
            {PREDEFINED_VOICE_ID ? (
              <CallInterface voiceId={PREDEFINED_VOICE_ID} voiceType="predefined" />
            ) : (
              <Alert className="bg-yellow-500/10 border-yellow-500/30">
                <AlertCircle className="h-4 w-4 text-yellow-500" />
                <AlertDescription className="text-yellow-200">
                  No pre-defined voice ID configured. Add NEXT_PUBLIC_PREDEFINED_VOICE_ID to your environment variables.
                </AlertDescription>
              </Alert>
            )}
          </TabsContent>
        </Tabs>

        {/* Transcript */}
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
                        {entry.role === 'assistant' ? 'AI' : 'Dad'}
                      </Badge>
                      <p className="text-gray-200 flex-1 leading-relaxed">{entry.text}</p>
                    </div>
                  ))}
                  
                  {/* Show partial transcript with loading indicator */}
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
                        {partialTranscript.role === 'assistant' ? 'AI' : 'Dad'}
                      </Badge>
                      <p className="text-gray-400 flex-1 italic leading-relaxed">{partialTranscript.text}...</p>
                    </div>
                  )}
                </div>
              </ScrollArea>
            </CardContent>
          </Card>
        )}

        {/* Tips */}
        {isCallActive && (
          <Alert className="mt-8 bg-purple-500/10 border-purple-500/30">
            <Sparkles className="h-4 w-4 text-purple-400" />
            <AlertDescription className="text-purple-200">
              <strong>Pro tip:</strong> When you mute the AI, it stops speaking completely. Dad will only hear you talking from your phone! Perfect timing is key for the prank.
            </AlertDescription>
          </Alert>
        )}
      </div>
    </div>
  );
}
