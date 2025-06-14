'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Mic, Upload, Square, RefreshCw, CheckCircle2, Play, Trophy, Star, Trash2 } from 'lucide-react';
import { toast } from 'sonner';

interface VoiceRecorderProps {
  onVoiceCloned: (voiceId: string) => void;
  hideSystemPrompt?: boolean;
  voiceTrainingTexts: string[];
  systemPrompt?: string;
  onSystemPromptChange?: (prompt: string) => void;
}

interface RecordingData {
  id: string;
  blob: Blob;
  text: string;
  recognizedText: string;
  timestamp: number;
  url: string;
}

// Define custom Speech Recognition types to avoid conflicts
interface CustomSpeechRecognitionEvent extends Event {
  resultIndex: number;
  results: SpeechRecognitionResultList;
}

interface CustomSpeechRecognitionErrorEvent extends Event {
  error: string;
  message: string;
}

interface CustomSpeechRecognition extends EventTarget {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start(): void;
  stop(): void;
  onresult: ((event: CustomSpeechRecognitionEvent) => void) | null;
  onerror: ((event: CustomSpeechRecognitionErrorEvent) => void) | null;
}

export default function VoiceRecorder({ onVoiceCloned, hideSystemPrompt = false, voiceTrainingTexts, systemPrompt = '', onSystemPromptChange }: VoiceRecorderProps) {
  const [isRecording, setIsRecording] = useState(false);
  const [recordings, setRecordings] = useState<RecordingData[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const [currentText, setCurrentText] = useState('');
  const [recognizedText, setRecognizedText] = useState('');
  const [highlightedWords, setHighlightedWords] = useState<number[]>([]);
  const [usedTextIndices, setUsedTextIndices] = useState<Set<number>>(new Set());
  const [allTextsCompleted, setAllTextsCompleted] = useState(false);
  const [showSuccessAnimation, setShowSuccessAnimation] = useState(false);
  
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recognitionRef = useRef<CustomSpeechRecognition | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);

  const generateNewText = useCallback(() => {
    if (usedTextIndices.size >= voiceTrainingTexts.length) {
      setAllTextsCompleted(true);
      setCurrentText('🎉 All training texts completed! You can now clone your voice.');
      return;
    }

    let randomIndex: number;
    do {
      randomIndex = Math.floor(Math.random() * voiceTrainingTexts.length);
    } while (usedTextIndices.has(randomIndex));

    setUsedTextIndices(prev => new Set([...prev, randomIndex]));
    setCurrentText(voiceTrainingTexts[randomIndex]);
    setRecognizedText('');
    setHighlightedWords([]);
  }, [usedTextIndices, voiceTrainingTexts]);

  // Initialize with first random text
  useEffect(() => {
    if (voiceTrainingTexts.length > 0 && !currentText) {
      generateNewText();
    }
  }, [voiceTrainingTexts, currentText, generateNewText]);

  const updateHighlighting = useCallback((transcript: string) => {
    if (!currentText || allTextsCompleted) return;
    
    const currentWords = currentText.toLowerCase().split(/\s+/);
    const spokenWords = transcript.toLowerCase().split(/\s+/);
    
    // Find matching words
    const matched = [];
    let spokenIndex = 0;
    
    for (let i = 0; i < currentWords.length && spokenIndex < spokenWords.length; i++) {
      const currentWord = currentWords[i].replace(/[^\w]/g, ''); // Remove punctuation
      let foundMatch = false;
      
      // Look ahead in spoken words to find this current word
      for (let j = spokenIndex; j < Math.min(spokenIndex + 3, spokenWords.length); j++) {
        const spokenWord = spokenWords[j].replace(/[^\w]/g, '');
        
        if (currentWord.length > 2 && spokenWord.length > 2) {
          // Use similarity for longer words
          const similarity = Math.max(
            currentWord.includes(spokenWord) ? spokenWord.length / currentWord.length : 0,
            spokenWord.includes(currentWord) ? currentWord.length / spokenWord.length : 0
          );
          
          if (similarity > 0.6) {
            matched.push(i);
            spokenIndex = j + 1;
            foundMatch = true;
            break;
          }
        } else if (currentWord === spokenWord) {
          matched.push(i);
          spokenIndex = j + 1;
          foundMatch = true;
          break;
        }
      }
      
      if (!foundMatch) break; // Stop highlighting if we can't find a match
    }
    
    setHighlightedWords(matched);
  }, [currentText, allTextsCompleted]);

  const initializeSpeechRecognition = useCallback(() => {
    if (typeof window === 'undefined') return null;

    // Check if Speech Recognition is available
    const windowWithSpeech = window as Window & {
      SpeechRecognition?: new () => CustomSpeechRecognition;
      webkitSpeechRecognition?: new () => CustomSpeechRecognition;
    };

    const SpeechRecognitionConstructor = windowWithSpeech.SpeechRecognition || windowWithSpeech.webkitSpeechRecognition;

    if (!SpeechRecognitionConstructor) {
      console.warn('Speech Recognition not supported in this browser');
      return null;
    }

    const recognition = new SpeechRecognitionConstructor();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = 'en-US';

    recognition.onresult = (event: CustomSpeechRecognitionEvent) => {
      let finalTranscript = '';
      let interimTranscript = '';
      
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          finalTranscript += transcript;
        } else {
          interimTranscript += transcript;
        }
      }
      
      if (finalTranscript) {
        setRecognizedText(prev => prev + finalTranscript);
        updateHighlighting(finalTranscript);
      }
      
      if (interimTranscript) {
        updateHighlighting(interimTranscript);
      }
    };

    recognition.onerror = (event: CustomSpeechRecognitionErrorEvent) => {
      console.error('Speech recognition error:', event.error);
      if (event.error === 'not-allowed') {
        toast.error('Microphone access denied. Please allow microphone access.');
      }
    };

    return recognition;
  }, [updateHighlighting]);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      
      // Reset recognition text
      setRecognizedText('');
      
      // Initialize speech recognition
      const recognition = initializeSpeechRecognition();
      if (recognition) {
        recognitionRef.current = recognition;
        recognition.start();
      }

      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];

      mediaRecorderRef.current.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorderRef.current.onstop = () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
        
        // Create recording data
        const recording: RecordingData = {
          id: Date.now().toString(),
          blob: audioBlob,
          text: currentText,
          recognizedText: recognizedText,
          timestamp: Date.now(),
          url: URL.createObjectURL(audioBlob)
        };
        
        setRecordings(prev => [...prev, recording]);
        stream.getTracks().forEach(track => track.stop());
        
        // Show success animation
        setShowSuccessAnimation(true);
        toast.success('Recording saved! Great job reading that text.');
        
        setTimeout(() => {
          setShowSuccessAnimation(false);
          if (!allTextsCompleted) {
            generateNewText();
          }
        }, 2000);
      };

      mediaRecorderRef.current.start();
      setIsRecording(true);
      toast.success('Recording started! Begin reading the text.');
    } catch (error) {
      console.error('Error starting recording:', error);
      toast.error('Failed to start recording. Please check microphone permissions.');
    }
  };

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      setIsRecording(false);
    }
    
    if (recognitionRef.current) {
      recognitionRef.current.stop();
      recognitionRef.current = null;
    }
  }, [isRecording]);

  const deleteRecording = (id: string) => {
    setRecordings(prev => {
      const recording = prev.find(r => r.id === id);
      if (recording) {
        URL.revokeObjectURL(recording.url);
      }
      return prev.filter(r => r.id !== id);
    });
    toast.success('Recording deleted');
  };

  const uploadVoice = async () => {
    if (recordings.length === 0) {
      toast.error('No recordings to upload');
      return;
    }

    setIsUploading(true);
    
    try {
      const formData = new FormData();
      
      // Add all recordings
      recordings.forEach((recording, index) => {
        const fileExtension = recording.blob.type.includes('mp4') ? 'mp4' : 
                             recording.blob.type.includes('webm') ? 'webm' : 'wav';
        formData.append('files', recording.blob, `voice-sample-${index + 1}.${fileExtension}`);
      });
      
      formData.append('name', `Voice Clone ${Date.now()}`);
      formData.append('description', `Voice cloned from ${recordings.length} samples for prank call`);

      console.log('Uploading voice with:', {
        recordingCount: recordings.length,
        totalSize: recordings.reduce((sum, r) => sum + r.blob.size, 0)
      });

      const response = await fetch('/api/clone-voice', {
        method: 'POST',
        body: formData,
      });

      const data = await response.json();
      console.log('Voice cloning response:', data);

      if (!response.ok) {
        throw new Error(data.error || 'Failed to clone voice');
      }

      toast.success(`Voice cloned successfully from ${recordings.length} recordings!`);
      onVoiceCloned(data.voiceId);
    } catch (error) {
      console.error('Error cloning voice:', error);
      toast.error(error instanceof Error ? error.message : 'Failed to clone voice');
    } finally {
      setIsUploading(false);
    }
  };

  const renderTextWithHighlighting = () => {
    if (allTextsCompleted) {
      return (
        <div className="flex items-center justify-center p-8 text-center">
          <div className="space-y-4">
            <div className="flex justify-center space-x-2">
              <Trophy className="h-16 w-16 text-yellow-500 animate-bounce" />
              <Star className="h-12 w-12 text-yellow-400 animate-pulse" />
            </div>
            <p className="text-lg font-medium text-green-700">
              🎉 Outstanding! You&apos;ve completed all voice training examples.
            </p>
            <p className="text-sm text-gray-600">
              Your voice samples are ready for cloning!
            </p>
          </div>
        </div>
      );
    }

    const words = currentText.split(/(\s+)/);
    return (
      <div className="relative">
        <p className="text-lg leading-relaxed text-gray-200">
          {words.map((word, index) => {
            const wordIndex = Math.floor(index / 2); // Account for spaces
            const isHighlighted = highlightedWords.includes(wordIndex);
            
            if (word.trim() === '') return <span key={index}>{word}</span>; // Return spaces as-is
            
            return (
              <span
                key={index}
                className={`transition-all duration-300 ${
                  isHighlighted 
                    ? 'bg-green-400/20 text-green-200 font-medium border-b-2 border-green-400' 
                    : ''
                }`}
              >
                {word}
              </span>
            );
          })}
        </p>
        
        {showSuccessAnimation && (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="bg-gray-900/95 backdrop-blur-sm border border-green-500/50 rounded-xl p-6 text-center shadow-2xl">
              <CheckCircle2 className="h-12 w-12 text-green-400 mx-auto mb-3 animate-bounce" />
              <p className="text-green-300 font-medium text-lg">Excellent! Recording saved.</p>
              <p className="text-gray-400 text-sm mt-1">Moving to next text...</p>
            </div>
          </div>
        )}
      </div>
    );
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
      if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
        mediaRecorderRef.current.stop();
      }
      // Clean up URLs
      recordings.forEach(recording => URL.revokeObjectURL(recording.url));
    };
  }, [recordings]);

  return (
    <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <Mic className="h-5 w-5 text-purple-400" />
          Voice Training
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div>
          <Label className="text-base font-medium text-white">Voice Training</Label>
          <p className="text-sm text-gray-400 mt-1">
            Read each text clearly to train your voice clone. Words will highlight in real-time as you speak them. For best results, aim for at least 30 seconds of total audio - the more you record, the better your voice clone will sound.
          </p>
        </div>

        <div className="border rounded-lg p-6 bg-gray-900/30 border-gray-600">
          <div className="flex justify-between items-center mb-4">
            <h3 className="font-medium text-white">
              {allTextsCompleted ? 'Training Complete!' : 'Read this text aloud:'}
            </h3>
            {!allTextsCompleted && (
              <Button
                variant="outline"
                size="sm"
                onClick={generateNewText}
                disabled={isRecording}
                className="bg-gray-700/50 border-gray-600 text-gray-300 hover:bg-gray-600 hover:text-white"
              >
                <RefreshCw className="h-4 w-4 mr-2" />
                New Text
              </Button>
            )}
          </div>
          
          <div className="text-gray-200">
            {renderTextWithHighlighting()}
          </div>
          
          {!allTextsCompleted && (
            <div className="mt-4 text-sm text-gray-400">
              <p><strong>Progress:</strong> {usedTextIndices.size} of {voiceTrainingTexts.length} examples completed</p>
              <p><strong>Recordings:</strong> {recordings.length} samples collected</p>
            </div>
          )}
        </div>

        <div className="flex gap-3">
          {!isRecording ? (
            <Button 
              onClick={startRecording} 
              className="flex-1 bg-red-600 hover:bg-red-700"
            >
              <Mic className="h-4 w-4 mr-2" />
              Start Recording
            </Button>
          ) : (
            <Button 
              onClick={stopRecording} 
              variant="destructive" 
              className="flex-1"
            >
              <Square className="h-4 w-4 mr-2" />
              Stop Recording
            </Button>
          )}
        </div>

        {!allTextsCompleted && (
          <div className="border rounded-lg p-4 bg-blue-900/20 border-blue-700">
            <Label className="text-sm font-medium text-blue-300">What we heard:</Label>
            <p className="text-sm text-blue-200 mt-1 min-h-[1.25rem]">
              {recognizedText || (isRecording ? "Listening..." : "Start recording to see speech recognition here")}
            </p>
          </div>
        )}

        {recordings.length > 0 && (
          <div className="space-y-4">
            <Accordion type="single" collapsible className="w-full">
              <AccordionItem value="recordings" className="border-gray-600">
                <AccordionTrigger className="text-white hover:text-purple-300">
                  <div className="flex items-center gap-2">
                    <Play className="h-4 w-4" />
                    Audio Previews
                  </div>
                </AccordionTrigger>
                <AccordionContent className="space-y-3 pt-4">
                  {recordings.map((recording, index) => (
                    <div key={recording.id} className="border rounded-lg p-3 bg-gray-800/30 border-gray-600">
                      <div className="flex justify-between items-start mb-3">
                        <div className="flex-1">
                          <Label className="text-white text-sm font-medium">
                            Recording #{index + 1}
                          </Label>
                          <p className="text-xs text-gray-400 mt-1">
                            {new Date(recording.timestamp).toLocaleTimeString()}
                          </p>
                        </div>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => deleteRecording(recording.id)}
                          className="text-red-400 hover:text-red-300 hover:bg-red-900/20"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                      
                      {/* Show the text that was being read */}
                      <div className="mb-3 p-2 bg-gray-900/50 rounded border border-gray-700">
                        <Label className="text-xs text-gray-400 font-medium">Text read:</Label>
                        <p className="text-xs text-gray-300 mt-1 leading-relaxed">
                          {recording.text.length > 100 
                            ? `${recording.text.substring(0, 100)}...` 
                            : recording.text
                          }
                        </p>
                      </div>
                      
                      <audio 
                        controls 
                        src={recording.url}
                        className="w-full"
                      />
                    </div>
                  ))}
                </AccordionContent>
              </AccordionItem>
            </Accordion>
            
            <Button 
              onClick={uploadVoice} 
              disabled={isUploading}
              className="w-full bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700"
            >
              <Upload className="h-4 w-4 mr-2" />
              {isUploading ? `Cloning Voice from ${recordings.length} Samples...` : `Clone Voice (${recordings.length} samples)`}
            </Button>
          </div>
        )}

        {!hideSystemPrompt && onSystemPromptChange && (
          <div className="space-y-2">
            <Label htmlFor="systemPrompt" className="text-white">System Prompt (Optional)</Label>
            <Textarea
              id="systemPrompt"
              placeholder="Enter custom system prompt for your assistant..."
              value={systemPrompt}
              onChange={(e) => onSystemPromptChange(e.target.value)}
              rows={4}
              className="bg-gray-900/50 border-gray-600 text-white placeholder:text-gray-400 focus:border-purple-500"
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
} 