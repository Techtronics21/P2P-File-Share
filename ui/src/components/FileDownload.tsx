'use client';

import { useState } from 'react';
import { FiDownload } from 'react-icons/fi';

interface FileDownloadProps {
  onDownload: (port: number, token?: string) => Promise<void>;
  isDownloading: boolean;
}

export default function FileDownload({ onDownload, isDownloading }: FileDownloadProps) {
  const [accessToken, setAccessToken] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!accessToken.trim()) {
      setError('Please enter the access PIN');
      return;
    }

    try {
      // Use a dummy port, since only PIN is used now
      await onDownload(0, accessToken.trim());
    } catch (err) {
      setError('Failed to download the file. Please check the PIN and try again.');
    }
  };

  return (
    <div className="space-y-4">
      <div className="bg-blue-50 p-4 rounded-lg border border-blue-100">
        <h3 className="text-lg font-medium text-blue-800 mb-2">Receive a File</h3>
        <p className="text-sm text-blue-600 mb-0">
          Enter the access PIN shared with you to download the file.
        </p>
      </div>
      
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="accessToken" className="block text-sm font-medium text-gray-700 mb-1">
            Access PIN
          </label>
          <input
            type="text"
            id="accessToken"
            value={accessToken}
            onChange={(e) => setAccessToken(e.target.value)}
            placeholder="Enter the 12-digit access PIN"
            className="input-field font-mono tracking-wider"
            disabled={isDownloading}
            maxLength={12}
            required
          />
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}
        
        <button
          type="submit"
          className="btn-primary flex items-center justify-center w-full"
          disabled={isDownloading}
        >
          {isDownloading ? (
            <span>Downloading...</span>
          ) : (
            <>
              <FiDownload className="mr-2" />
              <span>Download File</span>
            </>
          )}
        </button>
      </form>
    </div>
  );
}
