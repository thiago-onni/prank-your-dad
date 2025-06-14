'use client';

import { useState, useRef, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Trash2, Mic, Upload, Loader2, Square, Circle, RefreshCw, BookOpen, MessageSquare } from 'lucide-react';
import { toast } from 'sonner';

interface VoiceRecorderProps {
  onVoiceCloned: (voiceId: string) => void;
  systemPrompt: string;
  onSystemPromptChange: (prompt: string) => void;
  hideSystemPrompt?: boolean;
}

// Sample texts for voice cloning
const SAMPLE_TEXTS = [
  "The quick brown fox jumps over the lazy dog. This pangram contains every letter of the alphabet and is perfect for voice training.",
  "Technology has revolutionized the way we communicate, work, and live our daily lives in the modern world.",
  "Artificial intelligence is transforming industries by automating complex tasks and providing intelligent insights to businesses.",
  "The weather today is absolutely beautiful with clear blue skies and a gentle breeze flowing through the trees.",
  "Reading books expands our knowledge, improves vocabulary, and takes us on incredible journeys through imagination.",
  "Music has the power to evoke emotions, bring people together, and create lasting memories that span generations.",
  "Cooking delicious meals brings families together and allows us to explore different cultures through their traditional flavors.",
  "Exercise and healthy eating habits are essential for maintaining physical fitness and overall well-being throughout life.",
  "Travel opens our minds to new experiences, different perspectives, and helps us appreciate the diversity of our world.",
  "Learning new skills and pursuing hobbies keeps our minds active and provides a sense of accomplishment and personal growth."
];

export default function VoiceRecorder({ onVoiceCloned, systemPrompt, onSystemPromptChange, hideSystemPrompt = false }: VoiceRecorderProps) {
  const [recordings, setRecordings] = useState<{ blob: Blob; url: string }[]>([]);
  const [voiceName, setVoiceName] = useState('');
  const [isCloning, setIsCloning] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [recordingTime, setRecordingTime] = useState(0);
  const [currentText, setCurrentText] = useState(SAMPLE_TEXTS[0]);
  const [highlightedWords, setHighlightedWords] = useState<Set<number>>(new Set());
  const [recognizedText, setRecognizedText] = useState('');
  
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<NodeJS.Timeout | null>(null);
  const recognitionRef = useRef<SpeechRecognition | null>(null);

  // Initialize speech recognition
  useEffect(() => {
    if (typeof window !== 'undefined' && ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window)) {
      const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!SpeechRecognition) return;
      
      const recognition = new SpeechRecognition();
      
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = 'en-US';
      
      recognition.onresult = (event) => {
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
        
        const fullTranscript = (finalTranscript + interimTranscript).toLowerCase();
        setRecognizedText(fullTranscript);
        
                 // Highlight matching words sequentially
         const textWords = currentText.toLowerCase().split(/\s+/);
         const spokenWords = fullTranscript.split(/\s+/);
         const highlighted = new Set<number>();
         
         // Find the furthest sequential match
         let lastMatchedIndex = -1;
         for (let i = 0; i < textWords.length; i++) {
           const cleanTextWord = textWords[i].replace(/[^\w]/g, '');
           if (cleanTextWord.length < 2) continue; // Skip very short words
           
           // Check if this word appears in the spoken text
           const wordFound = spokenWords.some(spokenWord => {
             const cleanSpokenWord = spokenWord.replace(/[^\w]/g, '');
             return cleanSpokenWord.length >= 2 && (
               cleanSpokenWord.includes(cleanTextWord) || 
               cleanTextWord.includes(cleanSpokenWord) ||
               (cleanTextWord.length >= 3 && cleanSpokenWord.length >= 3 && 
                (cleanTextWord.startsWith(cleanSpokenWord.slice(0, 3)) || 
                 cleanSpokenWord.startsWith(cleanTextWord.slice(0, 3))))
             );
           });
           
           if (wordFound && i <= lastMatchedIndex + 3) { // Allow small gaps
             lastMatchedIndex = i;
           }
         }
         
         // Highlight all words up to the last matched index
         for (let i = 0; i <= lastMatchedIndex; i++) {
           highlighted.add(i);
         }
         
         setHighlightedWords(highlighted);
      };
      
      recognition.onerror = (event) => {
        console.error('Speech recognition error:', event.error);
      };
      
      recognitionRef.current = recognition;
    }
    
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
    };
  }, [currentText]);

  const getRandomText = () => {
    const randomIndex = Math.floor(Math.random() * SAMPLE_TEXTS.length);
    setCurrentText(SAMPLE_TEXTS[randomIndex]);
    setHighlightedWords(new Set());
    setRecognizedText('');
  };

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ 
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          sampleRate: 44100
        } 
      });

      // Try to use the most compatible format
      let mimeType = 'audio/webm';
      if (MediaRecorder.isTypeSupported('audio/mp4')) {
        mimeType = 'audio/mp4';
      } else if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
        mimeType = 'audio/webm;codecs=opus';
      } else if (MediaRecorder.isTypeSupported('audio/webm')) {
        mimeType = 'audio/webm';
      }

      const mediaRecorder = new MediaRecorder(stream, { mimeType });
      mediaRecorderRef.current = mediaRecorder;
      chunksRef.current = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: mimeType });
        const url = URL.createObjectURL(blob);
        setRecordings(prev => [...prev, { blob, url }]);
        toast.success('Recording saved');
        
        // Stop all tracks
        stream.getTracks().forEach(track => track.stop());
        
        // Stop speech recognition
        if (recognitionRef.current) {
          recognitionRef.current.stop();
        }
        
        // Get new random text for next recording
        getRandomText();
      };

      mediaRecorder.start();
      setIsRecording(true);
      setRecordingTime(0);
      setHighlightedWords(new Set());
      setRecognizedText('');

      // Start speech recognition
      if (recognitionRef.current) {
        recognitionRef.current.start();
      }

      // Start timer
      timerRef.current = setInterval(() => {
        setRecordingTime(prev => prev + 1);
      }, 1000);

    } catch (error) {
      console.error('Error starting recording:', error);
      toast.error('Failed to start recording. Please check microphone permissions.');
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
    }
  };

  const removeRecording = (index: number) => {
    setRecordings(prev => {
      const updated = [...prev];
      URL.revokeObjectURL(updated[index].url);
      updated.splice(index, 1);
      return updated;
    });
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const handleCloneVoice = async () => {
    if (!voiceName.trim()) {
      toast.error('Please enter a name for the voice');
      return;
    }

    if (recordings.length < 1) {
      toast.error('Please record at least one audio sample');
      return;
    }

    setIsCloning(true);
    
    try {
      // Prepare form data
      const formData = new FormData();
      formData.append('name', voiceName);
      
      // Add all recordings as files
      recordings.forEach((recording, index) => {
        // Determine file extension based on blob type
        const blobType = recording.blob.type;
        let extension = '.webm';
        let mimeType = 'audio/webm';
        
        if (blobType.includes('mp4')) {
          extension = '.mp4';
          mimeType = 'audio/mp4';
        } else if (blobType.includes('webm')) {
          extension = '.webm';
          mimeType = 'audio/webm';
        }
        
        const file = new File([recording.blob], `recording-${index}${extension}`, { type: mimeType });
        formData.append('files', file);
      });

      // Send to our API
      const response = await fetch('/api/clone-voice', {
        method: 'POST',
        body: formData,
      });

      const result = await response.json();

      if (!response.ok) {
        throw new Error(result.error || 'Failed to clone voice');
      }

      if (result.requiresVerification) {
        toast.warning('Voice created but requires verification in ElevenLabs dashboard');
      } else {
        toast.success('Voice cloned successfully!');
      }

      // Pass the voice ID back to parent
      onVoiceCloned(result.voiceId);
      
    } catch (error) {
      console.error('Voice cloning error:', error);
      toast.error(error instanceof Error ? error.message : 'Failed to clone voice');
    } finally {
      setIsCloning(false);
    }
  };

  const renderHighlightedText = () => {
    const words = currentText.split(/(\s+)/);
    let wordIndex = 0;
    
    return words.map((segment, index) => {
      if (segment.trim()) {
        const isHighlighted = highlightedWords.has(wordIndex);
        wordIndex++;
        return (
                     <span
             key={index}
             className={`transition-all duration-200 ${
               isHighlighted 
                 ? 'bg-green-400/30 text-green-200 rounded' 
                 : 'text-gray-300'
             }`}
           >
            {segment}
          </span>
        );
      }
      return <span key={index}>{segment}</span>;
    });
  };

  const totalDuration = recordings.length * 15; // Approximate 15 seconds per recording

  return (
    <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <Mic className="h-5 w-5 text-purple-400" />
          Clone Your Voice
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Voice Name Input */}
        <div>
          <label className="text-sm text-gray-300 mb-2 block">Voice Name</label>
          <input
            type="text"
            value={voiceName}
            onChange={(e) => setVoiceName(e.target.value)}
            placeholder="e.g., My Voice"
            className="w-full px-4 py-2 bg-gray-900/50 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-purple-500"
          />
        </div>

        {/* Reading Prompt */}
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <label className="text-sm text-gray-300 flex items-center gap-2">
              <BookOpen className="h-4 w-4" />
              Read this text aloud:
            </label>
                         <Button
               onClick={getRandomText}
               size="sm"
               variant="outline"
               className="bg-gray-700/50 border-gray-600 text-gray-300 hover:bg-gray-600 hover:text-white hover:border-gray-500 transition-colors"
               disabled={isRecording}
             >
               <RefreshCw className="h-3 w-3 mr-1" />
               New Text
             </Button>
          </div>
          
          <div className="p-4 bg-gray-900/50 border border-gray-700 rounded-lg">
            <p className="text-base leading-relaxed">
              {renderHighlightedText()}
            </p>
          </div>
          
          {isRecording && recognizedText && (
            <div className="text-xs text-gray-400">
              <span className="font-medium">Recognized: </span>
              <span className="italic">{recognizedText}</span>
            </div>
          )}
        </div>

        {/* Recording Instructions */}
        <Alert className="bg-blue-500/10 border-blue-500/30">
          <AlertDescription className="text-blue-200 text-sm">
            Read the text above naturally while recording. Words will highlight as you speak them. 
            Record 1-3 samples for best quality.
          </AlertDescription>
        </Alert>

        {/* Audio Recorder */}
        <div className="flex flex-col items-center gap-4 p-6 bg-gray-900/50 rounded-lg border border-gray-700">
          {!isRecording ? (
            <Button
              onClick={startRecording}
              size="lg"
              className="bg-red-600 hover:bg-red-700 text-white"
              disabled={recordings.length >= 25}
            >
              <Circle className="mr-2 h-4 w-4" />
              Start Recording
            </Button>
          ) : (
            <>
              <div className="flex flex-col items-center gap-2">
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 bg-red-500 rounded-full animate-pulse" />
                  <span className="text-white font-mono text-lg">{formatTime(recordingTime)}</span>
                </div>
                <p className="text-gray-400 text-sm">Recording... Read the text above</p>
              </div>
              <Button
                onClick={stopRecording}
                size="lg"
                className="bg-red-600 hover:bg-red-700 text-white border-0"
              >
                <Square className="mr-2 h-4 w-4" />
                Stop Recording
              </Button>
            </>
          )}
        </div>

        {/* Recordings List */}
        {recordings.length > 0 && (
          <div className="space-y-2">
            <div className="flex items-center justify-between text-sm text-gray-400">
              <span>{recordings.length} recording{recordings.length !== 1 ? 's' : ''}</span>
              <span>~{totalDuration}s total</span>
            </div>
            <div className="space-y-2">
              {recordings.map((recording, index) => (
                <div key={index} className="flex items-center justify-between p-3 bg-gray-900/50 rounded border border-gray-700">
                  <div className="flex items-center gap-3">
                    <span className="text-sm text-gray-300">Recording {index + 1}</span>
                    <audio src={recording.url} controls className="h-8" />
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => removeRecording(index)}
                    className="text-red-400 hover:text-red-300 hover:bg-red-500/10"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Clone Button */}
        <Button
          onClick={handleCloneVoice}
          disabled={isCloning || recordings.length === 0 || !voiceName.trim()}
          className="w-full bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700"
        >
          {isCloning ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Cloning Voice...
            </>
          ) : (
            <>
              <Upload className="mr-2 h-4 w-4" />
              Clone Voice
            </>
          )}
        </Button>

        {/* System Prompt */}
        {!hideSystemPrompt && (
          <div className="space-y-2">
            <Label htmlFor="systemPrompt" className="text-white font-medium flex items-center gap-2">
              <MessageSquare className="h-4 w-4 text-purple-400" />
              System Prompt
            </Label>
            <Textarea
              id="systemPrompt"
              value={systemPrompt}
              onChange={(e) => onSystemPromptChange(e.target.value)}
              placeholder="Enter the system prompt for your AI assistant..."
              rows={6}
              className="bg-gray-900/50 border-gray-600 text-white placeholder:text-gray-400 focus:border-purple-500 resize-none"
            />
            <p className="text-gray-400 text-sm">
              This defines how your AI assistant will behave during the conversation. You can customize it for different scenarios.
            </p>
          </div>
        )}

        {/* Requirements */}
        <div className="text-xs text-gray-500 space-y-1">
          <p>• Minimum 1 recording required</p>
          <p>• Maximum 25 recordings allowed</p>
          <p>• Read the provided text naturally</p>
          <p>• Words will highlight as you speak them</p>
        </div>
      </CardContent>
    </Card>
  );
} 