'use client';

import { useState, useRef } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Trash2, Mic, Upload, Loader2, Square, Circle } from 'lucide-react';
import { toast } from 'sonner';

interface VoiceRecorderProps {
  onVoiceCloned: (voiceId: string) => void;
}

export default function VoiceRecorder({ onVoiceCloned }: VoiceRecorderProps) {
  const [recordings, setRecordings] = useState<{ blob: Blob; url: string }[]>([]);
  const [voiceName, setVoiceName] = useState('');
  const [isCloning, setIsCloning] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [recordingTime, setRecordingTime] = useState(0);
  
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<NodeJS.Timeout | null>(null);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ 
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          sampleRate: 44100
        } 
      });

      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') 
        ? 'audio/webm;codecs=opus' 
        : 'audio/webm';

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
      };

      mediaRecorder.start();
      setIsRecording(true);
      setRecordingTime(0);

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
        const file = new File([recording.blob], `recording-${index}.webm`, { type: 'audio/webm' });
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

        {/* Recording Instructions */}
        <Alert className="bg-blue-500/10 border-blue-500/30">
          <AlertDescription className="text-blue-200 text-sm">
            Record 1-3 samples of yourself speaking naturally (10-30 seconds each). 
            The more samples, the better the voice clone quality.
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
                <p className="text-gray-400 text-sm">Recording...</p>
              </div>
              <Button
                onClick={stopRecording}
                size="lg"
                variant="secondary"
                className="bg-gray-700 hover:bg-gray-600"
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

        {/* Requirements */}
        <div className="text-xs text-gray-500 space-y-1">
          <p>• Minimum 1 recording required</p>
          <p>• Maximum 25 recordings allowed</p>
          <p>• Speak naturally in your normal voice</p>
        </div>
      </CardContent>
    </Card>
  );
} 