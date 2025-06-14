import React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Phone, ArrowLeft, ArrowRight } from 'lucide-react';
import { toast } from 'sonner';

interface PhoneNumberStepProps {
  phoneNumber: string;
  onPhoneNumberChange: (phoneNumber: string) => void;
  onBack: () => void;
  onNext: () => void;
}

export function PhoneNumberStep({
  phoneNumber,
  onPhoneNumberChange,
  onBack,
  onNext
}: PhoneNumberStepProps) {
  const handleSubmit = () => {
    if (!phoneNumber.trim()) {
      toast.error('Please enter your dad\'s phone number');
      return;
    }
    onNext();
  };

  return (
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
            value={phoneNumber}
            onChange={(e) => onPhoneNumberChange(e.target.value)}
            className="bg-gray-800/50 border-gray-600 text-white placeholder:text-gray-400"
          />
        </div>
        <div className="flex gap-3">
          <Button 
            variant="outline" 
            onClick={onBack}
            className="flex-1"
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back
          </Button>
          <Button 
            onClick={handleSubmit}
            className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600"
          >
            Continue
            <ArrowRight className="h-4 w-4 ml-2" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
} 