'use client';

import { useState, useRef, useCallback } from 'react';
import FileUpload from '@/components/FileUpload';
import FileDownload from '@/components/FileDownload';
import InviteCode from '@/components/InviteCode';
import axios from 'axios';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
const WS_BASE_URL = process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8081';
const CHUNK_SIZE = 64 * 1024; // 64KB chunks for WebSocket relay

export default function Home() {
  // Shared state
  const [activeTab, setActiveTab] = useState<'upload' | 'download'>('upload');
  const [token, setToken] = useState<string | null>(null);
  const [port, setPort] = useState<number | null>(null);

  // Upload mode: 'instant' = WebSocket relay, 'cloud' = S3 upload
  const [shareMode, setShareMode] = useState<'instant' | 'cloud'>('instant');

  // S3 upload state
  const [uploadedFile, setUploadedFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);

  // WebSocket relay state
  const [wsStatus, setWsStatus] = useState<
    'idle' | 'registering' | 'waiting' | 'transferring' | 'done' | 'error'
  >('idle');
  const [transferProgress, setTransferProgress] = useState(0);
  const wsRef = useRef<WebSocket | null>(null);
  const fileRef = useRef<File | null>(null);

  // Download state
  const [isDownloading, setIsDownloading] = useState(false);

  // ─── WebSocket Relay: send file in chunks with backpressure ───
  const sendFileViaWebSocket = useCallback((ws: WebSocket, file: File) => {
    let offset = 0;

    const sendNextChunk = () => {
      if (offset >= file.size) {
        ws.send(JSON.stringify({ type: 'TRANSFER_COMPLETE' }));
        setWsStatus('done');
        setTransferProgress(100);
        return;
      }

      // Backpressure: wait if browser's WS send buffer is backed up
      if (ws.bufferedAmount > 1024 * 1024) {
        setTimeout(sendNextChunk, 50);
        return;
      }

      const end = Math.min(offset + CHUNK_SIZE, file.size);
      const chunk = file.slice(offset, end);
      chunk.arrayBuffer().then((buffer) => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(buffer);
          offset = end;
          setTransferProgress(Math.round((offset / file.size) * 100));
          // Use setTimeout to avoid blocking the UI thread
          setTimeout(sendNextChunk, 0);
        }
      });
    };

    sendNextChunk();
  }, []);

  // ─── Handle "Share Now" (WebSocket Relay) ───
  const handleInstantShare = async (file: File) => {
    fileRef.current = file;
    setUploadedFile(file);
    setWsStatus('registering');
    setTransferProgress(0);
    setToken(null);
    setPort(null);

    try {
      // Step 1: Register file metadata (no file upload)
      const response = await axios.post(`${API_BASE_URL}/register`, {
        filename: file.name,
        size: file.size,
        contentType: file.type || 'application/octet-stream',
      });

      const newToken = response.data.token;
      setToken(newToken);

      // Step 2: Open WebSocket connection
      const ws = new WebSocket(`${WS_BASE_URL}/relay?token=${newToken}`);
      wsRef.current = ws;

      ws.onopen = () => {
        // Connection established, waiting for REGISTERED confirmation
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'REGISTERED') {
            setWsStatus('waiting');
          } else if (msg.type === 'SEND_FILE') {
            setWsStatus('transferring');
            if (fileRef.current) {
              sendFileViaWebSocket(ws, fileRef.current);
            }
          }
        } catch {
          // Non-JSON message, ignore
        }
      };

      ws.onerror = () => {
        setWsStatus('error');
      };

      ws.onclose = (event) => {
        if (wsRef.current === ws) {
          wsRef.current = null;
          // Only show error if we weren't done
          setWsStatus((prev) => (prev === 'done' ? 'done' : 'idle'));
        }
      };
    } catch (error: any) {
      console.error('Error registering:', error);
      setWsStatus('error');
      let errorMessage = 'Failed to register file share.';
      if (error.response?.data) {
        errorMessage = typeof error.response.data === 'string'
          ? error.response.data
          : error.response.data.error || errorMessage;
      }
      alert(errorMessage);
    }
  };

  // ─── Handle "Upload & Share Later" (S3) ───
  const handleCloudUpload = async (file: File) => {
    setUploadedFile(file);
    setIsUploading(true);
    setUploadProgress(0);
    setToken(null);
    setPort(null);
    setWsStatus('idle');

    try {
      const formData = new FormData();
      formData.append('file', file);

      const response = await axios.post(`${API_BASE_URL}/upload`, formData, {
        timeout: 300000,
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (progressEvent) => {
          if (progressEvent.total) {
            setUploadProgress(Math.round((progressEvent.loaded * 100) / progressEvent.total));
          }
        },
      });

      setPort(response.data.port ?? null);
      setToken(response.data.token ?? null);
    } catch (error: any) {
      console.error('Error uploading file:', error);
      let errorMessage = 'Failed to upload file. Please try again.';
      if (error.response?.data) {
        errorMessage = typeof error.response.data === 'string'
          ? error.response.data
          : error.response.data.error || errorMessage;
      } else if (error.message) {
        errorMessage = error.message;
      }
      alert(errorMessage);
    } finally {
      setIsUploading(false);
      setUploadProgress(0);
    }
  };

  // ─── Route to correct handler based on share mode ───
  const handleFileUpload = (file: File) => {
    if (shareMode === 'instant') {
      handleInstantShare(file);
    } else {
      handleCloudUpload(file);
    }
  };

  // ─── Download handler (unchanged) ───
  const handleDownload = async (_port: number, downloadToken?: string) => {
    setIsDownloading(true);

    try {
      const response = await axios.get(
        `${API_BASE_URL}/download?token=${encodeURIComponent(downloadToken || '')}`,
        { responseType: 'arraybuffer' }
      );

      const headers = response.headers ?? {};
      const contentType = headers['content-type'] || 'application/octet-stream';
      const contentDisposition = headers['content-disposition'] || '';

      const parseFilename = (cd: string): string | null => {
        if (!cd) return null;
        const filenameStar = cd.match(/filename\*\s*=\s*([^;]+)/i);
        if (filenameStar?.[1]) {
          const raw = filenameStar[1].trim().replace(/^UTF-8''/i, '').replace(/^"(.*)"$/, '$1');
          try { return decodeURIComponent(raw); } catch { return raw; }
        }
        const filenameNormal =
          cd.match(/filename\s*=\s*"([^"]+)"/i) ||
          cd.match(/filename\s*=\s*([^;]+)/i);
        return filenameNormal?.[1]?.trim().replace(/^"(.*)"$/, '$1') ?? null;
      };

      const extensionFromType = (type: string): string => {
        if (type.includes('image/jpeg')) return '.jpg';
        if (type.includes('image/png')) return '.png';
        if (type.includes('image/gif')) return '.gif';
        if (type.includes('application/pdf')) return '.pdf';
        if (type.includes('text/plain')) return '.txt';
        return '';
      };

      let filename = parseFilename(contentDisposition) || 'downloaded-file';
      if (!filename.includes('.')) {
        filename += extensionFromType(contentType);
      }

      const blob = new Blob([response.data], { type: contentType });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', filename);
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => window.URL.revokeObjectURL(url), 1000);
    } catch (error: any) {
      console.error('Error downloading file:', error);
      let message = 'Failed to download file. Please check the PIN and try again.';
      if (typeof error?.response?.data === 'string') {
        message = error.response.data;
      } else if (error?.response?.data?.error) {
        message = error.response.data.error;
      } else if (error?.message) {
        message = error.message;
      }
      alert(message);
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <div className="container mx-auto px-4 py-8 max-w-4xl">
      <header className="text-center mb-12">
        <h1 className="text-4xl font-bold text-blue-600 mb-2">PeerLink</h1>
        <p className="text-xl text-gray-600">Secure P2P File Sharing</p>
      </header>

      <div className="bg-white rounded-lg shadow-lg p-6">
        <div className="flex border-b mb-6">
          <button
            className={`px-4 py-2 font-medium ${activeTab === 'upload'
              ? 'text-blue-600 border-b-2 border-blue-600'
              : 'text-gray-500 hover:text-gray-700'
            }`}
            onClick={() => setActiveTab('upload')}
          >
            Share a File
          </button>
          <button
            className={`px-4 py-2 font-medium ${activeTab === 'download'
              ? 'text-blue-600 border-b-2 border-blue-600'
              : 'text-gray-500 hover:text-gray-700'
            }`}
            onClick={() => setActiveTab('download')}
          >
            Receive a File
          </button>
        </div>

        {activeTab === 'upload' ? (
          <div>
            {/* ── Share Mode Selection ── */}
            <div className="mb-6 p-4 bg-gray-50 rounded-lg">
              <p className="text-sm font-medium text-gray-700 mb-3">How would you like to share?</p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <label
                  className={`flex items-start gap-3 p-3 rounded-lg border-2 cursor-pointer transition-all ${
                    shareMode === 'instant'
                      ? 'border-blue-500 bg-blue-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <input
                    type="radio"
                    name="shareMode"
                    value="instant"
                    checked={shareMode === 'instant'}
                    onChange={() => setShareMode('instant')}
                    className="mt-1"
                  />
                  <div>
                    <span className="font-medium text-gray-800">⚡ Share Now</span>
                    <p className="text-xs text-gray-500 mt-0.5">
                      Stream directly to receiver. Keep this tab open.
                    </p>
                  </div>
                </label>
                <label
                  className={`flex items-start gap-3 p-3 rounded-lg border-2 cursor-pointer transition-all ${
                    shareMode === 'cloud'
                      ? 'border-blue-500 bg-blue-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <input
                    type="radio"
                    name="shareMode"
                    value="cloud"
                    checked={shareMode === 'cloud'}
                    onChange={() => setShareMode('cloud')}
                    className="mt-1"
                  />
                  <div>
                    <span className="font-medium text-gray-800">☁️ Upload & Share Later</span>
                    <p className="text-xs text-gray-500 mt-0.5">
                      Upload to cloud. Receiver downloads anytime.
                    </p>
                  </div>
                </label>
              </div>
            </div>

            <FileUpload
              onFileUpload={handleFileUpload}
              isUploading={isUploading || wsStatus === 'registering'}
              uploadProgress={shareMode === 'instant' ? transferProgress : uploadProgress}
            />

            {/* ── WebSocket Relay Status ── */}
            {shareMode === 'instant' && wsStatus === 'waiting' && (
              <div className="mt-4 p-4 bg-green-50 border border-green-200 rounded-lg">
                <div className="flex items-center gap-2 mb-1">
                  <span className="relative flex h-3 w-3">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-3 w-3 bg-green-500"></span>
                  </span>
                  <span className="font-medium text-green-800">Ready — waiting for receiver</span>
                </div>
                <p className="text-sm text-green-600">Keep this tab open. The file will stream directly when someone enters your PIN.</p>
              </div>
            )}

            {shareMode === 'instant' && wsStatus === 'transferring' && (
              <div className="mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
                <p className="font-medium text-blue-800 mb-2">📤 Streaming file to receiver...</p>
                <div className="h-2 bg-blue-200 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-blue-600 rounded-full transition-all duration-200"
                    style={{ width: `${transferProgress}%` }}
                  />
                </div>
                <p className="text-sm text-blue-600 mt-1">{transferProgress}% complete</p>
              </div>
            )}

            {shareMode === 'instant' && wsStatus === 'done' && (
              <div className="mt-4 p-4 bg-green-50 border border-green-200 rounded-lg">
                <p className="font-medium text-green-800">✅ Transfer complete! File delivered successfully.</p>
              </div>
            )}

            {wsStatus === 'error' && (
              <div className="mt-4 p-4 bg-red-50 border border-red-200 rounded-lg">
                <p className="font-medium text-red-800">❌ Connection error. Please try again.</p>
              </div>
            )}

            {/* ── S3 Upload Status ── */}
            {shareMode === 'cloud' && uploadedFile && !isUploading && token && (
              <div className="mt-4 p-3 bg-gray-50 rounded-md">
                <p className="text-sm text-gray-600">
                  Uploaded: <span className="font-medium">{uploadedFile.name}</span> ({Math.round(uploadedFile.size / 1024)} KB)
                </p>
              </div>
            )}

            {isUploading && (
              <div className="mt-6 text-center">
                <div className="inline-block animate-spin rounded-full h-8 w-8 border-4 border-blue-500 border-t-transparent"></div>
                <p className="mt-2 text-gray-600">Uploading to cloud...</p>
              </div>
            )}

            <InviteCode port={port} token={token} />
          </div>
        ) : (
          <div>
            <FileDownload onDownload={handleDownload} isDownloading={isDownloading} />

            {isDownloading && (
              <div className="mt-6 text-center">
                <div className="inline-block animate-spin rounded-full h-8 w-8 border-4 border-blue-500 border-t-transparent"></div>
                <p className="mt-2 text-gray-600">Downloading file...</p>
              </div>
            )}
          </div>
        )}
      </div>

      <footer className="mt-12 text-center text-gray-500 text-sm">
        <p>PeerLink &copy; {new Date().getFullYear()} - Secure P2P File Sharing</p>
      </footer>
    </div>
  );
}
