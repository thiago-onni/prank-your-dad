'use client';

import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import LoadingSkeleton from './LoadingSkeleton';
import { toast } from 'sonner';
import { Settings, CheckCircle2, Bot, ArrowLeft } from 'lucide-react';

interface Assistant {
  id: string;
  name: string;
  model?: {
    provider?: string;
    model?: string;
  };
  voice?: {
    provider?: string;
    voiceId?: string;
  };
  createdAt?: string;
}

interface AssistantSelectorProps {
  onAssistantSelected: (assistantId: string) => void;
}

export default function AssistantSelector({ onAssistantSelected }: AssistantSelectorProps) {
  const [assistants, setAssistants] = useState<Assistant[]>([]);
  const [isLoadingAssistants, setIsLoadingAssistants] = useState(false);
  const [selectedAssistantId, setSelectedAssistantId] = useState<string | null>(null);
  const [isConfigured, setIsConfigured] = useState(false);

  // Load assistants on component mount
  useEffect(() => {
    loadAssistants();
  }, []);

  const loadAssistants = async () => {
    setIsLoadingAssistants(true);
    try {
      const response = await fetch('/api/assistants');
      const result = await response.json();
      
      if (!response.ok) {
        throw new Error(result.error || 'Failed to load assistants');
      }
      
      setAssistants(result.assistants || []);
    } catch (error) {
      console.error('Error loading assistants:', error);
      toast.error('Failed to load assistants from your account');
    } finally {
      setIsLoadingAssistants(false);
    }
  };

  const handleAssistantSelect = (assistantId: string) => {
    setSelectedAssistantId(assistantId);
  };

  const handleConfirmSelection = () => {
    if (!selectedAssistantId) {
      toast.error('Please select an assistant');
      return;
    }
    setIsConfigured(true);
  };

  const handleGoBack = () => {
    setIsConfigured(false);
    setSelectedAssistantId(null);
  };

  const handleStartCall = () => {
    if (selectedAssistantId) {
      onAssistantSelected(selectedAssistantId);
    }
  };

  // If configured, show the final interface
  if (isConfigured && selectedAssistantId) {
    const selectedAssistant = assistants.find(a => a.id === selectedAssistantId);
    const assistantName = selectedAssistant?.name || 'Selected Assistant';
    
    return (
      <div className="space-y-6">
        {/* Assistant Ready Card */}
        <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
          <CardContent className="pt-8 pb-8">
            <div className="flex flex-col gap-6">
              {/* Assistant Ready Badge */}
              <div className="flex justify-center">
                <Badge className="bg-green-500/20 text-green-300 border-green-500/30 px-4 py-2">
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  Assistant Ready: {assistantName}
                </Badge>
              </div>

              {/* Action Buttons */}
              <div className="flex gap-4 justify-center">
                <Button 
                  onClick={handleGoBack} 
                  variant="outline"
                  className="bg-gray-800/50 border-gray-600 text-gray-300 hover:bg-gray-700/50 hover:text-white h-14"
                >
                  <ArrowLeft className="mr-2 h-4 w-4" />
                  Change Assistant
                </Button>
                <Button 
                  onClick={handleStartCall} 
                  size="lg" 
                  className="min-w-[240px] h-14 text-lg bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700 shadow-lg"
                >
                  <Bot className="mr-2 h-6 w-6" />
                  Start AI Assistant
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <Settings className="h-5 w-5 text-purple-400" />
          Select Your Assistant
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          {isLoadingAssistants ? (
            <LoadingSkeleton message="Loading your assistants..." minHeight="h-80" />
          ) : assistants.length === 0 ? (
            <Alert className="bg-yellow-500/10 border-yellow-500/30">
              <AlertDescription className="text-yellow-200">
                No assistants found in your Vapi account. Please create an assistant in your Vapi dashboard first.
              </AlertDescription>
            </Alert>
          ) : (
            <>
              <div className="grid gap-3 max-h-64 overflow-y-auto">
                {assistants.map((assistant) => (
                  <div
                    key={assistant.id}
                    className={`p-4 rounded-lg border cursor-pointer transition-all ${
                      selectedAssistantId === assistant.id
                        ? 'bg-purple-500/20 border-purple-500/50 text-white'
                        : 'bg-gray-900/50 border-gray-600 text-gray-300 hover:bg-gray-800/50 hover:border-gray-500'
                    }`}
                    onClick={() => handleAssistantSelect(assistant.id)}
                  >
                    <div className="flex items-center justify-between">
                      <div>
                        <h3 className="font-medium">{assistant.name}</h3>
                        <div className="flex gap-2 mt-1">
                          {assistant.model?.provider && (
                            <Badge variant="outline" className="text-xs bg-blue-500/10 text-blue-300 border-blue-500/30">
                              {assistant.model.provider} {assistant.model.model}
                            </Badge>
                          )}
                          {assistant.voice?.provider && (
                            <Badge variant="outline" className="text-xs bg-green-500/10 text-green-300 border-green-500/30">
                              {assistant.voice.provider}
                            </Badge>
                          )}
                        </div>
                        <p className="text-xs opacity-60 mt-1">ID: {assistant.id}</p>
                      </div>
                      {selectedAssistantId === assistant.id && (
                        <CheckCircle2 className="h-5 w-5 text-purple-400" />
                      )}
                    </div>
                  </div>
                ))}
              </div>
              
              <Button
                onClick={handleConfirmSelection}
                disabled={!selectedAssistantId}
                className="w-full bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700"
              >
                <CheckCircle2 className="mr-2 h-4 w-4" />
                Use Selected Assistant
              </Button>
            </>
          )}
        </div>
      </CardContent>
    </Card>
  );
} 