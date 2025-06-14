import { PhoneNumberUtil, PhoneNumberFormat } from 'google-libphonenumber';

const phoneUtil = PhoneNumberUtil.getInstance();

export function validatePhoneNumber(phoneNumber: string): boolean {
  try {
    // Try to parse the phone number
    let parsed;
    
    // If the number starts with +, parse without default region
    if (phoneNumber.trim().startsWith('+')) {
      parsed = phoneUtil.parseAndKeepRawInput(phoneNumber, undefined);
    } else {
      // For numbers without +, try international first, then US
      try {
        parsed = phoneUtil.parseAndKeepRawInput(phoneNumber, undefined);
      } catch {
        // If that fails, try with US as default region
        parsed = phoneUtil.parseAndKeepRawInput(phoneNumber, 'US');
      }
    }
    
    return phoneUtil.isValidNumber(parsed);
  } catch {
    return false;
  }
}

export function formatPhoneNumber(value: string): string {
  // Remove extra whitespace
  const cleaned = value.trim();
  
  if (!cleaned) return '';
  
  try {
    // Try to parse as international first (no default region)
    let parsed;
    
    // If the number starts with +, parse without default region
    if (cleaned.startsWith('+')) {
      parsed = phoneUtil.parseAndKeepRawInput(cleaned, undefined);
    } else {
      // For numbers without +, try to detect region or default to US
      try {
        // First try without default region in case it's a complete international number
        parsed = phoneUtil.parseAndKeepRawInput(cleaned, undefined);
      } catch {
        // If that fails, try with US as default region for domestic numbers
        parsed = phoneUtil.parseAndKeepRawInput(cleaned, 'US');
      }
    }
    
    // Always format as international for consistency and clarity
    return phoneUtil.format(parsed, PhoneNumberFormat.INTERNATIONAL);
  } catch {
    // If parsing fails completely, return the cleaned input with minimal formatting
    const digitsOnly = cleaned.replace(/\D/g, '');
    if (digitsOnly.length === 0) return '';
    
    // For international numbers, try to preserve the + prefix
    if (cleaned.startsWith('+') && digitsOnly.length > 0) {
      return `+${digitsOnly}`;
    }
    
    // For shorter numbers, return as-is
    if (digitsOnly.length <= 10) {
      return digitsOnly;
    }
    
    // For longer numbers, assume international and add + prefix
    return `+${digitsOnly}`;
  }
}

export function validateFormattedPhoneNumber(phoneNumber: string): { isValid: boolean; message?: string } {
  if (!phoneNumber.trim()) {
    return { isValid: false, message: 'Phone number is required' };
  }
  
  try {
    let parsed;
    const cleaned = phoneNumber.trim();
    
    // If the number starts with +, parse without default region
    if (cleaned.startsWith('+')) {
      parsed = phoneUtil.parseAndKeepRawInput(cleaned, undefined);
    } else {
      // For numbers without +, try international first, then US
      try {
        parsed = phoneUtil.parseAndKeepRawInput(cleaned, undefined);
      } catch {
        // If that fails, try with US as default region
        parsed = phoneUtil.parseAndKeepRawInput(cleaned, 'US');
      }
    }
    
    if (!phoneUtil.isValidNumber(parsed)) {
      return { isValid: false, message: 'Please enter a valid phone number' };
    }
    
    // Additional check for possible numbers
    if (!phoneUtil.isPossibleNumber(parsed)) {
      return { isValid: false, message: 'Phone number format is invalid' };
    }
    
    return { isValid: true };
  } catch {
    return { isValid: false, message: 'Please enter a valid phone number. Use international format (+country code) for non-US numbers' };
  }
}

// Helper function to normalize phone number for API calls
export function normalizePhoneNumber(phoneNumber: string): string {
  try {
    let parsed;
    const cleaned = phoneNumber.trim();
    
    // If the number starts with +, parse without default region
    if (cleaned.startsWith('+')) {
      parsed = phoneUtil.parseAndKeepRawInput(cleaned, undefined);
    } else {
      // For numbers without +, try international first, then US
      try {
        parsed = phoneUtil.parseAndKeepRawInput(cleaned, undefined);
      } catch {
        // If that fails, try with US as default region
        parsed = phoneUtil.parseAndKeepRawInput(cleaned, 'US');
      }
    }
    
    return phoneUtil.format(parsed, PhoneNumberFormat.E164);
  } catch {
    // Fallback: clean the number and format it
    const digitsOnly = phoneNumber.replace(/\D/g, '');
    
    // If the original number started with +, preserve it
    if (phoneNumber.trim().startsWith('+')) {
      return `+${digitsOnly}`;
    }
    
    // If it's a long number (>10 digits), assume it already includes country code
    if (digitsOnly.length > 10) {
      return `+${digitsOnly}`;
    }
    
    // For shorter numbers, assume US/Canada (+1)
    return `+1${digitsOnly}`;
  }
}

export function validateVoiceId(voiceId: string): boolean {
  return voiceId.trim().length > 0;
}

export function validatePrankId(prankId: string, validPrankIds: string[]): boolean {
  return validPrankIds.includes(prankId);
} 