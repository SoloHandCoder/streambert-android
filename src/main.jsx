import "./mobile/electronShim.js";
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles/global.css";
import "./styles/mobile.css";
import { initTvNav } from "./mobile/tvNav.js";
import { initPullToRefresh } from "./mobile/pullToRefresh.js";
import { initIncoming } from "./mobile/incoming.js";
import { initEpisodeWatch } from "./mobile/episodeWatch.js";

initTvNav();
if (window.electron?.isMobile) {
  initPullToRefresh();
  initIncoming();
  initEpisodeWatch();
}

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
