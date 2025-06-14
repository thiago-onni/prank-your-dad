import React from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { PhoneForwarded, ArrowLeft, Phone } from "lucide-react";
import PhoneInput from "react-phone-number-input";
import "react-phone-number-input/style.css";

interface TransferStepProps {
  transferPhoneNumber: string;
  skipTransfer: boolean;
  onTransferPhoneNumberChange: (phoneNumber: string) => void;
  onSkipTransferChange: (skip: boolean) => void;
  onBack: () => void;
  onStartCall: () => void;
  isDisabled: boolean;
}

export function TransferStep({
  transferPhoneNumber,
  skipTransfer,
  onTransferPhoneNumberChange,
  onSkipTransferChange,
  onBack,
  onStartCall,
  isDisabled,
}: TransferStepProps) {
  // Call is valid if user chose to skip transfer OR provided a transfer number
  const canStartCall = skipTransfer || transferPhoneNumber.trim();

  const handleTransferPhoneChange = (value: string | undefined) => {
    onTransferPhoneNumberChange(value || "");
  };

  return (
    <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <PhoneForwarded className="h-5 w-5 text-purple-400" />
          Call Transfer (Optional)
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        <p className="text-gray-300">
          After the AI reveals itself, we can transfer the call to you so you
          can talk to your dad directly. You can also choose to skip this and
          let the call end naturally.
        </p>

        <div className="space-y-4">
          <div className="flex items-center space-x-2">
            <Checkbox
              id="skipTransfer"
              checked={skipTransfer}
              onCheckedChange={onSkipTransferChange}
              className="border-gray-600 data-[state=checked]:bg-purple-600"
            />
            <Label htmlFor="skipTransfer" className="text-white text-sm">
              Skip transfer - let the call end after AI reveal
            </Label>
          </div>

          {!skipTransfer && (
            <div className="space-y-2">
              <Label htmlFor="transferPhone" className="text-white">
                Your Phone Number
              </Label>
              <PhoneInput
                id="transferPhone"
                placeholder="Enter your phone number"
                className="phone-input bg-gray-900/50 border-gray-600 text-white focus:border-purple-500"
                value={transferPhoneNumber}
                onChange={handleTransferPhoneChange}
                defaultCountry="US"
                international
              />
              <p className="text-sm text-gray-400">
                Include country code for international numbers (e.g., +44 for
                UK)
              </p>
            </div>
          )}
        </div>

        <div className="flex gap-3">
          <Button
            variant="outline"
            onClick={onBack}
            className="flex-1 bg-gray-700/50 border-gray-600 text-gray-300 hover:bg-gray-600 hover:text-white"
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back
          </Button>
          <Button
            onClick={onStartCall}
            disabled={isDisabled || !canStartCall}
            className="flex-1 bg-gradient-to-r from-green-600 to-emerald-600 hover:from-green-700 hover:to-emerald-700 disabled:opacity-50"
          >
            <Phone className="h-4 w-4 mr-2" />
            Start Prank Call
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
