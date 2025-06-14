'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Mic, Upload, Square, RefreshCw, CheckCircle2 } from 'lucide-react';
import { toast } from 'sonner';

interface VoiceRecorderProps {
  onVoiceCloned: (voiceId: string) => void;
  hideSystemPrompt?: boolean;
  voiceTrainingTexts: string[];
  systemPrompt?: string;
  onSystemPromptChange?: (prompt: string) => void;
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
  const [audioBlob, setAudioBlob] = useState<Blob | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [currentText, setCurrentText] = useState('');
  const [recognizedText, setRecognizedText] = useState('');
  const [highlightedWords, setHighlightedWords] = useState<number[]>([]);
  const [usedTextIndices, setUsedTextIndices] = useState<Set<number>>(new Set());
  const [allTextsCompleted, setAllTextsCompleted] = useState(false);
  
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recognitionRef = useRef<CustomSpeechRecognition | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);

  const generateNewText = useCallback(() => {
    if (usedTextIndices.size >= voiceTrainingTexts.length) {
      setAllTextsCompleted(true);
      setCurrentText('🎉 Congratulations! You\'ve completed all voice training examples. Your voice samples are ready for cloning!');
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
    
    // Find the furthest sequential match
    let matchedCount = 0;
    for (let i = 0; i < Math.min(currentWords.length, spokenWords.length); i++) {
      // Check if current word or any recent spoken word matches
      const currentWord = currentWords[i];
      const recentSpokenWords = spokenWords.slice(Math.max(0, spokenWords.length - 5)); // Check last 5 words
      
      if (recentSpokenWords.some(spokenWord => 
        spokenWord.includes(currentWord) || currentWord.includes(spokenWord)
      )) {
        matchedCount = i + 1;
      } else {
        break; // Stop at first non-sequential match
      }
    }
    
    setHighlightedWords(Array.from({ length: matchedCount }, (_, i) => i));
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
      for (let i = event.resultIndex; i < event.results.length; i++) {
        if (event.results[i].isFinal) {
          finalTranscript += event.results[i][0].transcript;
        }
      }
      
      if (finalTranscript) {
        setRecognizedText(finalTranscript);
        updateHighlighting(finalTranscript);
      }
    };

    recognition.onerror = (event: CustomSpeechRecognitionErrorEvent) => {
      console.error('Speech recognition error:', event.error);
    };

    return recognition;
  }, [updateHighlighting]);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      
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
        setAudioBlob(audioBlob);
        stream.getTracks().forEach(track => track.stop());
      };

      mediaRecorderRef.current.start();
      setIsRecording(true);
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

  const resetRecording = useCallback(() => {
    setAudioBlob(null);
    setRecognizedText('');
    setHighlightedWords([]);
    audioChunksRef.current = [];
  }, []);

  const uploadVoice = async () => {
    if (!audioBlob) {
      toast.error('No audio recorded');
      return;
    }

    setIsUploading(true);
    
    try {
      const formData = new FormData();
      
      // Determine file extension based on blob type
      const fileExtension = audioBlob.type.includes('mp4') ? 'mp4' : 
                           audioBlob.type.includes('webm') ? 'webm' : 'wav';
      
      formData.append('files', audioBlob, `voice-sample.${fileExtension}`);
      formData.append('name', `Voice Clone ${Date.now()}`);
      formData.append('description', 'Voice cloned for prank call');

      console.log('Uploading voice with:', {
        blobType: audioBlob.type,
        blobSize: audioBlob.size,
        fileExtension
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

      toast.success('Voice cloned successfully!');
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
            <CheckCircle2 className="h-16 w-16 text-green-500 mx-auto" />
            <p className="text-lg font-medium text-green-700">
              🎉 Congratulations! You&apos;ve completed all voice training examples.
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
      <p className="text-lg leading-relaxed">
        {words.map((word, index) => {
          const wordIndex = Math.floor(index / 2); // Account for spaces
          const isHighlighted = highlightedWords.includes(wordIndex);
          
          if (word.trim() === '') return <span key={index}>{word}</span>; // Return spaces as-is
          
          return (
            <span
              key={index}
              className={`transition-colors duration-200 ${
                isHighlighted ? 'bg-green-200 text-green-800' : ''
              }`}
            >
              {word}
            </span>
          );
        })}
      </p>
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
    };
  }, []);

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
            Read the text below clearly to train your voice clone. Words will highlight as you speak them.
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
            </div>
          )}
        </div>

        {recognizedText && !allTextsCompleted && (
          <div className="border rounded-lg p-4 bg-blue-900/20 border-blue-700">
            <Label className="text-sm font-medium text-blue-300">What we heard:</Label>
            <p className="text-sm text-blue-200 mt-1">{recognizedText}</p>
          </div>
        )}

        <div className="flex gap-3">
          {!isRecording ? (
            <Button 
              onClick={startRecording} 
              className="flex-1 bg-red-600 hover:bg-red-700"
              disabled={allTextsCompleted}
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
          
          {audioBlob && (
            <Button 
              onClick={resetRecording} 
              variant="outline"
              className="bg-gray-700/50 border-gray-600 text-gray-300 hover:bg-gray-600 hover:text-white"
            >
              Reset
            </Button>
          )}
        </div>

        {audioBlob && (
          <div className="space-y-4">
            <div>
              <Label className="text-white">Audio Preview</Label>
              <audio 
                controls 
                src={URL.createObjectURL(audioBlob)} 
                className="w-full mt-2"
              />
            </div>
            
            <Button 
              onClick={uploadVoice} 
              disabled={isUploading}
              className="w-full bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700"
            >
              <Upload className="h-4 w-4 mr-2" />
              {isUploading ? 'Cloning Voice...' : 'Clone Voice'}
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