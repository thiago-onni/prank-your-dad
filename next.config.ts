import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  env: {
    ELEVENLABS_API_KEY: process.env.ELEVENLABS_API_KEY,
    VAPI_PRIVATE_KEY: process.env.VAPI_PRIVATE_KEY,
    VAPI_PHONE_NUMBER_ID: process.env.VAPI_PHONE_NUMBER_ID,
  },
};

export default nextConfig;
