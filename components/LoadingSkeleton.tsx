import { Loader2 } from 'lucide-react';

interface LoadingSkeletonProps {
  message: string;
  minHeight?: string;
}

export default function LoadingSkeleton({ message, minHeight = "h-64" }: LoadingSkeletonProps) {
  return (
    <div className={`flex flex-col items-center justify-center ${minHeight} space-y-4`}>
      <Loader2 className="h-8 w-8 animate-spin text-purple-400" />
      <span className="text-gray-300 text-center">{message}</span>
      
      {/* Skeleton placeholders to maintain consistent height */}
      <div className="w-full space-y-3 animate-pulse">
        <div className="h-16 bg-gray-800/30 rounded-lg"></div>
        <div className="h-16 bg-gray-800/30 rounded-lg"></div>
        <div className="h-16 bg-gray-800/30 rounded-lg"></div>
      </div>
      
      <div className="w-full h-12 bg-gray-800/30 rounded-lg animate-pulse"></div>
    </div>
  );
} 