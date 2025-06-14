import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { Toaster } from "@/components/ui/sonner";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Prank Your Dad - AI Voice Demo",
  description:
    "AI-powered Father's Day surprise made possible with Vapi. Clone your voice and prank call your dad!",
  metadataBase: new URL("https://call-dad.vapi.ai"),
  openGraph: {
    title: "Prank Your Dad - AI Voice Demo by Vapi",
    description:
      "AI-powered Father's Day surprise made possible with Vapi. Clone your voice and prank call your dad!",
    url: "https://call-dad.vapi.ai",
    siteName: "Prank Your Dad",
    images: [
      {
        url: "/og-banner.png",
        width: 1200,
        height: 630,
        alt: "Prank Your Dad - AI Voice Demo",
      },
    ],
    locale: "en_US",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "Prank Your Dad - AI Voice Demo by Vapi",
    description:
      "AI-powered Father's Day surprise made possible with Vapi. Clone your voice and prank call your dad!",
    images: ["/og-banner.png"],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
        <Toaster />
      </body>
    </html>
  );
}
