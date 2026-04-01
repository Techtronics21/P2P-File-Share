'use client';

import { useState } from 'react';
import { FiCopy, FiCheck } from 'react-icons/fi';

interface InviteCodeProps {
  port: number | null;
  token: string | null;
}

export default function InviteCode({ port, token }: InviteCodeProps) {
  const [copiedToken, setCopiedToken] = useState(false);
  if (!token) return null;

  const copyTokenToClipboard = () => {
    // If navigator.clipboard is available (Secure Context/Localhost)
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(token).then(() => {
        setCopiedToken(true);
        setTimeout(() => setCopiedToken(false), 2000);
      });
      return;
    }

    // Fallback for non-secure context (HTTP over LAN)
    try {
      const textArea = document.createElement("textarea");
      textArea.value = token;

      // Ensure the textarea is off-screen
      textArea.style.position = "fixed";
      textArea.style.left = "-999999px";
      textArea.style.top = "-999999px";

      document.body.appendChild(textArea);
      textArea.focus();
      textArea.select();

      const successful = document.execCommand('copy');
      if (successful) {
        setCopiedToken(true);
        setTimeout(() => setCopiedToken(false), 2000);
      }
      document.body.removeChild(textArea);
    } catch (err) {
      console.error('Fallback copy failed:', err);
    }
  };

  return (
    <div className="mt-6 p-4 bg-green-50 border border-green-200 rounded-lg">
      <h3 className="text-lg font-medium text-green-800">File Ready to Share!</h3>
      <p className="text-sm text-green-600 mb-3">
        Share this access PIN with anyone you want to share the file with:
      </p>
      <div className="space-y-3">
        <div>
          <label className="text-xs font-medium text-gray-600 mb-1 block">Access PIN</label>
          <div className="flex items-center">
            <div className="flex-1 bg-white p-3 rounded-l-md border border-r-0 border-gray-300 font-mono text-lg tracking-wider">
              {token}
            </div>
            <button
              onClick={copyTokenToClipboard}
              className="p-3 bg-green-500 hover:bg-green-600 text-white rounded-r-md transition-colors"
              aria-label="Copy access PIN"
            >
              {copiedToken ? <FiCheck className="w-5 h-5" /> : <FiCopy className="w-5 h-5" />}
            </button>
          </div>
        </div>
      </div>
      <p className="mt-3 text-xs text-gray-500">
        The access PIN is required to download the file. This adds extra security to your file sharing.
      </p>
    </div>
  );
}
