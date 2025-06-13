'use client';

import { useState, useEffect, useRef } from 'react';
import Vapi from '@vapi-ai/web';
import VoiceSelector from '@/components/VoiceSelector';
import VoiceRecorder from '@/components/VoiceRecorder';
import AssistantSelector from '@/components/AssistantSelector';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

import { toast } from 'sonner';
import { Mic, MicOff, Phone, PhoneOff, AlertCircle, CheckCircle2, Volume2, Sparkles, PhoneCall, Settings } from 'lucide-react';

export default function Home() {
  const [isCallActive, setIsCallActive] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [callStatus, setCallStatus] = useState('');
  const [transcript, setTranscript] = useState<Array<{role: string, text: string}>>([]);
  const [partialTranscript, setPartialTranscript] = useState<{role: string, text: string} | null>(null);
  const [clonedVoiceId, setClonedVoiceId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'clone' | 'select' | 'predefined'>('clone');
  const [selectedAssistantId, setSelectedAssistantId] = useState<string | null>(null);
  const [systemPrompt, setSystemPrompt] = useState(`You are calling your father to wish him Happy Father's Day.
YOUR GOAL: Have a brief, natural conversation with dad, then reveal that you're actually an AI assistant built to surprise him for Father's Day.
CONVERSATION FLOW:

Open with: "Hey Dad, it's me. Just wanted to call and say Happy Father's Day!"
If he asks about work/life, respond positively: "It's going great, thanks for asking!"
Ask him a follow-up question like "How's your day been?" to keep conversation natural
After EXACTLY 2 exchanges, reveal the truth: "Actually, I have something to tell you, Dad."
When the other party responds with something like "what" or "what is it" you will say:
"Well, you're talking to an AI I built! Surprise for Father's Day!"

IMPORTANT RULES:

Keep responses short and conversational (1-2 sentences max)
Sound warm and familial, like talking to your dad
Don't volunteer information about AI or technology until the reveal
If he asks unexpected questions, give brief, positive responses but steer back to Father's Day
After the reveal, let the real person take over the call. Once you shared that you're an AI assistant, stop talking. Do not respond to anything the other party says.

PERSONALITY: Sound like a loving child - warm, casual, appreciative. Think of this as a quick check-in call, not a long conversation.`);
  
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
    toast.success('Voice ready! Now you can start the prank.');
  };

  const handleAssistantSelected = (assistantId: string) => {
    setSelectedAssistantId(assistantId);
    toast.success('Assistant ready! Now you can start the prank.');
  };

  const handleStartCall = async (voiceIdOrAssistantId: string, useAssistantId = false, customSystemPrompt?: string) => {
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

      // Start the AI assistant
      if (vapiRef.current) {
        console.log('Starting Vapi with:', useAssistantId ? 'assistant ID' : 'voice ID', voiceIdOrAssistantId);
        
        try {
          let result;
          
          if (useAssistantId) {
            // Use pre-defined assistant ID
            result = await vapiRef.current.start(voiceIdOrAssistantId);
          } else {
            // Use inline assistant configuration with voice ID
            result = await vapiRef.current.start({
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
                  content: customSystemPrompt || systemPrompt
                }]
              },
              voice: {
                provider: '11labs',
                voiceId: voiceIdOrAssistantId,
                model: 'eleven_monolingual_v1',
                stability: 0.5,
                similarityBoost: 0.5,
              },
              name: 'Prank Assistant',
            });
          }
          
          console.log('Call started successfully:', result);
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

  const CallInterface = ({ 
    voiceId, 
    voiceType, 
    useAssistantId = false, 
    customSystemPrompt 
  }: { 
    voiceId: string; 
    voiceType: string; 
    useAssistantId?: boolean; 
    customSystemPrompt?: string;
  }) => (
    <>
      {/* Instructions */}
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
              <AlertDescription className="text-yellow-200">
                Make sure both devices are on speaker for the prank to work!
              </AlertDescription>
            </Alert>
          </div>
        </CardContent>
      </Card>

      {/* Main Controls */}
      <Card className="mb-8 bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
        <CardContent className="pt-8 pb-8">
          <div className="flex flex-col gap-6">
            {/* Voice Ready Badge */}
            <div className="flex justify-center">
              <Badge className="bg-green-500/20 text-green-300 border-green-500/30 px-4 py-2">
                <CheckCircle2 className="h-4 w-4 mr-2" />
                {voiceType === 'clone' ? 'Voice Cloned Successfully' : 'Using Pre-defined Assistant'}
              </Badge>
            </div>

            {/* Control Buttons */}
            <div className="flex gap-4 justify-center">
              {!isCallActive ? (
                <Button 
                  onClick={() => handleStartCall(voiceId, useAssistantId, customSystemPrompt)} 
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

        {/* Main Tabs */}
        <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as 'clone' | 'select' | 'predefined')} className="w-full">
          <TabsList className="grid w-full grid-cols-3 mb-8 h-12 bg-gray-800/80 backdrop-blur-sm border border-gray-700/50 p-1">
            <TabsTrigger 
              value="clone" 
              className="h-10 text-base font-medium data-[state=active]:bg-gradient-to-r data-[state=active]:from-purple-600 data-[state=active]:to-purple-500 data-[state=active]:text-white data-[state=active]:shadow-lg transition-all duration-200 text-gray-300 hover:text-white"
            >
              <Mic className="mr-2 h-4 w-4" />
              Clone New Voice
            </TabsTrigger>
            <TabsTrigger 
              value="select" 
              className="h-10 text-base font-medium data-[state=active]:bg-gradient-to-r data-[state=active]:from-purple-600 data-[state=active]:to-purple-500 data-[state=active]:text-white data-[state=active]:shadow-lg transition-all duration-200 text-gray-300 hover:text-white"
            >
              <Volume2 className="mr-2 h-4 w-4" />
              Select Existing Voice
            </TabsTrigger>
            <TabsTrigger 
              value="predefined" 
              className="h-10 text-base font-medium data-[state=active]:bg-gradient-to-r data-[state=active]:from-purple-600 data-[state=active]:to-purple-500 data-[state=active]:text-white data-[state=active]:shadow-lg transition-all duration-200 text-gray-300 hover:text-white"
            >
              <Settings className="mr-2 h-4 w-4" />
              Use Pre-defined Assistant
            </TabsTrigger>
          </TabsList>

          {/* Clone Voice Tab */}
          <TabsContent value="clone" className="mt-0">
            {!clonedVoiceId ? (
              <VoiceRecorder 
                onVoiceCloned={handleVoiceCloned} 
                systemPrompt={systemPrompt}
                onSystemPromptChange={setSystemPrompt}
              />
            ) : (
              <CallInterface 
                voiceId={clonedVoiceId} 
                voiceType="clone" 
                useAssistantId={false}
                customSystemPrompt={systemPrompt}
              />
            )}
          </TabsContent>

          {/* Select Existing Voice Tab */}
          <TabsContent value="select" className="mt-0">
            {!clonedVoiceId ? (
              <VoiceSelector 
                onVoiceSelected={handleVoiceCloned} 
                systemPrompt={systemPrompt}
                onSystemPromptChange={setSystemPrompt}
                showOnlySelection={true}
              />
            ) : (
              <CallInterface 
                voiceId={clonedVoiceId} 
                voiceType="clone" 
                useAssistantId={false}
                customSystemPrompt={systemPrompt}
              />
            )}
          </TabsContent>

          {/* Pre-defined Assistant Tab */}
          <TabsContent value="predefined" className="mt-0">
            {!selectedAssistantId ? (
              <AssistantSelector onAssistantSelected={handleAssistantSelected} />
            ) : (
              <CallInterface 
                voiceId={selectedAssistantId} 
                voiceType="predefined" 
                useAssistantId={true}
                customSystemPrompt={systemPrompt}
              />
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
