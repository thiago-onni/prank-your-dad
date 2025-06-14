"use client";

import React, { useState, useRef, useEffect, useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Mic, Upload, Square, SkipForward, Play, Trash2 } from "lucide-react";
import { toast } from "sonner";

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
  onend: ((event: Event) => void) | null;
}

export default function VoiceRecorder({
  onVoiceCloned,
  hideSystemPrompt = false,
  voiceTrainingTexts,
  systemPrompt = "",
  onSystemPromptChange,
}: VoiceRecorderProps) {
  const [isRecording, setIsRecording] = useState(false);
  const [recordings, setRecordings] = useState<RecordingData[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const [currentText, setCurrentText] = useState("");
  const [recognizedText, setRecognizedText] = useState("");
  const [usedTextIndices, setUsedTextIndices] = useState<Set<number>>(
    new Set()
  );
  const [allTextsCompleted, setAllTextsCompleted] = useState(false);
  const [showTooltip, setShowTooltip] = useState(false);
  const [isContinuousMode, setIsContinuousMode] = useState(false);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recognitionRef = useRef<CustomSpeechRecognition | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const sentenceStartTimeRef = useRef<number>(0);

  // Calculate total recording duration (estimated from blob size)
  const getTotalRecordingDuration = useCallback((): number => {
    // Estimate duration based on blob size (rough approximation: 1KB ≈ 0.1s for voice)
    return recordings.reduce((total, recording) => {
      const sizeInKB = recording.blob.size / 1024;
      const estimatedDuration = sizeInKB * 0.1; // Rough estimation
      return total + estimatedDuration;
    }, 0);
  }, [recordings]);

  // Get recording quality status with tiered progress
  const getRecordingQuality = useCallback(() => {
    const totalDuration = getTotalRecordingDuration();

    if (totalDuration < 30) {
      return {
        status: "poor",
        label: "Very Little",
        description: "May sound robotic",
        color: "text-red-400",
        bgColor: "bg-red-500",
        progressPercent: Math.min((totalDuration / 30) * 25, 25),
      };
    } else if (totalDuration < 90) {
      return {
        status: "fair",
        label: "Medium",
        description: "Will sound decent",
        color: "text-yellow-400",
        bgColor: "bg-yellow-500",
        progressPercent: 25 + Math.min(((totalDuration - 30) / 60) * 25, 25),
      };
    } else if (totalDuration < 300) {
      return {
        status: "good",
        label: "Great Quality",
        description: "High-quality voice clone",
        color: "text-green-400",
        bgColor: "bg-green-500",
        progressPercent: 50 + Math.min(((totalDuration - 90) / 210) * 30, 30),
      };
    } else {
      return {
        status: "excellent",
        label: "Exceptional",
        description: "Uneducated ears will be fooled",
        color: "text-pink-400",
        bgColor: "bg-pink-500",
        progressPercent: Math.min(80 + ((totalDuration - 300) / 300) * 20, 100),
      };
    }
  }, [getTotalRecordingDuration]);

  const generateNewText = useCallback(() => {
    if (usedTextIndices.size >= voiceTrainingTexts.length) {
      setAllTextsCompleted(true);
      setCurrentText(
        "🎉 All training texts completed! You can now clone your voice."
      );
      return;
    }

    let randomIndex: number;
    do {
      randomIndex = Math.floor(Math.random() * voiceTrainingTexts.length);
    } while (usedTextIndices.has(randomIndex));

    setUsedTextIndices((prev) => new Set([...prev, randomIndex]));
    setCurrentText(voiceTrainingTexts[randomIndex]);
    setRecognizedText("");
    sentenceStartTimeRef.current = Date.now();
  }, [usedTextIndices, voiceTrainingTexts]);

  // Initialize with the first text (Father's Day prompt), then use random for subsequent texts
  useEffect(() => {
    if (voiceTrainingTexts.length > 0 && !currentText) {
      // Always start with the first text (Father's Day prompt)
      setUsedTextIndices(new Set([0]));
      setCurrentText(voiceTrainingTexts[0]);
      setRecognizedText("");
      sentenceStartTimeRef.current = Date.now();
    }
  }, [voiceTrainingTexts, currentText]);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

      // Start media recorder
      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];
      sentenceStartTimeRef.current = Date.now();

      mediaRecorderRef.current.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorderRef.current.start(1000);

      // Start speech recognition
      if (typeof window !== "undefined") {
        const windowWithSpeech = window as Window & {
          SpeechRecognition?: new () => CustomSpeechRecognition;
          webkitSpeechRecognition?: new () => CustomSpeechRecognition;
        };

        const SpeechRecognition =
          windowWithSpeech.SpeechRecognition ||
          windowWithSpeech.webkitSpeechRecognition;

        if (SpeechRecognition) {
          const recognition = new SpeechRecognition();
          recognition.continuous = true;
          recognition.interimResults = true;
          recognition.lang = "en-US";

          recognition.onresult = (event: CustomSpeechRecognitionEvent) => {
            let finalTranscript = "";

            for (let i = event.resultIndex; i < event.results.length; i++) {
              const transcript = event.results[i][0].transcript;
              if (event.results[i].isFinal) {
                finalTranscript += transcript;
              }
            }

            // Handle final results - save to state
            if (finalTranscript) {
              setRecognizedText((prev) => prev + finalTranscript);
            }
          };

          recognition.onerror = (event: CustomSpeechRecognitionErrorEvent) => {
            console.log("Speech recognition error:", event.error);
          };

          recognitionRef.current = recognition;
          recognition.start();
        }
      }
    } catch (error) {
      console.error("Error starting recording:", error);
      throw error;
    }
  };

  const cropCurrentSample = async () => {
    if (!mediaRecorderRef.current || !isContinuousMode) return;

    return new Promise<void>((resolve) => {
      const currentRecorder = mediaRecorderRef.current;
      if (!currentRecorder) {
        resolve();
        return;
      }

      // Set up handler for when recording stops and final data is available
      currentRecorder.onstop = () => {
        // Save current recording with all available audio data
        const audioBlob = new Blob(audioChunksRef.current, {
          type: "audio/webm",
        });

        console.log("Saving audio blob of size:", audioBlob.size);

        const recording: RecordingData = {
          id: Date.now().toString(),
          blob: audioBlob,
          text: currentText,
          recognizedText: recognizedText,
          timestamp: sentenceStartTimeRef.current,
          url: URL.createObjectURL(audioBlob),
        };

        setRecordings((prev) => {
          const newRecordings = [...prev, recording];
          console.log("Total recordings:", newRecordings.length);
          return newRecordings;
        });

        // Clean up everything else
        if (recognitionRef.current) {
          recognitionRef.current.stop();
          recognitionRef.current = null;
        }

        if (currentRecorder.stream) {
          currentRecorder.stream.getTracks().forEach((track) => track.stop());
        }

        mediaRecorderRef.current = null;

        // Reset state
        setRecognizedText("");
        audioChunksRef.current = [];

        toast.success("Sample captured! Starting next prompt...");

        // Check if done
        if (allTextsCompleted) {
          setIsContinuousMode(false);
          setIsRecording(false);
          resolve();
          return;
        }

        // Get new text and restart
        generateNewText();

        setTimeout(async () => {
          if (isContinuousMode && !allTextsCompleted) {
            try {
              await startRecording();
            } catch (error) {
              console.error("Failed to restart recording:", error);
              toast.error("Failed to start recording for next prompt");
            }
          }
          resolve();
        }, 1000);
      };

      // Stop the recording - this will trigger the onstop handler
      currentRecorder.stop();
    });
  };

  const startContinuousRecording = async () => {
    try {
      setIsContinuousMode(true);
      setIsRecording(true);
      setShowTooltip(true);
      setRecognizedText("");

      await startRecording();

      // Hide tooltip after 5 seconds
      setTimeout(() => setShowTooltip(false), 5000);
    } catch (error) {
      console.error("Error starting recording:", error);
      toast.error(
        "Failed to start recording. Please check microphone permissions."
      );
      setIsContinuousMode(false);
      setIsRecording(false);
    }
  };

  const stopContinuousRecording = async () => {
    // Check if current recording is longer than 10 seconds
    const currentDuration = Date.now() - sentenceStartTimeRef.current;
    const shouldSaveSample = currentDuration > 10000; // 10 seconds in milliseconds

    console.log("Stop recording called:", {
      currentDuration: Math.round(currentDuration / 1000) + "s",
      shouldSaveSample,
      hasMediaRecorder: !!mediaRecorderRef.current,
      mediaRecorderState: mediaRecorderRef.current?.state,
      audioChunksLength: audioChunksRef.current.length,
    });

    if (
      shouldSaveSample &&
      mediaRecorderRef.current &&
      audioChunksRef.current.length > 0
    ) {
      // Save the current sample before stopping
      return new Promise<void>((resolve) => {
        const currentRecorder = mediaRecorderRef.current;
        if (!currentRecorder) {
          console.log("No current recorder found");
          stopRecordingCleanup();
          resolve();
          return;
        }

        // Set up handler for when recording stops and final data is available
        currentRecorder.onstop = () => {
          console.log("MediaRecorder stopped, processing audio...");
          // Save current recording with all available audio data
          const audioBlob = new Blob(audioChunksRef.current, {
            type: "audio/webm",
          });

          console.log("Saving audio blob of size:", audioBlob.size);

          const recording: RecordingData = {
            id: Date.now().toString(),
            blob: audioBlob,
            text: currentText,
            recognizedText: recognizedText,
            timestamp: sentenceStartTimeRef.current,
            url: URL.createObjectURL(audioBlob),
          };

          setRecordings((prev) => {
            const newRecordings = [...prev, recording];
            console.log("Total recordings:", newRecordings.length);
            return newRecordings;
          });

          stopRecordingCleanup();
          toast.success("Recording saved and session ended.");
          resolve();
        };

        // Stop the recording - this will trigger the onstop handler
        if (currentRecorder.state === "recording") {
          console.log("Stopping MediaRecorder...");
          currentRecorder.stop();
        } else {
          console.log(
            "MediaRecorder not in recording state:",
            currentRecorder.state
          );
          // If not recording, just process what we have
          const audioBlob = new Blob(audioChunksRef.current, {
            type: "audio/webm",
          });

          console.log("Saving audio blob of size:", audioBlob.size);

          const recording: RecordingData = {
            id: Date.now().toString(),
            blob: audioBlob,
            text: currentText,
            recognizedText: recognizedText,
            timestamp: sentenceStartTimeRef.current,
            url: URL.createObjectURL(audioBlob),
          };

          setRecordings((prev) => {
            const newRecordings = [...prev, recording];
            console.log("Total recordings:", newRecordings.length);
            return newRecordings;
          });

          stopRecordingCleanup();
          toast.success("Recording saved and session ended.");
          resolve();
        }
      });
    } else {
      stopRecordingCleanup();
      if (currentDuration < 10000) {
        toast.info(
          `Recording too short (${Math.round(
            currentDuration / 1000
          )}s). Need 10+ seconds to save.`
        );
      } else {
        toast.success("Recording session ended.");
      }
    }
  };

  const stopRecordingCleanup = () => {
    // Stop speech recognition
    if (recognitionRef.current) {
      recognitionRef.current.stop();
      recognitionRef.current.onresult = null;
      recognitionRef.current.onerror = null;
      recognitionRef.current.onend = null;
      recognitionRef.current = null;
    }

    // Stop all media tracks
    if (mediaRecorderRef.current?.stream) {
      mediaRecorderRef.current.stream
        .getTracks()
        .forEach((track) => track.stop());
    }

    // Clean up
    mediaRecorderRef.current = null;
    audioChunksRef.current = [];
    setRecognizedText("");
    setIsRecording(false);
    setIsContinuousMode(false);
    setShowTooltip(false);
  };

  const deleteRecording = (id: string) => {
    setRecordings((prev) => {
      const recording = prev.find((r) => r.id === id);
      if (recording) {
        URL.revokeObjectURL(recording.url);
      }
      return prev.filter((r) => r.id !== id);
    });
  };

  const uploadVoiceInternal = async (
    recordingsToUse: RecordingData[] = recordings,
    isRetry = false
  ) => {
    if (recordingsToUse.length === 0) {
      toast.error("Please record some voice samples first");
      return;
    }

    setIsUploading(true);

    try {
      const formData = new FormData();

      // Add a name for the voice
      formData.append("name", `Dad Voice Clone ${Date.now()}`);

      // Add all audio files with the key "files" (as expected by the API)
      recordingsToUse.forEach((recording, index) => {
        formData.append("files", recording.blob, `recording-${index}.webm`);
      });

      const response = await fetch("/api/clone-voice", {
        method: "POST",
        body: formData,
      });

      if (!response.ok) {
        const errorData = await response.json();

        // Check for the specific ElevenLabs "voice_sample_too_short" error
        if (
          errorData.details &&
          typeof errorData.details === "object" &&
          errorData.details.detail &&
          errorData.details.detail.status === "voice_sample_too_short"
        ) {
          console.log(
            "ElevenLabs rejected sample due to insufficient speech content"
          );

          // If we have more than one recording and this isn't already a retry, try again with fewer files
          if (recordingsToUse.length > 1 && !isRetry) {
            console.log(
              "Retrying with fewer files to exclude problematic samples..."
            );

            // Sort recordings by size (larger files more likely to have enough speech)
            const sortedRecordings = [...recordingsToUse].sort(
              (a, b) => b.blob.size - a.blob.size
            );

            // Try with the largest 70% of recordings (minimum 1)
            const numToKeep = Math.max(
              1,
              Math.floor(sortedRecordings.length * 0.7)
            );
            const filteredRecordings = sortedRecordings.slice(0, numToKeep);

            console.log(
              `Retrying with ${filteredRecordings.length} largest recordings (was ${recordingsToUse.length})`
            );

            // Silently retry with filtered recordings
            return uploadVoiceInternal(filteredRecordings, true);
          }
        }

        throw new Error(errorData.error || "Failed to clone voice");
      }

      const data = await response.json();

      if (data.success && data.voiceId) {
        if (isRetry) {
          toast.success(
            "Voice cloned successfully! (Some samples were automatically filtered out)"
          );
        } else {
          toast.success("Voice cloned successfully!");
        }
        onVoiceCloned(data.voiceId);
      } else {
        throw new Error(data.error || "Unknown error occurred");
      }
    } catch (error) {
      console.error("Error cloning voice:", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unknown error occurred";
      toast.error(`Failed to clone voice: ${errorMessage}`);
    } finally {
      setIsUploading(false);
    }
  };

  const uploadVoice = async () => {
    return uploadVoiceInternal();
  };

  const renderProgressBar = () => {
    const quality = getRecordingQuality();
    const breakpoints = [
      { threshold: 30, color: "bg-red-500", width: 25 },
      { threshold: 90, color: "bg-yellow-500", width: 25 },
      { threshold: 300, color: "bg-green-500", width: 30 },
      { threshold: Infinity, color: "bg-pink-500", width: 20 },
    ];

    return (
      <div className="space-y-3">
        {/* Progress Bar */}
        <div className="relative">
          <div className="flex h-4 bg-gray-700 rounded-full overflow-hidden">
            {breakpoints.map((breakpoint, index) => (
              <div
                key={index}
                className={`${breakpoint.color} transition-all duration-500`}
                style={{
                  width: `${breakpoint.width}%`,
                  opacity:
                    quality.progressPercent >=
                    (index === 0
                      ? 0
                      : breakpoints
                          .slice(0, index)
                          .reduce((sum, bp) => sum + bp.width, 0))
                      ? 1
                      : 0.3,
                }}
              />
            ))}
          </div>

          {/* Progress Indicator */}
          <div
            className="absolute top-0 h-4 bg-white/20 rounded-full transition-all duration-500"
            style={{ width: `${quality.progressPercent}%` }}
          />
        </div>

        {/* Duration and Description */}
        <div className="flex flex-row items-center gap-2">
          {/* Quality Pill */}
          <div className="flex justify-center">
            <div
              className={`px-2 py-1 rounded-full text-sm font-medium ${quality.color} border border-current`}
            >
              {quality.label}
            </div>
          </div>

          <p className="text-sm text-gray-300">
            ~{Math.round(getTotalRecordingDuration())}s recorded.{" "}
            {quality.description}
          </p>
        </div>
      </div>
    );
  };

  const renderText = () => {
    if (allTextsCompleted) {
      return (
        <div className="relative text-center py-8">
          <p className="text-2xl">{currentText}</p>
        </div>
      );
    }

    return (
      <div className="relative">
        <p className="text-lg leading-relaxed">{currentText}</p>
      </div>
    );
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
      if (
        mediaRecorderRef.current &&
        mediaRecorderRef.current.state === "recording"
      ) {
        mediaRecorderRef.current.stop();
      }
      // Clean up URLs
      recordings.forEach((recording) => URL.revokeObjectURL(recording.url));
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
        <p className="text-sm text-gray-400">
          Record yourself reading different Father&apos;s Day themed texts to
          train your voice clone. Click &quot;Complete Prompt&quot; when you
          finish reading each prompt, or &quot;Skip Prompt&quot; to get a
          different one.
        </p>

        <div className="border rounded-lg p-4 bg-gray-900/30 border-gray-600">
          {renderProgressBar()}
        </div>

        <div className="border rounded-lg p-6 bg-gray-900/30 border-gray-600">
          <div className="flex justify-between items-center mb-4">
            <h3 className="font-medium text-white">
              {allTextsCompleted
                ? "Training Complete!"
                : "Read this text aloud:"}
            </h3>
            {!allTextsCompleted && !isRecording && (
              <Button
                variant="outline"
                size="sm"
                onClick={generateNewText}
                className="bg-gray-700/50 border-gray-600 text-gray-300 hover:bg-gray-600 hover:text-white"
              >
                <SkipForward className="h-4 w-4 mr-2" />
                Skip Prompt
              </Button>
            )}
          </div>

          <div className="text-gray-200">{renderText()}</div>
        </div>

        {/* Recording Controls */}
        <div className="relative">
          {!isRecording ? (
            <Button
              onClick={startContinuousRecording}
              className="w-full bg-red-600 hover:bg-red-700"
            >
              <Mic className="h-4 w-4 mr-2" />
              Start Recording
            </Button>
          ) : (
            <div className="flex gap-2">
              <Button
                variant="default"
                onClick={cropCurrentSample}
                className="flex-1 bg-green-600 hover:bg-green-700 text-white"
              >
                Complete Prompt
              </Button>
              <Button
                onClick={stopContinuousRecording}
                variant="destructive"
                className="flex-shrink-0"
              >
                <Square className="h-4 w-4 mr-2" />
                Stop Recording
              </Button>
            </div>
          )}
          {showTooltip && (
            <div className="absolute -top-12 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white text-sm px-3 py-2 rounded-lg shadow-lg z-10 whitespace-nowrap">
              Read each Father&apos;s Day prompt aloud with emotion! Click
              &quot;Complete Prompt&quot; when done, then continue to the next
              prompt.
              <div className="absolute top-full left-1/2 transform -translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-transparent border-t-gray-900"></div>
            </div>
          )}
        </div>

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
                    <div
                      key={recording.id}
                      className="border rounded-lg p-3 bg-gray-800/30 border-gray-600"
                    >
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
                        <Label className="text-xs text-gray-400 font-medium">
                          Text read:
                        </Label>
                        <p className="text-xs text-gray-300 mt-1 leading-relaxed">
                          {recording.text.length > 100
                            ? `${recording.text.substring(0, 100)}...`
                            : recording.text}
                        </p>
                      </div>

                      <audio controls src={recording.url} className="w-full" />
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
              {isUploading
                ? `Cloning Voice from ${recordings.length} Samples...`
                : `Clone Voice (${recordings.length} samples)`}
            </Button>
          </div>
        )}

        {!hideSystemPrompt && onSystemPromptChange && (
          <div className="space-y-2">
            <Label htmlFor="systemPrompt" className="text-white">
              System Prompt (Optional)
            </Label>
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
