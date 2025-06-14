import React from "react";
import { Badge } from "@/components/ui/badge";
import { PhoneCall, Sparkles, Zap } from "lucide-react";

export function AppHeader() {
  return (
    <div className="text-center mb-8">
      <div className="inline-flex items-center gap-3 mb-4">
        <div className="p-3 bg-gradient-to-br from-purple-500 to-cyan-500 rounded-2xl">
          <PhoneCall className="h-8 w-8 text-white" />
        </div>
        <h1 className="text-4xl md:text-5xl font-bold text-white">
          Prank Your Dad
        </h1>
      </div>
      <p className="text-gray-400 text-lg md:text-xl">
        AI-powered Father&apos;s Day prank call with your cloned voice
      </p>
      <div className="flex items-center justify-center gap-2 mt-2">
        <Badge
          variant="outline"
          className="bg-purple-500/10 text-purple-300 border-purple-500/30"
        >
          <Sparkles className="h-3 w-3 mr-1" />
          100% Free
        </Badge>
        <Badge
          variant="outline"
          className="bg-cyan-500/10 text-cyan-300 border-cyan-500/30"
        >
          <Zap className="h-3 w-3 mr-1" />
          Father&apos;s Day Special
        </Badge>
      </div>
    </div>
  );
}
