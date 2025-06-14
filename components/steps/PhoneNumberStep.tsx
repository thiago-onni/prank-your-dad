import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Phone, ArrowLeft, ArrowRight } from "lucide-react";
import { toast } from "sonner";
import PhoneInput from "react-phone-number-input";
import "react-phone-number-input/style.css";
import { isValidPhoneNumber } from "react-phone-number-input";

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
  onNext,
}: PhoneNumberStepProps) {
  const [validationError, setValidationError] = useState<string>("");

  const handlePhoneNumberChange = (value: string | undefined) => {
    onPhoneNumberChange(value || "");

    // Clear validation error when user types
    if (validationError) {
      setValidationError("");
    }
  };

  const handleNext = () => {
    if (!phoneNumber) {
      setValidationError("Please enter a phone number");
      toast.error("Please enter a phone number");
      return;
    }

    if (!isValidPhoneNumber(phoneNumber)) {
      setValidationError("Please enter a valid phone number");
      toast.error("Please enter a valid phone number");
      return;
    }

    onNext();
  };

  return (
    <Card className="bg-gray-800/50 backdrop-blur-sm border-gray-700 shadow-2xl">
      <CardHeader>
        <CardTitle className="text-xl text-white flex items-center gap-2">
          <Phone className="h-5 w-5 text-purple-400" />
          Dad&apos;s Phone Number
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="dadPhone" className="text-white">
            Phone Number
          </Label>
          <PhoneInput
            id="dadPhone"
            placeholder="Enter phone number"
            className="phone-input bg-gray-900/50 border-gray-600 text-white focus:border-purple-500"
            value={phoneNumber}
            onChange={handlePhoneNumberChange}
            defaultCountry="US"
            international
          />
          {validationError && (
            <p className="text-red-400 text-sm">{validationError}</p>
          )}
        </div>

        <p className="text-sm text-gray-400">
          Enter phone number with area code. For international numbers, include
          the country code (e.g., +44 for UK). Format will be applied
          automatically.
        </p>

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
            onClick={handleNext}
            className="flex-1 bg-gradient-to-r from-purple-600 to-cyan-600 hover:from-purple-700 hover:to-cyan-700"
          >
            Next
            <ArrowRight className="h-4 w-4 ml-2" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
