/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  // Increase body size limit to 500MB for large file uploads
  experimental: {
    serverActions: {
      bodySizeLimit: '500mb',
    },
  },
  async rewrites() {
    return [
      {
        source: '/api/upload',
        destination: 'http://localhost:8080/upload',
      },
      {
        source: '/api/download/:port',
        destination: 'http://localhost:8080/download/:port',
      },
    ];
  },
}

module.exports = nextConfig
