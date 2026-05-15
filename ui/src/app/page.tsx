'use client';

import { useState, useRef, useCallback, useEffect } from 'react';
import FileUpload from '@/components/FileUpload';
import FileDownload from '@/components/FileDownload';
import InviteCode from '@/components/InviteCode';
import axios from 'axios';

// Base URLs — set dynamically on mount
let API_BASE_URL = '';
let WS_BASE_URL = '';
const CHUNK_SIZE = 64 * 1024; // 64KB chunks for WebSocket relay

// ─── E2E Encryption helpers (AES-256-GCM via Web Crypto API) ───────────────
// The key never leaves the browser — it travels only in the URL #fragment,
// which browsers never include in HTTP requests (server is blind to it).

async function generateAesKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}

async function exportKeyAsBase64(key: CryptoKey): Promise<string> {
  const raw = await crypto.subtle.exportKey('raw', key);
  return btoa(String.fromCharCode.apply(null, Array.from(new Uint8Array(raw))));
}

async function importKeyFromBase64(b64: string): Promise<CryptoKey> {
  const raw = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['decrypt']);
}

/**
 * Encrypt one chunk. Returns: [12-byte IV | ciphertext].
 * Each chunk gets a fresh random IV — reuse impossible even across 500MB files.
 */
async function encryptChunk(key: CryptoKey, plaintext: ArrayBuffer): Promise<ArrayBuffer> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext);
  const result = new Uint8Array(12 + ciphertext.byteLength);
  result.set(iv, 0);
  result.set(new Uint8Array(ciphertext), 12);
  return result.buffer;
}

/**
 * Decrypt one chunk. Expects: [12-byte IV | ciphertext].
 */
async function decryptChunk(key: CryptoKey, data: ArrayBuffer): Promise<ArrayBuffer> {
  const iv = data.slice(0, 12);
  const ciphertext = data.slice(12);
  return crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ciphertext);
}
// ────────────────────────────────────────────────────────────────────────────

export default function Home() {
  // ─── Dynamic Backend Discovery ───────────────────────────────────────
  // In PRODUCTION (behind Nginx): everything is on the SAME origin.
  //   API  = https://peerlink.example.com/api
  //   WS   = wss://peerlink.example.com/ws
  // In LOCAL DEV (no Nginx): Java runs on separate ports.
  //   API  = http://localhost:8080   (or :3001)
  //   WS   = ws://localhost:8081     (or :3002)
  // ─────────────────────────────────────────────────────────────────────
  useEffect(() => {
    if (typeof window !== 'undefined') {
      const { hostname, protocol, port } = window.location;
      const isSecure = protocol === 'https:';
      const isLocal = hostname === 'localhost' || hostname === '127.0.0.1'
        || hostname.startsWith('10.') || hostname.startsWith('192.168.');

      if (isLocal) {
        // Local dev: UI is on :3000, Java HTTP on :3001, Java WS on :3002
        const javaHttpPort = parseInt(port || '3000') + 1;   // 3001
        const javaWsPort   = parseInt(port || '3000') + 2;   // 3002
        API_BASE_URL = `http://${hostname}:${javaHttpPort}`;
        WS_BASE_URL  = `ws://${hostname}:${javaWsPort}`;
      } else {
        // Production: check if env vars point to a separate backend (Vercel + Railway)
        const envApi = process.env.NEXT_PUBLIC_API_URL;
        const envWs  = process.env.NEXT_PUBLIC_WS_URL;

        if (envApi) {
          // Separate frontend/backend deployment (Vercel → Railway)
          API_BASE_URL = envApi;
          WS_BASE_URL  = envWs || `${isSecure ? 'wss' : 'ws'}://${hostname}/ws`;
        } else {
          // Same-origin deployment (behind Nginx)
          const origin = window.location.origin;
          API_BASE_URL = `${origin}/api`;
          WS_BASE_URL  = `${isSecure ? 'wss' : 'ws'}://${hostname}${port ? ':' + port : ''}/ws`;
        }
      }

      console.log('🌐 MODE:', isLocal ? 'LOCAL DEV' : 'PRODUCTION');
      console.log('📡 API:', API_BASE_URL);
      console.log('🔌 WS:', WS_BASE_URL);
    }
  }, []);

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

  // E2E encryption — key lives only in this ref and the URL #fragment (never sent to server)
  const aesKeyRef = useRef<CryptoKey | null>(null);

  // ─── WebSocket Relay: encrypt + send file in chunks with backpressure ───
  const sendFileViaWebSocket = useCallback((ws: WebSocket, file: File, aesKey: CryptoKey | null) => {
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
      chunk.arrayBuffer().then((plaintext) => {
        // AES-GCM: prepend fresh 12-byte IV to each ciphertext chunk (if key exists)
        if (aesKey) {
          return encryptChunk(aesKey, plaintext);
        }
        return plaintext; // Send as-is if no encryption
      }).then((data) => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(data);
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
      // Step 1: Generate AES-256-GCM key — stays in browser only
      let aesKey = null;
      let keyB64 = null;
      
      if (typeof window !== 'undefined' && window.crypto && window.crypto.subtle) {
        try {
          aesKey = await generateAesKey();
          aesKeyRef.current = aesKey;
          keyB64 = await exportKeyAsBase64(aesKey);
        } catch (e) {
          console.warn('Encryption failed to initialize:', e);
        }
      } else {
        console.warn('Crypto API not available (Insecure context?)');
      }

      // Step 2: Register file metadata (no file upload, no key sent to server)
      const response = await axios.post(`${API_BASE_URL}/register`, {
        filename: file.name,
        size: file.size,
        contentType: file.type || 'application/octet-stream',
      });

      const newToken = response.data.token;
      setToken(newToken);

      // Step 3: Embed key in URL fragment — browsers never send #fragment to the server
      if (keyB64) {
        window.location.hash = `key=${encodeURIComponent(keyB64)}`;
      }

      // Step 4: Open WebSocket connection
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
              sendFileViaWebSocket(ws, fileRef.current, aesKeyRef.current);
            }
          }
        } catch {
          // Non-JSON message, ignore
        }
      };

      ws.onerror = () => {
        setWsStatus('error');
      };

      ws.onclose = () => {
        if (wsRef.current === ws) {
          wsRef.current = null;
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

  // ─── Download handler ───
  const handleDownload = async (_port: number, downloadToken?: string) => {
    setIsDownloading(true);

    try {
      const normalizedToken = (downloadToken || '').trim().toUpperCase();
      const response = await axios.get(
        `${API_BASE_URL}/download?token=${encodeURIComponent(normalizedToken)}`,
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

      // ─── E2E Decryption ─────────────────────────────────────────────────
      const ENCRYPTED_CHUNK_STRIDE = CHUNK_SIZE + 28;
      const hash = window.location.hash;
      const keyParam = hash.startsWith('#key=') ? hash.slice(5) : null;

      let finalBuffer: ArrayBuffer;
      if (keyParam) {
        try {
          const aesKey = await importKeyFromBase64(decodeURIComponent(keyParam));
          const encrypted = response.data as ArrayBuffer;
          const plaintextChunks: ArrayBuffer[] = [];
          let pos = 0;

          while (pos < encrypted.byteLength) {
            const stride = Math.min(ENCRYPTED_CHUNK_STRIDE, encrypted.byteLength - pos);
            const chunkData = encrypted.slice(pos, pos + stride);
            const plain = await decryptChunk(aesKey, chunkData);
            plaintextChunks.push(plain);
            pos += stride;
          }

          const totalBytes = plaintextChunks.reduce((n, c) => n + c.byteLength, 0);
          const assembled = new Uint8Array(totalBytes);
          let byteOffset = 0;
          for (const chunk of plaintextChunks) {
            assembled.set(new Uint8Array(chunk), byteOffset);
            byteOffset += chunk.byteLength;
          }
          finalBuffer = assembled.buffer;
          window.location.hash = '';
        } catch {
          alert('Decryption failed — the key in your URL may be wrong or the file was corrupted.');
          return;
        }
      } else {
        finalBuffer = response.data as ArrayBuffer;
      }
      // ────────────────────────────────────────────────────────────────────

      const blob = new Blob([finalBuffer], { type: contentType });
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

      if (error?.response?.data instanceof ArrayBuffer) {
        try {
          const decoder = new TextDecoder('utf-8');
          const decoded = decoder.decode(error.response.data);
          if (decoded && decoded.length < 300) {
            message = decoded;
          }
        } catch (decodeErr) {
          console.error('Failed to decode error ArrayBuffer', decodeErr);
        }
      } else if (typeof error?.response?.data === 'string') {
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
                  className={`flex items-start gap-3 p-3 rounded-lg border-2 cursor-pointer transition-all ${shareMode === 'instant'
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
                    <span className="font-medium text-gray-800">Share Now</span>
                    <p className="text-xs text-gray-500 mt-0.5">
                      Stream directly to receiver. Keep this tab open.
                    </p>
                  </div>
                </label>
                <label
                  className={`flex items-start gap-3 p-3 rounded-lg border-2 cursor-pointer transition-all ${shareMode === 'cloud'
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
                    <span className="font-medium text-gray-800">Upload & Share Later</span>
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
