import React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Heart, ArrowLeft, PhoneCall } from 'lucide-react';

interface TransferStepProps {
  transferPhoneNumber: string;
  skipTransfer: boolean;
  onTransferPhoneNumberChange: (phoneNumber: string) => void;
  onSkipTransferChange: (skip: boolean) => void;
  onBack: () => void;
  onStartCall: () => void;
  isDisabled?: boolean;
}

export function TransferStep({
  transferPhoneNumber,
  skipTransfer,
  onTransferPhoneNumberChange,
  onSkipTransferChange,
  onBack,
  onStartCall,
  isDisabled = false
}: TransferStepProps) {
  const canStartCall = skipTransfer || transferPhoneNumber.trim();

  return (
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
              onCheckedChange={(checked) => onSkipTransferChange(checked === true)}
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
                onChange={(e) => onTransferPhoneNumberChange(e.target.value)}
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
            onClick={onBack}
            className="flex-1"
            disabled={isDisabled}
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back
          </Button>
          <Button 
            onClick={onStartCall}
            disabled={!canStartCall || isDisabled}
            className="flex-1 bg-gradient-to-r from-red-600 to-pink-600 hover:from-red-700 hover:to-pink-700"
          >
            <PhoneCall className="h-4 w-4 mr-2" />
            Start Prank Call!
          </Button>
        </div>
      </CardContent>
    </Card>
  );
} 