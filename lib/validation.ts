export function validatePhoneNumber(phoneNumber: string): boolean {
  // Basic phone number validation - allows various formats
  const phoneRegex = /^[+]?[\d\s\-\(\)]{10,}$/;
  return phoneRegex.test(phoneNumber.trim());
}

export function validateVoiceId(voiceId: string): boolean {
  return voiceId.trim().length > 0;
}

export function validatePrankId(prankId: string, validPrankIds: string[]): boolean {
  return validPrankIds.includes(prankId);
} 