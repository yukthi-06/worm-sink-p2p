# WormSink WebRTC Signaling Server (Cloudflare Workers)

This directory contains the lightweight signaling server code for the WormSink P2P file transfer client. It runs entirely on Cloudflare Workers.

---

## Folder Structure
*   `index.js`: The Cloudflare Worker implementation handling sessions, offer/answer exchange, and ICE candidates.
*   `wrangler.toml`: The wrangler CLI deployment configuration file.
*   `package.json`: NPM package management for developer tools like Wrangler.

---

## Local Development (Testing)

You can run the server locally on your machine mock environment without deploying to Cloudflare.

### Prerequisite
*   Ensure you have [Node.js](https://nodejs.org) installed on your system.

### Steps
1.  Navigate into this directory:
    ```bash
    cd signaling-server-cloudflare
    ```
2.  Install dependencies:
    ```bash
    npm install
    ```
3.  Start the local development server:
    ```bash
    npm run dev
    ```
    This will start a local mock server at:
    `http://localhost:8787`

You can supply this address to your WormSink CLI app using the `--signaling-url` parameter:
```bash
java -jar wormsink-1.0.0-fatjar.jar send file.iso --signaling-url="http://localhost:8787"
```

---

## Cloudflare Deployment

### 1. Login to Wrangler
To login to your Cloudflare account from your command line:
```bash
npx wrangler login
```

### 2. Create the KV Namespace
WormSink uses Cloudflare Workers KV to temporarily persist signaling payloads. Create the KV namespace by running:
```bash
npx wrangler kv:namespace create SESSIONS
```

This command will output two configurations:
*   A namespace configuration for your `wrangler.toml` file (production).
*   A namespace configuration for local preview.

### 3. Update `wrangler.toml`
Open `wrangler.toml` and paste the production namespace block. It should look like this:
```toml
[[kv_namespaces]]
binding = "SESSIONS"
id = "PASTE_YOUR_KV_NAMESPACE_ID_HERE"
```

### 4. Deploy to Production
To upload and publish your worker live on Cloudflare's Edge:
```bash
npm run deploy
```

Once deployed, wrangler will output your worker's live URL (e.g., `https://wormsinks.your-subdomain.workers.dev`). You can use this URL globally!
