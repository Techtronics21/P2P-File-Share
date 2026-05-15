/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  // Standalone output mode: produces a self-contained build for Docker
  output: 'standalone',
  // Increase body size limit to 500MB for large file uploads
  experimental: {
    serverActions: {
      bodySizeLimit: '500mb',
    },
  },
}

module.exports = nextConfig
