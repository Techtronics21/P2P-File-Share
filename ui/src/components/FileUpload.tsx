'use client';

import React, { useState, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Upload, X, File as FileIcon, CheckCircle, AlertCircle } from 'lucide-react';

interface FileUploadProps {
  onUploadComplete: (data: { port: number; token: string }) => void;
}

const FileUpload: React.FC<FileUploadProps> = ({ onUploadComplete }) => {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [error, setError] = useState<string | null>(null); // Added error state
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setFile(e.target.files[0]);
      setError(null); // Clear errors when a new file is selected
      setUploadProgress(0);
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      setFile(e.dataTransfer.files[0]);
      setError(null);
      setUploadProgress(0);
    }
  };

  const handleUpload = () => {
    if (!file) return;

    setUploading(true);
    setError(null);
    setUploadProgress(0);

    const formData = new FormData();
    formData.append('file', file);

    const xhr = new XMLHttpRequest();

    // Track upload progress
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        const percentComplete = (event.loaded / event.total) * 100;
        setUploadProgress(percentComplete);
      }
    };

    xhr.onload = () => {
      if (xhr.status === 200) {
        try {
          const response = JSON.parse(xhr.responseText);
          setUploading(false);
          onUploadComplete(response);
        } catch (e) {
          setError('Invalid server response');
          setUploading(false);
        }
      } else {
        // Handle specific backend errors
        setUploading(false);
        setUploadProgress(0);

        if (xhr.status === 429) {
          // Handled per UploadHandler.java rate limiting
          setError("Please wait: You are uploading too fast (Limit: 10 uploads/min).");
        } else if (xhr.status === 413) {
          // Handled per UploadHandler.java MAX_FILE_SIZE (500MB)
          setError("Upload failed: The file is too large (Max 500MB).");
        } else if (xhr.status === 415) {
          // Handled per UploadHandler.java whitelist checks
          setError("Upload failed: This file type is not supported.");
        } else {
          // Fallback for 400, 500, etc.
          setError(`Upload failed: ${xhr.responseText || 'Server error'}`);
        }
      }
    };

    xhr.onerror = () => {
      setUploading(false);
      setError('Connection failed. Please check if the server is running.');
    };

    // Ensure this matches your backend URL
    xhr.open('POST', 'http://localhost:8080/upload');
    xhr.send(formData);
  };

  return (
    <div className="w-full max-w-md mx-auto">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="bg-white rounded-xl shadow-xl overflow-hidden"
      >
        <div className="p-8">
          <div 
            className={`border-2 border-dashed rounded-xl p-8 text-center transition-colors ${
              file ? 'border-indigo-500 bg-indigo-50' : 'border-gray-300 hover:border-indigo-400'
            }`}
            onDragOver={(e) => e.preventDefault()}
            onDrop={handleDrop}
          >
            <AnimatePresence mode="wait">
              {!file ? (
                <motion.div
                  key="empty"
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0 }}
                  className="space-y-4"
                >
                  <div className="w-16 h-16 bg-indigo-100 text-indigo-600 rounded-full flex items-center justify-center mx-auto mb-4">
                    <Upload size={32} />
                  </div>
                  <h3 className="text-xl font-semibold text-gray-700">Drop your file here</h3>
                  <p className="text-gray-500">or click to browse</p>
                  <input
                    ref={fileInputRef}
                    type="file"
                    className="hidden"
                    onChange={handleFileChange}
                  />
                  <button
                    onClick={() => fileInputRef.current?.click()}
                    className="px-6 py-2 bg-white border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50 transition-colors"
                  >
                    Select File
                  </button>
                </motion.div>
              ) : (
                <motion.div
                  key="selected"
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0 }}
                  className="relative"
                >
                  <button
                    onClick={() => {
                      setFile(null);
                      setError(null);
                    }}
                    className="absolute -top-4 -right-4 p-1 bg-white rounded-full shadow-md text-gray-400 hover:text-red-500 transition-colors"
                  >
                    <X size={20} />
                  </button>
                  <FileIcon size={48} className="mx-auto text-indigo-600 mb-4" />
                  <p className="text-gray-700 font-medium truncate mb-1">{file.name}</p>
                  <p className="text-gray-500 text-sm mb-4">
                    {(file.size / (1024 * 1024)).toFixed(2)} MB
                  </p>
                  
                  {uploading ? (
                    <div className="space-y-2">
                      <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                        <motion.div
                          className="h-full bg-indigo-600"
                          initial={{ width: 0 }}
                          animate={{ width: `${uploadProgress}%` }}
                          transition={{ duration: 0.2 }}
                        />
                      </div>
                      <p className="text-sm text-indigo-600 font-medium">
                        Uploading... {Math.round(uploadProgress)}%
                      </p>
                    </div>
                  ) : (
                    <button
                      onClick={handleUpload}
                      className="w-full py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors flex items-center justify-center gap-2"
                    >
                      Share File
                    </button>
                  )}
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* Error Message Display */}
          <AnimatePresence>
            {error && (
              <motion.div
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
                className="mt-4 p-3 bg-red-50 text-red-600 rounded-lg text-sm flex items-center gap-2"
              >
                <AlertCircle size={16} />
                <span>{error}</span>
              </motion.div>
            )}
          </AnimatePresence>

          <p className="mt-6 text-center text-sm text-gray-500">
            Max file size: 500MB • Files expire after one download
          </p>
        </div>
      </motion.div>
    </div>
  );
};

export default FileUpload;