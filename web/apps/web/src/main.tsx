import "./sentry";
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { UpdatePrompt } from "./components/UpdatePrompt";
import { watchDisplayMode } from "./utils/displayMode";
import { preconnectToApi } from "./utils/preconnect";
import { migrateLegacySession, runtimeConfig } from "./api";
import "./index.css";

// Before the first paint, so an installed app never renders a frame in which
// pinch-zoom and double-tap-zoom are still live.
watchDisplayMode();

// Ahead of the first render, so the handshake with a remote API host overlaps
// React's startup instead of delaying Discover's opening requests.
preconnectToApi(runtimeConfig.getApiBase());

const render = () =>
  ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <ErrorBoundary>
        <BrowserRouter>
          <App />
        </BrowserRouter>
        <UpdatePrompt />
      </ErrorBoundary>
    </React.StrictMode>
  );

// A browser signed in before sessions moved into cookies still has its token in
// localStorage; it is exchanged for the cookie before the app decides whether to
// show the sign-in screen. Instant for everyone else: there is nothing to read.
void migrateLegacySession()
  .catch(() => {})
  .finally(render);
